package com.example.render

import kotlin.math.exp

/**
 * Allows at most one frame request to be in flight for a wallpaper engine.
 *
 * State changes arriving during a render are coalesced into one deferred redraw. Animation
 * advancement is paused while a frame is in flight, so the displayed offset cannot be overwritten
 * by a succession of newer requests.
 */
internal class RenderFrameGate {
    private var nextFrameId = 1L
    private var activeFrameId: Long? = null
    private var hasDeferredRedraw = false
    private var deferredFromOffsetUpdate = false

    val isInFlight: Boolean
        get() = activeFrameId != null

    fun beginFrame(fromOffsetUpdate: Boolean): Long? {
        if (activeFrameId != null) {
            hasDeferredRedraw = true
            deferredFromOffsetUpdate = deferredFromOffsetUpdate || fromOffsetUpdate
            return null
        }

        val frameId = nextFrameId++
        activeFrameId = frameId
        return frameId
    }

    fun isCurrent(frameId: Long): Boolean = activeFrameId == frameId

    fun completeFrame(frameId: Long): FrameCompletion? {
        if (!isCurrent(frameId)) return null
        activeFrameId = null

        val deferred = if (hasDeferredRedraw) {
            DeferredFrameRequest(deferredFromOffsetUpdate)
        } else {
            null
        }
        hasDeferredRedraw = false
        deferredFromOffsetUpdate = false
        return FrameCompletion(deferred)
    }

    fun cancel() {
        activeFrameId = null
        hasDeferredRedraw = false
        deferredFromOffsetUpdate = false
    }
}

internal data class FrameCompletion(
    val deferredRedraw: DeferredFrameRequest?
)

internal data class DeferredFrameRequest(
    val fromOffsetUpdate: Boolean
)

/**
 * Exponential smoothing with a fixed wall-clock time constant, independent of refresh rate.
 *
 * A 25 ms time constant preserves approximately the existing 60 Hz response while allowing
 * higher-refresh devices to use smaller per-frame steps without changing real-time response.
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
