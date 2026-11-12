package com.example.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

data class WallpaperDiagnosticsResult(
    val recordedAtMillis: Long,
    val durationMillis: Long,
    val visibleDurationNanos: Long,
    val visibleOffsetCallbacks: Int,
    val changedOffsetCallbacks: Int,
    val movingOffsetIntervalCount: Int,
    val movingOffsetDurationNanos: Long,
    val visibleFramesSubmitted: Int,
    val offsetDrivenFramesSubmitted: Int,
    val movingFrameIntervalCount: Int,
    val movingFrameDurationNanos: Long
) {
    val averageOffsetCallbackHz: Float
        get() = rate(visibleOffsetCallbacks, visibleDurationNanos)

    val movingOffsetHz: Float?
        get() = rateOrNull(movingOffsetIntervalCount, movingOffsetDurationNanos)

    val averageFrameSubmissionHz: Float
        get() = rate(visibleFramesSubmitted, visibleDurationNanos)

    val movingFrameSubmissionHz: Float?
        get() = rateOrNull(movingFrameIntervalCount, movingFrameDurationNanos)

    private fun rate(count: Int, durationNanos: Long): Float =
        if (durationNanos > 0L) count * 1_000_000_000f / durationNanos else 0f

    private fun rateOrNull(count: Int, durationNanos: Long): Float? =
        if (count > 0 && durationNanos > 0L) rate(count, durationNanos) else null
}

data class WallpaperDiagnosticsState(
    val isRecording: Boolean = false,
    val current: WallpaperDiagnosticsResult? = null,
    val history: List<WallpaperDiagnosticsResult> = emptyList()
)

