package com.example.render

class DarkenTransitionController {

    var currentMix: Float = 0f
        private set

    var targetMix: Float = 0f
        private set

    var isActive: Boolean = false
        private set

    private var startMix: Float = 0f
    private var elapsedNanos: Long = 0L
    private var durationNanos: Long = DEFAULT_DURATION_MS * 1_000_000L

    fun snapTo(darkened: Boolean) {
        val target = if (darkened) 1f else 0f
        currentMix = target
        targetMix = target
        startMix = target
        elapsedNanos = 0L
        isActive = false
    }

    fun transitionTo(
        darkened: Boolean,
        durationMs: Int = DEFAULT_DURATION_MS
    ) {
        val newTarget = if (darkened) 1f else 0f
        val distance = kotlin.math.abs(newTarget - currentMix)
        if (distance <= MIX_EPSILON) {
            snapTo(darkened)
            return
        }
        if (isActive && kotlin.math.abs(targetMix - newTarget) <= MIX_EPSILON) {
            return
        }
        startMix = currentMix
        targetMix = newTarget
        elapsedNanos = 0L
        val safeDurationMs = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        durationNanos = (safeDurationMs * distance).toLong().coerceAtLeast(1L) * 1_000_000L
        isActive = true
    }

    fun step(deltaNanos: Long) {
        if (!isActive) return
        val dt = deltaNanos.coerceAtLeast(1L)
        elapsedNanos += dt
        val linear = (elapsedNanos.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
        val eased = if (linear < 0.5f) {
            4f * linear * linear * linear
        } else {
            val t = -2f * linear + 2f
            1f - (t * t * t) / 2f
        }
        currentMix = (startMix + (targetMix - startMix) * eased).coerceIn(0f, 1f)
        if (linear >= 1f) {
            currentMix = targetMix
            startMix = targetMix
            elapsedNanos = 0L
            isActive = false
        }
    }

    fun reset() {
        snapTo(false)
    }

    companion object {
        const val DEFAULT_DURATION_MS = 550
        const val MIN_DURATION_MS = 150
        const val MAX_DURATION_MS = 1500
        private const val MIX_EPSILON = 0.001f
    }
}
