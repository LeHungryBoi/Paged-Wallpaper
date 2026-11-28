package com.example.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
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
    val history: List<WallpaperDiagnosticsResult> = emptyList(),
    val hasTrace: Boolean = false
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
    val isRecording: Boolean
        get() = activeSession != null

    private var preferences: SharedPreferences? = null
    private var appContext: Context? = null
    @Volatile
    private var activeSession: WallpaperDiagnosticsSession? = null
    private var lastPublishedAtNanos = 0L
    private var wallpaperVisible = false
    private val traceFrames = LinkedHashMap<Long, JSONObject>()
    private var traceEvents = JSONArray()
    private var traceDroppedEvents = 0
    private var latestTraceJson: String? = null
    private var traceRecordedAtMillis = 0L
    private var traceSessionStartNanos = 0L
    private var lastTraceOffset: Float? = null
    private var lastOffsetCallbackTraceNs = 0L
    private var offsetMotionDirection = 0
    private var offsetMotionActive = false
    private var lastSmoothingSampleNs = 0L

    private const val MAX_TRACE_FRAMES = 2048
    private const val MAX_TRACE_EVENTS = 32768
    private const val TRACE_FIELDS_PER_SAMPLE = 18
    private const val MOTION_IDLE_GAP_NANOS = 120_000_000L
    private const val SMOOTHING_SAMPLE_INTERVAL_NANOS = 16_666_667L

    fun initialize(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            val applicationContext = context.applicationContext
            appContext = applicationContext
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            preferences = prefs
            mutableState.value = WallpaperDiagnosticsState(
                history = loadHistory(prefs),
                hasTrace = latestTraceJson != null
            )
        }
    }

    fun startRecording() {
        synchronized(lock) {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val history = mutableState.value.history
            val session = WallpaperDiagnosticsSession(
                nowNanos,
                System.currentTimeMillis(),
                wallpaperVisible
            )
            traceFrames.clear()
            traceEvents = JSONArray()
            traceDroppedEvents = 0
            traceRecordedAtMillis = System.currentTimeMillis()
            traceSessionStartNanos = System.nanoTime()
            lastTraceOffset = null
            lastOffsetCallbackTraceNs = 0L
            offsetMotionDirection = 0
            offsetMotionActive = false
            lastSmoothingSampleNs = 0L
            latestTraceJson = null
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "session_start")
                    .put("timeNs", traceSessionStartNanos)
            )
            activeSession = session
            lastPublishedAtNanos = nowNanos
            mutableState.value = WallpaperDiagnosticsState(
                isRecording = true,
                current = session.snapshot(nowNanos),
                history = history,
                hasTrace = false
            )
        }
    }

    fun stopRecording() {
        synchronized(lock) {
            val session = activeSession ?: return
            val result = session.snapshot(SystemClock.elapsedRealtimeNanos())
            activeSession = null
            val stopTraceNow = System.nanoTime()
            if (offsetMotionActive) {
                appendTraceEventLocked(
                    JSONObject()
                        .put("type", "motion_stop")
                        .put("timeNs", stopTraceNow)
                        .put("lastOffsetCallbackNs", lastOffsetCallbackTraceNs)
                        .put("reason", "recording_stopped")
                )
                offsetMotionActive = false
                offsetMotionDirection = 0
            }
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "session_stop")
                    .put("timeNs", stopTraceNow)
            )
            latestTraceJson = buildTraceJson(result)
            val history = (listOf(result) + mutableState.value.history).take(MAX_HISTORY_SIZE)
            preferences?.edit()
                ?.putString(HISTORY_KEY, serializeHistory(history))
                ?.apply()
            mutableState.value = WallpaperDiagnosticsState(
                history = history,
                hasTrace = latestTraceJson != null
            )
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

    fun exportLatestTrace(): String? = synchronized(lock) { latestTraceJson }

    fun clearHistory() {
        synchronized(lock) {
            preferences?.edit()
                ?.remove(HISTORY_KEY)
                ?.apply()
            mutableState.value = mutableState.value.copy(history = emptyList())
        }
    }

    fun recordOffsetUpdate(xOffset: Float, isVisible: Boolean) {
        if (activeSession == null) return
        synchronized(lock) {
            val session = activeSession ?: return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            session.recordOffsetUpdate(xOffset, isVisible, nowNanos)
            val traceNow = System.nanoTime()
            val previousOffset = lastTraceOffset
            val delta = previousOffset?.let { xOffset - it } ?: 0f
            val direction = when {
                delta > 0.0001f -> 1
                delta < -0.0001f -> -1
                else -> 0
            }
            if (offsetMotionActive && lastOffsetCallbackTraceNs > 0L &&
                traceNow - lastOffsetCallbackTraceNs > MOTION_IDLE_GAP_NANOS
            ) {
                appendTraceEventLocked(
                    JSONObject()
                        .put("type", "motion_stop")
                        .put("timeNs", lastOffsetCallbackTraceNs)
                        .put("detectedAtNs", traceNow)
                        .put("idleGapNs", traceNow - lastOffsetCallbackTraceNs)
                        .put("reason", "offset_callback_gap")
                )
                offsetMotionActive = false
                offsetMotionDirection = 0
            }
            if (direction != 0) {
                if (!offsetMotionActive) {
                    appendTraceEventLocked(
                        JSONObject()
                            .put("type", "motion_start")
                            .put("timeNs", traceNow)
                            .put("source", "launcher_offset")
                            .put("direction", direction)
                    )
                    offsetMotionActive = true
                } else if (offsetMotionDirection != 0 && direction != offsetMotionDirection) {
                    appendTraceEventLocked(
                        JSONObject()
                            .put("type", "motion_reversal")
                            .put("timeNs", traceNow)
                            .put("previousDirection", offsetMotionDirection)
                            .put("direction", direction)
                            .put("previousOffset", previousOffset?.toDouble())
                            .put("xOffset", xOffset.toDouble())
                    )
                }
                offsetMotionDirection = direction
            }
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "offset")
                    .put("timeNs", traceNow)
                    .put("xOffset", xOffset.toDouble())
                    .put("previousXOffset", previousOffset?.toDouble())
                    .put("delta", delta.toDouble())
                    .put("direction", direction)
                    .put("visible", isVisible)
            )
            lastTraceOffset = xOffset
            lastOffsetCallbackTraceNs = traceNow
            publishIfDue(session, nowNanos)
        }
    }

    fun recordFrameSubmitted(
        fromOffsetUpdate: Boolean,
        isVisible: Boolean,
        frameId: Long = -1L
    ) {
        if (activeSession == null) return
        synchronized(lock) {
            val session = activeSession ?: return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            session.recordFrameSubmitted(fromOffsetUpdate, isVisible, nowNanos)
            val traceNow = System.nanoTime()
            if (frameId >= 0L) traceFrames[frameId]?.put("submittedCallbackNs", traceNow)
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "frame_submitted_callback")
                    .put("timeNs", traceNow)
                    .put("frameId", frameId)
                    .put("fromOffsetUpdate", fromOffsetUpdate)
                    .put("visible", isVisible)
            )
            publishIfDue(session, nowNanos)
        }
    }

    fun recordFrameRequested(
        frameId: Long,
        requestTimeNanos: Long,
        fromOffsetUpdate: Boolean,
        xOffset: Float,
        pageA: Int,
        pageB: Int,
        progress: Float,
        edgeOverScrollFraction: Float,
        motionBlurRadius: Int
    ) {
        if (activeSession == null) return
        synchronized(lock) {
            traceFrames[frameId] = JSONObject()
                .put("frameId", frameId)
                .put("requestTimeNs", requestTimeNanos)
                .put("fromOffsetUpdate", fromOffsetUpdate)
                .put("xOffset", xOffset.toDouble())
                .put("pageA", pageA)
                .put("pageB", pageB)
                .put("progress", progress.toDouble())
                .put("edgeOverScrollFraction", edgeOverScrollFraction.toDouble())
                .put("motionBlurRadius", motionBlurRadius)
            trimTraceFramesLocked()
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "frame_requested")
                    .put("timeNs", requestTimeNanos)
                    .put("frameId", frameId)
                    .put("fromOffsetUpdate", fromOffsetUpdate)
            )
        }
    }

    fun recordFrameCoalesced(fromOffsetUpdate: Boolean) {
        if (activeSession == null) return
        synchronized(lock) {
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "frame_request_coalesced")
                    .put("timeNs", System.nanoTime())
                    .put("fromOffsetUpdate", fromOffsetUpdate)
            )
        }
    }

    fun recordTouchEvent(
        action: Int,
        x: Float,
        y: Float,
        xOffset: Float,
        edgeOverScrollFraction: Float
    ) {
        if (activeSession == null) return
        synchronized(lock) {
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "touch")
                    .put("timeNs", System.nanoTime())
                    .put("action", action)
                    .put("x", x.toDouble())
                    .put("y", y.toDouble())
                    .put("xOffset", xOffset.toDouble())
                    .put("edgeOverScrollFraction", edgeOverScrollFraction.toDouble())
            )
        }
    }

    /**
     * Samples the actual Choreographer-driven interpolation at a capped 60 Hz.
     * The trace remains useful on 120/165 Hz panels without allocating an event
     * for every animation callback.
     */
    fun recordSmoothingStep(
        targetXOffset: Float,
        renderedXOffset: Float,
        smoothingFraction: Float,
        elapsedNanos: Long
    ) {
        if (activeSession == null) return
        synchronized(lock) {
            val now = System.nanoTime()
            if (lastSmoothingSampleNs != 0L &&
                now - lastSmoothingSampleNs < SMOOTHING_SAMPLE_INTERVAL_NANOS
            ) return
            lastSmoothingSampleNs = now
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "smoothing_step")
                    .put("timeNs", now)
                    .put("targetXOffset", targetXOffset.toDouble())
                    .put("renderedXOffset", renderedXOffset.toDouble())
                    .put("error", (targetXOffset - renderedXOffset).toDouble())
                    .put("smoothingFraction", smoothingFraction.toDouble())
                    .put("elapsedNs", elapsedNanos)
            )
        }
    }

    fun recordFrameRenderStage(frameId: Long, stage: String) {
        if (activeSession == null) return
        synchronized(lock) {
            val now = System.nanoTime()
            traceFrames[frameId]?.put(stage + "Ns", now)
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "render_stage")
                    .put("timeNs", now)
                    .put("frameId", frameId)
                    .put("stage", stage)
            )
        }
    }

    fun recordFrameFailure(frameId: Long, reason: String) {
        if (activeSession == null) return
        synchronized(lock) {
            val now = System.nanoTime()
            traceFrames[frameId]?.put("failure", reason)
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "frame_failure")
                    .put("timeNs", now)
                    .put("frameId", frameId)
                    .put("reason", reason)
            )
        }
    }

    fun recordNativeFrameTrace(values: LongArray) {
        if (activeSession == null || values.isEmpty()) return
        synchronized(lock) {
            var index = 0
            while (index + TRACE_FIELDS_PER_SAMPLE <= values.size) {
                val revision = values[index]
                val frameId = values[index + 1]
                val requestTimeNanos = values[index + 2]
                if (requestTimeNanos < traceSessionStartNanos) {
                    index += TRACE_FIELDS_PER_SAMPLE
                    continue
                }
                val frame = traceFrames.getOrPut(frameId) { JSONObject().put("frameId", frameId) }
                frame.put("nativeTraceRevision", revision)
                frame.put("nativeRequestTimeNs", values[index + 2])
                putOptionalLong(frame, "fenceWaitNs", values[index + 3])
                putOptionalLong(frame, "acquireNs", values[index + 4])
                putOptionalLong(frame, "vkBeginCpuNs", values[index + 5])
                putOptionalLong(frame, "queueSubmitNs", values[index + 6])
                putOptionalLong(frame, "queuePresentCallNs", values[index + 7])
                putOptionalLong(frame, "vkEndCpuNs", values[index + 8])
                putOptionalLong(frame, "gpuFrameNs", values[index + 9])
                putOptionalLong(frame, "gpuOffscreenNs", values[index + 10])
                putOptionalLong(frame, "gpuMainNs", values[index + 11])
                frame.put("presentId", values[index + 12])
                if (values[index + 13] != 0L) frame.put("actualPresentTimeDeviceNs", values[index + 13])
                putOptionalLong(frame, "presentMarginNs", values[index + 14])
                frame.put("refreshCycleDurationNs", values[index + 15])
                putOptionalLong(frame, "offscreenTargetSetupNs", values[index + 16])
                val flags = values[index + 17]
                frame.put("nativeFlags", flags)
                frame.put("gpuTimestampsAvailable", flags and 1L != 0L)
                frame.put("offscreenPassUsed", flags and (1L shl 1) != 0L)
                frame.put("presentCallSucceeded", flags and (1L shl 2) != 0L)
                frame.put("displayTimingEnabled", flags and (1L shl 3) != 0L)
                trimTraceFramesLocked()
                index += TRACE_FIELDS_PER_SAMPLE
            }
        }
    }

    private fun putOptionalLong(target: JSONObject, key: String, value: Long) {
        if (value >= 0L) target.put(key, value) else target.put(key, JSONObject.NULL)
    }

    private fun trimTraceFramesLocked() {
        while (traceFrames.size > MAX_TRACE_FRAMES) {
            val iterator = traceFrames.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun appendTraceEventLocked(event: JSONObject) {
        if (traceEvents.length() >= MAX_TRACE_EVENTS) {
            traceDroppedEvents++
        } else {
            traceEvents.put(event)
        }
    }

    private fun buildTraceJson(result: WallpaperDiagnosticsResult): String {
        val frames = JSONArray()
        traceFrames.values.forEach { frames.put(it) }
        val summary = JSONObject()
            .put("durationMillis", result.durationMillis)
            .put("visibleDurationNanos", result.visibleDurationNanos)
            .put("visibleOffsetCallbacks", result.visibleOffsetCallbacks)
            .put("changedOffsetCallbacks", result.changedOffsetCallbacks)
            .put("visibleFramesSubmitted", result.visibleFramesSubmitted)
            .put("offsetDrivenFramesSubmitted", result.offsetDrivenFramesSubmitted)
            .put("averageOffsetCallbackHz", result.averageOffsetCallbackHz.toDouble())
            .put("averageFrameSubmissionHz", result.averageFrameSubmissionHz.toDouble())
        val context = appContext
        val displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val packageInfo = runCatching {
            context?.packageManager?.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val appMetadata = JSONObject()
            .put("packageName", context?.packageName ?: JSONObject.NULL)
            .put("versionName", packageInfo?.versionName ?: JSONObject.NULL)
            .put(
                "versionCode",
                packageInfo?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
                    else it.versionCode.toLong()
                } ?: JSONObject.NULL
            )
        val deviceMetadata = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("board", Build.BOARD)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("buildId", Build.DISPLAY)
            .put("displayRefreshRateHz", display?.refreshRate?.toDouble() ?: JSONObject.NULL)
            .put("displayWidthPx", display?.mode?.physicalWidth ?: JSONObject.NULL)
            .put("displayHeightPx", display?.mode?.physicalHeight ?: JSONObject.NULL)
        return JSONObject()
            .put("format", "paperpages-frame-trace")
            .put("schemaVersion", 1)
            .put("recordedAtMillis", traceRecordedAtMillis)
            .put("clockDomain", "System.nanoTime / CLOCK_MONOTONIC")
            .put("app", appMetadata)
            .put("device", deviceMetadata)
            .put("summary", summary)
            .put("frames", frames)
            .put("events", traceEvents)
            .put("droppedEvents", traceDroppedEvents)
            .put(
                "notes",
                "All *Ns fields are nanoseconds. Fields ending in TimeNs or StageNs are monotonic timestamps; fields named as durations are elapsed times. actualPresentTimeDeviceNs is in the Vulkan display clock domain and must not be subtracted from System.nanoTime timestamps. nativeFlags bit 0=GPU timestamps available, bit 1=offscreen blur pass, bit 2=present call succeeded, bit 3=display-timing extension enabled."
            )
            .toString()
    }

    fun recordVisibilityChanged(isVisible: Boolean) {
        synchronized(lock) {
            wallpaperVisible = isVisible
            val session = activeSession ?: return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            session.recordVisibilityChanged(isVisible, nowNanos)
            appendTraceEventLocked(
                JSONObject()
                    .put("type", "visibility")
                    .put("timeNs", System.nanoTime())
                    .put("visible", isVisible)
            )
            publishIfDue(session, nowNanos)
        }
    }

    private fun publishIfDue(session: WallpaperDiagnosticsSession, nowNanos: Long) {
        if (nowNanos - lastPublishedAtNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishedAtNanos = nowNanos
        mutableState.value = WallpaperDiagnosticsState(
            isRecording = true,
            current = session.snapshot(nowNanos),
            history = mutableState.value.history,
            hasTrace = latestTraceJson != null
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