internal class WallpaperDiagnosticsSession(
    private val startedAtNanos: Long,
    private val recordedAtMillis: Long,
    initiallyVisible: Boolean
) {
    private var lastOffset: Float? = null
    private var lastChangedOffsetNanos: Long? = null
    private var lastOffsetFrameNanos: Long? = null
    private var isVisible = initiallyVisible
    private var visibleSinceNanos: Long? = if (initiallyVisible) startedAtNanos else null
    private var visibleDurationNanos = 0L

    private var visibleOffsetCallbacks = 0
    private var changedOffsetCallbacks = 0
    private var movingOffsetIntervalCount = 0
    private var movingOffsetDurationNanos = 0L
    private var visibleFramesSubmitted = 0
    private var offsetDrivenFramesSubmitted = 0
    private var movingFrameIntervalCount = 0
    private var movingFrameDurationNanos = 0L

    fun recordVisibilityChanged(visible: Boolean, timestampNanos: Long) {
        if (isVisible == visible) return
        if (visible) {
            visibleSinceNanos = timestampNanos
        } else {
            visibleSinceNanos?.let { visibleDurationNanos += (timestampNanos - it).coerceAtLeast(0L) }
            visibleSinceNanos = null
        }
        isVisible = visible
        lastOffset = null
        lastChangedOffsetNanos = null
        lastOffsetFrameNanos = null
    }

    fun recordOffsetUpdate(xOffset: Float, isVisible: Boolean, timestampNanos: Long) {
        recordVisibilityChanged(isVisible, timestampNanos)
        if (!isVisible) return
        visibleOffsetCallbacks++

        val previousOffset = lastOffset
        if (previousOffset != null && abs(xOffset - previousOffset) > OFFSET_CHANGE_EPSILON) {
            changedOffsetCallbacks++
            lastChangedOffsetNanos?.let { previousTimestamp ->
                val interval = timestampNanos - previousTimestamp
                if (interval in 1..MAX_ACTIVE_INTERVAL_NANOS) {
                    movingOffsetIntervalCount++
                    movingOffsetDurationNanos += interval
                }
            }
            lastChangedOffsetNanos = timestampNanos
        }
        lastOffset = xOffset
    }

    fun recordFrameSubmitted(
        fromOffsetUpdate: Boolean,
        isVisible: Boolean,
        timestampNanos: Long
    ) {
        recordVisibilityChanged(isVisible, timestampNanos)
        if (!isVisible) return
        visibleFramesSubmitted++
        if (!fromOffsetUpdate) return

        offsetDrivenFramesSubmitted++
        lastOffsetFrameNanos?.let { previousTimestamp ->
            val interval = timestampNanos - previousTimestamp
            if (interval in 1..MAX_ACTIVE_INTERVAL_NANOS) {
                movingFrameIntervalCount++
                movingFrameDurationNanos += interval
            }
        }
        lastOffsetFrameNanos = timestampNanos
    }

    fun snapshot(timestampNanos: Long): WallpaperDiagnosticsResult {
        val currentVisibleDuration = visibleDurationNanos +
            (visibleSinceNanos?.let { (timestampNanos - it).coerceAtLeast(0L) } ?: 0L)
        return WallpaperDiagnosticsResult(
            recordedAtMillis = recordedAtMillis,
            durationMillis = ((timestampNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000L),
            visibleDurationNanos = currentVisibleDuration,
            visibleOffsetCallbacks = visibleOffsetCallbacks,
            changedOffsetCallbacks = changedOffsetCallbacks,
            movingOffsetIntervalCount = movingOffsetIntervalCount,
            movingOffsetDurationNanos = movingOffsetDurationNanos,
            visibleFramesSubmitted = visibleFramesSubmitted,
            offsetDrivenFramesSubmitted = offsetDrivenFramesSubmitted,
            movingFrameIntervalCount = movingFrameIntervalCount,
            movingFrameDurationNanos = movingFrameDurationNanos
        )
    }

    companion object {
        private const val OFFSET_CHANGE_EPSILON = 0.0001f
        private const val MAX_ACTIVE_INTERVAL_NANOS = 500_000_000L
    }
}

object WallpaperDiagnosticsRecorder {
    private const val TAG = "WallpaperDiagnostics"
    private const val PREFS_NAME = "wallpaper_diagnostics"
    private const val HISTORY_KEY = "recording_history"
    private const val MAX_HISTORY_SIZE = 10
    private const val PUBLISH_INTERVAL_NANOS = 250_000_000L

    private val lock = Any()
    private val mutableState = MutableStateFlow(WallpaperDiagnosticsState())
    val state: StateFlow<WallpaperDiagnosticsState> = mutableState.asStateFlow()

    private var preferences: SharedPreferences? = null
    @Volatile
    private var activeSession: WallpaperDiagnosticsSession? = null
    private var lastPublishedAtNanos = 0L
    private var wallpaperVisible = false

    fun initialize(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            preferences = prefs
            mutableState.value = WallpaperDiagnosticsState(history = loadHistory(prefs))
        }
    }

    fun startRecording() {
        synchronized(lock) {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val history = mutableState.value.history
            activeSession = WallpaperDiagnosticsSession(
                nowNanos,
                System.currentTimeMillis(),
                wallpaperVisible
            )
            lastPublishedAtNanos = nowNanos
            mutableState.value = WallpaperDiagnosticsState(
                isRecording = true,
                current = activeSession?.snapshot(nowNanos),
                history = history
            )
        }
    }

    fun stopRecording() {
        synchronized(lock) {
            val session = activeSession ?: return
            val result = session.snapshot(SystemClock.elapsedRealtimeNanos())
            val history = (listOf(result) + mutableState.value.history).take(MAX_HISTORY_SIZE)
            activeSession = null
            preferences?.edit()
                ?.putString(HISTORY_KEY, serializeHistory(history))
                ?.apply()
            mutableState.value = WallpaperDiagnosticsState(history = history)
            Log.i(
                TAG,
                "Recording complete: durationMs=${result.durationMillis}, " +
                    "visibleDurationMs=${result.visibleDurationNanos / 1_000_000L}, " +
                    "offsetCallbacks=${result.visibleOffsetCallbacks}, " +
                    "averageOffsetCallbackHz=${result.averageOffsetCallbackHz}, " +
                    "changedOffsets=${result.changedOffsetCallbacks}, " +
                    "movingOffsetHz=${result.movingOffsetHz}, " +
                    "visibleFrames=${result.visibleFramesSubmitted}, " +
                    "averageFrameSubmissionHz=${result.averageFrameSubmissionHz}, " +
                    "offsetDrivenFrames=${result.offsetDrivenFramesSubmitted}, " +
                    "movingFrameSubmissionHz=${result.movingFrameSubmissionHz}"
            )
        }
    }

    fun recordOffsetUpdate(xOffset: Float, isVisible: Boolean) {
        if (activeSession == null) return
        synchronized(lock) {
            val session = activeSession ?: return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            session.recordOffsetUpdate(xOffset, isVisible, nowNanos)
            publishIfDue(session, nowNanos)
        }
    }

    fun recordFrameSubmitted(fromOffsetUpdate: Boolean, isVisible: Boolean) {
        if (activeSession == null) return
        synchronized(lock) {
            val session = activeSession ?: return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            session.recordFrameSubmitted(fromOffsetUpdate, isVisible, nowNanos)
            publishIfDue(session, nowNanos)
        }
    }

    fun recordVisibilityChanged(isVisible: Boolean) {
        synchronized(lock) {
            wallpaperVisible = isVisible
            val session = activeSession ?: return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            session.recordVisibilityChanged(isVisible, nowNanos)
            publishIfDue(session, nowNanos)
        }
    }

    private fun publishIfDue(session: WallpaperDiagnosticsSession, nowNanos: Long) {
        if (nowNanos - lastPublishedAtNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishedAtNanos = nowNanos
        mutableState.value = WallpaperDiagnosticsState(
            isRecording = true,
            current = session.snapshot(nowNanos),
            history = mutableState.value.history
        )
    }

    private fun loadHistory(prefs: SharedPreferences): List<WallpaperDiagnosticsResult> {
        return try {
            val entries = JSONArray(prefs.getString(HISTORY_KEY, "[]"))
            (0 until entries.length()).mapNotNull { index ->
                entries.optJSONObject(index)?.let(::resultFromJson)
            }.take(MAX_HISTORY_SIZE)
        } catch (e: JSONException) {
            Log.e(TAG, "Could not read recording history", e)
            emptyList()
        }
    }

    private fun serializeHistory(history: List<WallpaperDiagnosticsResult>): String =
        JSONArray().apply {
            history.forEach { result ->
                put(
                    JSONObject()
                        .put("recordedAtMillis", result.recordedAtMillis)
                        .put("durationMillis", result.durationMillis)
                        .put("visibleDurationNanos", result.visibleDurationNanos)
                        .put("visibleOffsetCallbacks", result.visibleOffsetCallbacks)
                        .put("changedOffsetCallbacks", result.changedOffsetCallbacks)
                        .put("movingOffsetIntervalCount", result.movingOffsetIntervalCount)
                        .put("movingOffsetDurationNanos", result.movingOffsetDurationNanos)
                        .put("visibleFramesSubmitted", result.visibleFramesSubmitted)
                        .put("offsetDrivenFramesSubmitted", result.offsetDrivenFramesSubmitted)
                        .put("movingFrameIntervalCount", result.movingFrameIntervalCount)
                        .put("movingFrameDurationNanos", result.movingFrameDurationNanos)
                )
            }
        }.toString()

    private fun resultFromJson(json: JSONObject): WallpaperDiagnosticsResult =
        WallpaperDiagnosticsResult(
            recordedAtMillis = json.getLong("recordedAtMillis"),
            durationMillis = json.getLong("durationMillis"),
            visibleDurationNanos = json.getLong("visibleDurationNanos"),
            visibleOffsetCallbacks = json.getInt("visibleOffsetCallbacks"),
            changedOffsetCallbacks = json.getInt("changedOffsetCallbacks"),
            movingOffsetIntervalCount = json.getInt("movingOffsetIntervalCount"),
            movingOffsetDurationNanos = json.getLong("movingOffsetDurationNanos"),
            visibleFramesSubmitted = json.getInt("visibleFramesSubmitted"),
            offsetDrivenFramesSubmitted = json.getInt("offsetDrivenFramesSubmitted"),
            movingFrameIntervalCount = json.getInt("movingFrameIntervalCount"),
            movingFrameDurationNanos = json.getLong("movingFrameDurationNanos")
        )
}
