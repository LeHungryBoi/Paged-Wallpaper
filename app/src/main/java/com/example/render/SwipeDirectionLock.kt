package com.example.render

import kotlin.math.abs

class SwipeDirectionLock {
    private var direction = 0
    private var locked = false
    private var unlockAfterSettledFrame = false

    fun onOffsetChanged(
        previousOffset: Float,
        nextOffset: Float,
        isAtStaticPage: Boolean
    ): Int {
        val delta = nextOffset - previousOffset
        if (!locked && abs(delta) > OFFSET_EPSILON) {
            direction = if (delta > 0f) 1 else -1
            locked = true
        }
        unlockAfterSettledFrame = locked && isAtStaticPage
        return direction
    }

    fun onFrameSettled(): Int {
        if (unlockAfterSettledFrame) {
            locked = false
            unlockAfterSettledFrame = false
        }
        return direction
    }

    fun reset(): Int {
        direction = 0
        locked = false
        unlockAfterSettledFrame = false
        return direction
    }

    private companion object {
        const val OFFSET_EPSILON = 0.00005f
    }
}
