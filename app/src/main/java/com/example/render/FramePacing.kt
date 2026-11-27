package com.example.render

import kotlin.math.exp

/**
 * Allows at most one frame request to be in flight for a wallpaper engine.
 *
 * State changes arriving during a render are coalesced into one deferred redraw. The animation
 * itself is not advanced while a frame is in flight; the next Choreographer callback is requested
 * only after the submitted frame completes.
 */
internal class RenderFrameGate {
    var isInFlight: Boolean = false
        private set

    private var hasDeferredRedraw = false
    private var deferredFromOffsetUpdate = false

    fun tryBeginFrame(fromOffsetUpdate: Boolean): Boolean {
        if (isInFlight) {
            hasDeferredRedraw = true
            deferredFromOffsetUpdate = deferredFromOffsetUpdate || fromOffsetUpdate
            return false
        }
        isInFlight = true
        return true
    }

    fun completeFrame(): DeferredFrameRequest? {
        if (!isInFlight) return null
        isInFlight = false
        if (!hasDeferredRedraw) return null

        val deferred = DeferredFrameRequest(deferredFromOffsetUpdate)
        hasDeferredRedraw = false
        deferredFromOffsetUpdate = false
        return deferred
    }

    fun cancel() {
        isInFlight = false
        hasDeferredRedraw = false
        deferredFromOffsetUpdate = false
    }
}

internal data class DeferredFrameRequest(
    val fromOffsetUpdate: Boolean
)

/**
 * Exponential smoothing with a fixed wall-clock time constant, independent of refresh rate.
 *
 * A 25 ms time constant preserves approximately the existing 60 Hz response while allowing
 * higher-refresh devices to use smaller per-frame steps without changing the real-time response.
 */
internal object FrameSmoothing {
    const val TIME_CONSTANT_NANOS = 25_000_000L

    fun fraction(elapsedNanos: Long): Float {
        val elapsed = elapsedNanos.coerceAtLeast(0L)
        if (elapsed == 0L) return 0f
        return (1.0 - exp(-elapsed.toDouble() / TIME_CONSTANT_NANOS.toDouble()))
            .toFloat()
            .coerceIn(0f, 1f)
    }
}
