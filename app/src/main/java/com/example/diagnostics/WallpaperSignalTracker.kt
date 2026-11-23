package com.example.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class WallpaperSignalTracker(
    private val maxHistory: Int = DEFAULT_MAX_HISTORY,
    private val zoomSupported: Boolean = true
) {
    data class SignalEvent(
        val timestampMillis: Long,
        val tag: String,
        val detail: String
    )

    private val events = ArrayDeque<SignalEvent>()

    var isVisible: Boolean? = null
        private set
    var isKeyguardLocked: Boolean? = null
        private set
    var isScreenInteractive: Boolean? = null
        private set
    var currentZoom: Float? = null
        private set
    var lastCommand: String? = null
        private set
    var lastOffsetSummary: String? = null
        private set

    private var lastLoggedZoom: Float? = null
    private var lastDisplayZoom: Float? = null
    private var zoomSessionActive: Boolean = false
    private var lastLoggedOffsetKind: String? = null

    fun recordVisibility(
        visible: Boolean,
        keyguardLocked: Boolean?,
        screenInteractive: Boolean?,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        isVisible = visible
        isKeyguardLocked = keyguardLocked
        isScreenInteractive = screenInteractive
        val flags = buildList {
            keyguardLocked?.let { add("kg=$it") }
            screenInteractive?.let { add("scr=$it") }
        }.joinToString(" ")
        val detail = if (flags.isNotEmpty()) "$visible ($flags)" else "$visible"
        pushEvent("VIS", detail, timestampMillis)
        if (!visible) {
            zoomSessionActive = false
        }
    }

    fun recordUserPresent(
        keyguardLocked: Boolean?,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        isKeyguardLocked = keyguardLocked
        pushEvent("UNLOCK", "USER_PRESENT kg=$keyguardLocked", timestampMillis)
    }

    fun recordScreenPower(
        interactive: Boolean,
        keyguardLocked: Boolean?,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        isScreenInteractive = interactive
        isKeyguardLocked = keyguardLocked
        val label = if (interactive) "SCREEN_ON" else "SCREEN_OFF"
        pushEvent(label, "kg=$keyguardLocked", timestampMillis)
    }

    fun recordZoom(
        zoom: Float,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val prev = currentZoom
        currentZoom = zoom
        val started = !zoomSessionActive && zoom > ZOOM_ACTIVE_EPSILON
        val settled = zoomSessionActive && zoom <= ZOOM_ACTIVE_EPSILON
        val largeJump = prev != null && abs(zoom - prev) >= ZOOM_SIGNIFICANT_DELTA
        val shouldLog = started || settled || (prev == null) || largeJump
        if (started) zoomSessionActive = true
        if (settled) zoomSessionActive = false
        if (shouldLog) {
            val fromStr = lastLoggedZoom?.let { String.format(Locale.US, "%.2f->", it) } ?: ""
            val toStr = String.format(Locale.US, "%.2f", zoom)
            val stateLabel = when {
                settled -> "settled"
                started -> "start"
                else -> "step"
            }
            pushEvent("ZOOM", "$fromStr$toStr ($stateLabel)", timestampMillis)
            lastLoggedZoom = zoom
        }
        val displayChanged = lastDisplayZoom == null ||
            abs(zoom - (lastDisplayZoom ?: 0f)) >= ZOOM_DISPLAY_EPSILON ||
            shouldLog
        if (displayChanged) {
            lastDisplayZoom = zoom
        }
        return displayChanged
    }

    fun recordCommand(
        action: String?,
        extrasSummary: String? = null,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val safeAction = action ?: "null"
        lastCommand = if (extrasSummary.isNullOrBlank()) safeAction else "$safeAction [$extrasSummary]"
        pushEvent("CMD", lastCommand!!, timestampMillis)
    }

    fun recordSystemDialogClose(
        reason: String?,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        pushEvent("SYS_DLG", "reason=${reason ?: "none"}", timestampMillis)
    }

    fun recordTouchEvent(
        tag: String,
        detail: String,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        pushEvent(tag, detail, timestampMillis)
    }

    fun recordOffsetSignal(
        xOffset: Float,
        xOffsetStep: Float,
        isVisible: Boolean,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val isDummyZero = xOffset <= 0.001f && xOffsetStep <= 0f
        val kind = when {
            !isVisible && isDummyZero -> "hidden_dummy_0"
            isDummyZero -> "visible_dummy_0"
            xOffsetStep > 0f -> "launcher_step"
            else -> "raw_offset"
        }
        lastOffsetSummary = String.format(
            Locale.US,
            "x=%.2f step=%.2f (%s)",
            xOffset,
            xOffsetStep,
            kind
        )
        if (kind != lastLoggedOffsetKind) {
            lastLoggedOffsetKind = kind
            pushEvent("OFFSET", lastOffsetSummary ?: kind, timestampMillis)
            return true
        }
        return false
    }

    fun buildOverlayLines(entryProgress: Float, entryActive: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val visText = isVisible?.toString() ?: "?"
        val kgText = isKeyguardLocked?.toString() ?: "?"
        val scrText = isScreenInteractive?.toString() ?: "?"
        val zoomText = when {
            !zoomSupported -> "N/A"
            currentZoom != null -> String.format(Locale.US, "%.2f", currentZoom)
            else -> "--"
        }
        val entryText = if (entryActive) {
            String.format(Locale.US, "%.2f", entryProgress)
        } else {
            "IDLE"
        }
        lines += "VIS:$visText  KG:$kgText  SCR:$scrText"
        lines += "ZOOM:$zoomText  ENTRY:$entryText"
        lastCommand?.let { cmd ->
            lines += "CMD:$cmd"
        }
        if (events.isEmpty()) {
            lines += "Waiting for signals..."
        } else {
            val timeFormat = SimpleDateFormat("HH:mm:ss.S", Locale.US)
            events.forEach { event ->
                val time = timeFormat.format(Date(event.timestampMillis))
                lines += "$time ${event.tag} ${event.detail}".take(56)
            }
        }
        return lines
    }

    private fun pushEvent(tag: String, detail: String, timestampMillis: Long) {
        events.addFirst(SignalEvent(timestampMillis, tag, detail))
        while (events.size > maxHistory) {
            events.removeLast()
        }
    }

    companion object {
        private const val DEFAULT_MAX_HISTORY = 8
        private const val ZOOM_ACTIVE_EPSILON = 0.01f
        private const val ZOOM_DISPLAY_EPSILON = 0.05f
        private const val ZOOM_SIGNIFICANT_DELTA = 0.25f
    }
}
