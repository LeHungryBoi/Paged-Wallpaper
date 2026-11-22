package com.example

import android.view.MotionEvent
import com.example.data.model.CalibrationConfig
import com.example.render.EdgeOverScrollController
import com.example.render.EdgeOverScrollScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EdgeOverScrollUnitTest {

    @Test
    fun edgeOverScrollControllerEngagesAtBothEdgesYieldsInwardAndDecaysSmoothly() {
        val calib = CalibrationConfig(pageCount = 3, customOffsets = listOf(0f, 0.5f, 1f))
        val controller = EdgeOverScrollController()
        val width = 1000
        val slop = 24

        // Middle page (0.5f) never activates touch tracking
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 500f, 500f, width, slop, 0.5f, 0.5f, calib)
        assertFalse(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)

        // Left edge (0.0f): dragging rightward (outward toward -1) locks negative fraction
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib)
        assertTrue(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)

        val lockMove = controller.onTouchSignal(
            MotionEvent.ACTION_MOVE, 0, 300f + slop + 275f, 505f, width, slop, 0.7f, 0f, calib
        )
        assertTrue(lockMove.handled)
        assertTrue(lockMove.needsRedraw)
        assertTrue(controller.isTouchActive)
        assertTrue(controller.isOverScrollLocked)
        assertEquals(0f, controller.lockedEdgeOffset, 0.001f)
        assertEquals(-0.5f, controller.overScrollFraction, 0.01f)

        // Release without reversing triggers gentle slow ease-out snap-back and supports mid-decay re-grab
        controller.onTouchSignal(MotionEvent.ACTION_UP, 0, 300f + slop + 275f, 505f, width, slop, 0.7f, 0f, calib)
        assertFalse(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)
        assertEquals(EdgeOverScrollController.SnapBackMode.SLOW, controller.snapBackMode)
        controller.stepRelease(EdgeOverScrollController.SLOW_SNAP_BACK_DURATION_NANOS / 2L, 0.5f)
        val midReturnFraction = controller.overScrollFraction
        assertTrue(midReturnFraction < -0.05f && midReturnFraction > -0.45f)

        // Mid-decay re-grab continues seamlessly from current fraction
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 400f, 500f, width, slop, 0f, 0f, calib)
        assertTrue(controller.isTouchActive)
        assertTrue(controller.isOverScrollLocked)
        assertEquals(midReturnFraction, controller.overScrollFraction, 0.01f)
        controller.reset()

        // Right edge (1.0f): dragging leftward (outward) locks positive fraction
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 700f, 500f, width, slop, 1f, 1f, calib)
        val rightOutward = controller.onTouchSignal(
            MotionEvent.ACTION_MOVE, 0, 700f - slop - 275f, 500f, width, slop, 1f, 1f, calib
        )
        assertTrue(rightOutward.handled)
        assertTrue(controller.isOverScrollLocked)
        assertEquals(1f, controller.lockedEdgeOffset, 0.001f)
        assertEquals(0.5f, controller.overScrollFraction, 0.01f)
    }

    @Test
    fun intensityCombinesDistanceAndVelocityWithRaisedFloorAndCappedTopSpeed() {
        val calib = CalibrationConfig(pageCount = 3, customOffsets = listOf(0f, 0.5f, 1f))
        val width = 1000
        val slop = 24

        val slowController = EdgeOverScrollController()
        slowController.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 200f, 500f, width, slop, 0f, 0f, calib, 0L)
        slowController.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 200f + 120f, 500f, width, slop, 0f, 0f, calib, 600L)
        val slowIntensity = abs(slowController.overScrollFraction)

        val fastController = EdgeOverScrollController()
        fastController.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 200f, 500f, width, slop, 0f, 0f, calib, 0L)
        fastController.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 200f + 120f, 500f, width, slop, 0f, 0f, calib, 40L)
        val fastIntensity = abs(fastController.overScrollFraction)

        assertTrue(
            "Fast flick ($fastIntensity) should exceed slow drag ($slowIntensity)",
            fastIntensity > slowIntensity + 0.08f
        )
        assertTrue("Slow drag minimum intensity ($slowIntensity) should remain responsive", slowIntensity > 0.18f)
        assertTrue("Fast flick intensity ($fastIntensity) should be softly capped", fastIntensity < 0.34f)
    }

    @Test
    fun reversalsControlSnapBackModeAndOverlapRecoveryWithPageMotion() {
        val calib = CalibrationConfig(pageCount = 3, customOffsets = listOf(0f, 0.5f, 1f))
        val controller = EdgeOverScrollController()
        val width = 1000
        val slop = 24

        // Case 1: Partial reversal stays on Page 1 with 350ms slow ease-out return
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 450f, 500f, width, slop, 0f, 0f, calib, 100L)
        val peakFraction = controller.overScrollFraction
        assertTrue(peakFraction < -0.15f)

        val partialReversal = controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 350f, 500f, width, slop, 0f, 0f, calib, 220L)
        assertTrue(partialReversal.handled)
        assertTrue(controller.overScrollFraction < 0f)
        assertTrue(controller.overScrollFraction > peakFraction)

        controller.onTouchSignal(MotionEvent.ACTION_UP, 0, 350f, 500f, width, slop, 0f, 0f, calib, 240L)
        assertEquals(EdgeOverScrollController.SnapBackMode.SLOW, controller.snapBackMode)
        controller.stepRelease(EdgeOverScrollController.SLOW_SNAP_BACK_DURATION_NANOS, 0.5f)
        assertEquals(0f, controller.overScrollFraction, 0.001f)

        // Case 2: Full reversal retains live fraction for seamless overlap with incoming page motion
        controller.reset()
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 450f, 500f, width, slop, 0f, 0f, calib, 100L)
        val fractionBeforeReversal = controller.overScrollFraction

        val fullReversal = controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 270f, 500f, width, slop, 0.1f, 0f, calib, 200L)
        assertFalse(fullReversal.handled)
        assertTrue(fullReversal.needsRedraw)
        assertFalse(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)
        assertTrue(controller.hasActiveFraction())
        assertEquals(fractionBeforeReversal, controller.overScrollFraction, 0.001f)
        assertEquals(EdgeOverScrollController.SnapBackMode.FAST, controller.snapBackMode)

        controller.stepRelease(16_666_667L, 0.5f)
        assertTrue(controller.overScrollFraction > fractionBeforeReversal && controller.overScrollFraction < 0f)
        repeat(24) {
            controller.stepRelease(16_666_667L, 0.5f)
        }
        assertEquals(0f, controller.overScrollFraction, 0.001f)

        // Case 3: Outward-only swipe with minor thumb twitch before lift stays SLOW (no reversal)
        controller.reset()
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 500f, 500f, width, slop, 0f, 0f, calib, 120L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 488f, 500f, width, slop, 0f, 0f, calib, 122L)
        controller.onTouchSignal(MotionEvent.ACTION_UP, 0, 488f, 500f, width, slop, 0f, 0f, calib, 124L)
        assertEquals(EdgeOverScrollController.SnapBackMode.SLOW, controller.snapBackMode)
        assertTrue(controller.overScrollFraction < -0.15f)

        // Case 4: Post-release FAST promotion uses time-capped exponential smoothing
        controller.reset()
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 300f + slop + 275f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_UP, 0, 300f + slop + 275f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.promoteToFastSnapBack()
        assertEquals(EdgeOverScrollController.SnapBackMode.FAST, controller.snapBackMode)
        controller.stepRelease(16_666_667L, 0.5f)
        assertTrue(controller.overScrollFraction < -0.28f && controller.overScrollFraction > -0.40f)
    }

    @Test
    fun diminishingParallaxScalingIsScreenAnchoredAndCapped() {
        val screenWidth = 1000
        val smallReachPx = 150
        val standardReachPx = 500
        val ultraWideReachPx = 1250

        val dimUltraHalf = EdgeOverScrollScaling.computeTravelPx(0.5f, ultraWideReachPx, screenWidth)
        val dimUltraFull = EdgeOverScrollScaling.computeTravelPx(1.0f, ultraWideReachPx, screenWidth)
        val dimStdFull = EdgeOverScrollScaling.computeTravelPx(1.0f, standardReachPx, screenWidth)
        val dimSmallFull = EdgeOverScrollScaling.computeTravelPx(1.0f, smallReachPx, screenWidth)

        assertTrue(dimUltraFull <= 720.5f && dimUltraFull < ultraWideReachPx)
        assertTrue(dimUltraHalf > dimUltraFull * 0.55f)
        assertEquals(standardReachPx.toFloat(), dimStdFull, 0.5f)
        assertEquals(smallReachPx.toFloat(), dimSmallFull, 0.5f)

        assertEquals(
            ultraWideReachPx - 720f,
            EdgeOverScrollScaling.computeSourceLeftPx(-1f, ultraWideReachPx, ultraWideReachPx, screenWidth),
            0.5f
        )
        assertEquals(
            (smallReachPx * 2).toFloat(),
            EdgeOverScrollScaling.computeSourceLeftPx(1f, smallReachPx, smallReachPx, screenWidth),
            0.5f
        )
    }
}
