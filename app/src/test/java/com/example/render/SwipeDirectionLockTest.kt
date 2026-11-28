package com.example.render

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDirectionLockTest {

    @Test
    fun fastRevealKeepsLayerOrderStableWhileReversingInsideTheSameSegment() {
        val lock = SwipeDirectionLock()

        assertEquals(
            1,
            lock.onOffsetChanged(
                previousOffset = 0.5f,
                nextOffset = 0.6f,
                isAtStaticPage = false,
                newTransitionUnlockImmediately = true
            )
        )
        assertEquals(1, lock.onOffsetChanged(0.6f, 0.55f, false))
        assertEquals(1, lock.onOffsetChanged(0.55f, 0.58f, false))
        assertEquals(1, lock.onOffsetChanged(0.58f, 0.53f, false))
        assertEquals(1, lock.onOffsetChanged(0.53f, 0.57f, false))
    }

    @Test
    fun fastRevealUnlocksAtPageBoundaryAndUsesTheNewDirectionImmediately() {
        val lock = SwipeDirectionLock()

        assertEquals(
            1,
            lock.onOffsetChanged(
                previousOffset = 0.5f,
                nextOffset = 0.6f,
                isAtStaticPage = false,
                newTransitionUnlockImmediately = true
            )
        )
        assertEquals(
            0,
            lock.onOffsetChanged(
                previousOffset = 0.6f,
                nextOffset = 0.5f,
                isAtStaticPage = true
            )
        )
        assertEquals(-1, lock.onOffsetChanged(0.5f, 0.49f, false))
    }

    @Test
    fun fastRevealCanRestartWhenOneOffsetUpdateCrossesTheBoundary() {
        val lock = SwipeDirectionLock()

        assertEquals(
            1,
            lock.onOffsetChanged(
                previousOffset = 0.4f,
                nextOffset = 0.45f,
                isAtStaticPage = false,
                newTransitionUnlockImmediately = true
            )
        )
        assertEquals(1, lock.onOffsetChanged(0.45f, 0.55f, false))
        assertEquals(
            -1,
            lock.onOffsetChanged(
                previousOffset = 0.55f,
                nextOffset = 0.45f,
                isAtStaticPage = false,
                crossedStaticPage = true,
                newTransitionUnlockImmediately = true
            )
        )
    }

    @Test
    fun slowSwitchingKeepsTheDirectionUntilTheBoundaryFrameSettles() {
        val lock = SwipeDirectionLock()

        assertEquals(1, lock.onOffsetChanged(0.5f, 0.6f, false))
        assertEquals(1, lock.onOffsetChanged(0.6f, 0.5f, true))
        assertEquals(1, lock.onOffsetChanged(0.5f, 0.49f, false))
        assertEquals(1, lock.onFrameSettled())
        assertEquals(-1, lock.onOffsetChanged(0.49f, 0.48f, false))
    }

    @Test
    fun unlockPolicyIsLatchedWhenEachTransitionStarts() {
        val lock = SwipeDirectionLock()

        assertEquals(1, lock.onOffsetChanged(0.5f, 0.6f, false))
        // A later callback must not change this transition from slow to fast.
        assertEquals(1, lock.onOffsetChanged(
            previousOffset = 0.6f,
            nextOffset = 0.55f,
            isAtStaticPage = false,
            newTransitionUnlockImmediately = true
        ))
        assertEquals(1, lock.onOffsetChanged(0.55f, 0.5f, true))
        assertEquals(1, lock.onOffsetChanged(0.5f, 0.49f, false))
        assertEquals(1, lock.onFrameSettled())
    }
}
