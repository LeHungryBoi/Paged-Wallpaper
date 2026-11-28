package com.example.render

import kotlin.math.abs

class SwipeDirectionLock {
    private var direction = 0
    private var locked = false
    private var unlockAfterSettledFrame = false
    private var unlockImmediatelyAtBoundary = false

    fun previewDirection(previousOffset: Float, nextOffset: Float): Int {
        if (locked) return direction
        val delta = nextOffset - previousOffset
        return when {
            delta > OFFSET_EPSILON -> 1
            delta < -OFFSET_EPSILON -> -1
            else -> direction
        }
    }

    fun onOffsetChanged(
        previousOffset: Float,
        nextOffset: Float,
        isAtStaticPage: Boolean,
        crossedStaticPage: Boolean = false,
        newTransitionUnlockImmediately: Boolean = false
    ): Int {
        val delta = nextOffset - previousOffset
        val reachedPageBoundary = isAtStaticPage || crossedStaticPage

        if (locked && reachedPageBoundary) {
            if (unlockImmediatelyAtBoundary) {
                direction = 0
                locked = false
                unlockAfterSettledFrame = false
                unlockImmediatelyAtBoundary = false
            } else {
                // Keep the legacy slow-switching behavior until a submitted frame settles.
                // Once the page boundary has been reached, later offsets must not cancel it.
                unlockAfterSettledFrame = true
            }
        }

        if (!locked && !isAtStaticPage && abs(delta) > OFFSET_EPSILON) {
            direction = if (delta > 0f) 1 else -1
            locked = true
            unlockImmediatelyAtBoundary = newTransitionUnlockImmediately
        }
        return direction
    }

    fun onFrameSettled(): Int {
        if (unlockAfterSettledFrame) {
            locked = false
            unlockAfterSettledFrame = false
            unlockImmediatelyAtBoundary = false
        }
        return direction
    }

    fun reset(): Int {
        direction = 0
        locked = false
        unlockAfterSettledFrame = false
        unlockImmediatelyAtBoundary = false
        return direction
    }

    private companion object {
        const val OFFSET_EPSILON = 0.00005f
    }
}
