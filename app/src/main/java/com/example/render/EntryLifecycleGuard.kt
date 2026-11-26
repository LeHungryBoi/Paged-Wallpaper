package com.example.render

enum class EntryTriggerDecision {
    NONE,
    TRIGGER_NOW,
    DEFER_UNTIL_SURFACE_READY,
    RESET
}

/**
 * Coordinates entry transition triggers across visibility, surface readiness, screen power,
 * and keyguard unlock events so the wallpaper never stalls on a solid-black frame or flashes
 * black from late unlock broadcasts after the home screen is already interactive.
 */
class EntryLifecycleGuard {

    var pendingEntryOnSurfaceReady: Boolean = false
        private set

    var pendingEntryOnUnlock: Boolean = false
        private set

    var entryTriggeredForVisibilitySession: Boolean = false
        private set

    private var lastScreenOnNanos: Long = 0L

    fun onVisibilityChanged(
        visible: Boolean,
        wasVisible: Boolean,
        entryEnabled: Boolean,
        surfaceReady: Boolean,
        keyguardLocked: Boolean?,
        screenInteractive: Boolean?,
        nowNanos: Long
    ): EntryTriggerDecision {
        if (!visible) {
            pendingEntryOnSurfaceReady = false
            pendingEntryOnUnlock = false
            entryTriggeredForVisibilitySession = false
            return EntryTriggerDecision.RESET
        }

        if (wasVisible) {
            return EntryTriggerDecision.NONE
        }

        pendingEntryOnSurfaceReady = false
        pendingEntryOnUnlock = false
        entryTriggeredForVisibilitySession = false

        if (!entryEnabled) {
            return EntryTriggerDecision.RESET
        }

        val recentlyScreenedOn = lastScreenOnNanos > 0L &&
            (nowNanos - lastScreenOnNanos) in 0L..SCREEN_ON_RECENCY_NANOS
        val effectiveInteractive = if (screenInteractive == false && recentlyScreenedOn) {
            true
        } else {
            screenInteractive
        }

        if (effectiveInteractive == false || keyguardLocked == true) {
            pendingEntryOnUnlock = true
            return EntryTriggerDecision.RESET
        }

        return armOrTrigger(surfaceReady)
    }

    fun onSurfaceReady(
        isVisible: Boolean,
        entryEnabled: Boolean
    ): Boolean {
        if (!entryEnabled) {
            pendingEntryOnSurfaceReady = false
            return false
        }
        if (!isVisible || !pendingEntryOnSurfaceReady || entryTriggeredForVisibilitySession) {
            return false
        }
        pendingEntryOnSurfaceReady = false
        pendingEntryOnUnlock = false
        entryTriggeredForVisibilitySession = true
        return true
    }

    fun onScreenOn(
        isVisible: Boolean,
        entryEnabled: Boolean,
        surfaceReady: Boolean,
        keyguardLocked: Boolean?,
        nowNanos: Long
    ): EntryTriggerDecision {
        lastScreenOnNanos = nowNanos
        if (!isVisible || !entryEnabled || !pendingEntryOnUnlock || entryTriggeredForVisibilitySession) {
            return EntryTriggerDecision.NONE
        }
        if (keyguardLocked == false) {
            pendingEntryOnUnlock = false
            return armOrTrigger(surfaceReady)
        }
        return EntryTriggerDecision.NONE
    }

    fun onUserPresent(
        isVisible: Boolean,
        entryEnabled: Boolean,
        surfaceReady: Boolean,
        keyguardLocked: Boolean?,
        screenInteractive: Boolean?
    ): EntryTriggerDecision {
        if (!isVisible || !entryEnabled || !pendingEntryOnUnlock || entryTriggeredForVisibilitySession) {
            pendingEntryOnUnlock = false
            return EntryTriggerDecision.NONE
        }
        if (keyguardLocked == true || screenInteractive == false) {
            return EntryTriggerDecision.NONE
        }
        pendingEntryOnUnlock = false
        return armOrTrigger(surfaceReady)
    }

    fun onUnlockedHomeInteraction(
        isVisible: Boolean,
        keyguardLocked: Boolean?,
        screenInteractive: Boolean?
    ) {
        if (isVisible && keyguardLocked != true && screenInteractive != false) {
            pendingEntryOnUnlock = false
        }
    }

    fun onScreenOff() {
        lastScreenOnNanos = 0L
        pendingEntryOnUnlock = false
        pendingEntryOnSurfaceReady = false
        entryTriggeredForVisibilitySession = false
    }

    fun onEntryDisabled() {
        pendingEntryOnUnlock = false
        pendingEntryOnSurfaceReady = false
        entryTriggeredForVisibilitySession = false
    }

    fun reset() {
        lastScreenOnNanos = 0L
        pendingEntryOnUnlock = false
        pendingEntryOnSurfaceReady = false
        entryTriggeredForVisibilitySession = false
    }

    private fun armOrTrigger(surfaceReady: Boolean): EntryTriggerDecision {
        return if (surfaceReady) {
            pendingEntryOnSurfaceReady = false
            entryTriggeredForVisibilitySession = true
            EntryTriggerDecision.TRIGGER_NOW
        } else {
            pendingEntryOnSurfaceReady = true
            EntryTriggerDecision.DEFER_UNTIL_SURFACE_READY
        }
    }

    companion object {
        const val SCREEN_ON_RECENCY_NANOS = 300_000_000L
    }
}
