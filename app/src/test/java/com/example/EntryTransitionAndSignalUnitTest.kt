package com.example

import com.example.diagnostics.WallpaperSignalTracker
import com.example.render.EntryTransitionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryTransitionAndSignalUnitTest {

    @Test
    fun entryTransitionControllerEasesZoomOutFadeAndBlurSmoothly() {
        val controller = EntryTransitionController()
        assertFalse(controller.isActive)

        val idleState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 1.35f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 1.0f,
            blurEnabled = true,
            blurMaxMix = 1.0f
        )
        assertFalse(idleState.isActive)
        assertEquals(1f, idleState.scale, 0.0001f)
        assertEquals(0f, idleState.blackFadeAlpha, 0.0001f)
        assertEquals(0f, idleState.blurMix, 0.0001f)

        controller.trigger(durationMs = 390)
        assertTrue(controller.isActive)
        assertFalse(controller.isInterrupted)
        val startState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 1.35f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 1.0f,
            blurEnabled = true,
            blurMaxMix = 1.0f
        )
        assertTrue(startState.isActive)
        assertEquals(1.35f, startState.scale, 0.001f)
        assertEquals(1.0f, startState.blackFadeAlpha, 0.001f)
        assertEquals(1.0f, startState.blurMix, 0.001f)

        // Step halfway (195ms) -> scale decreases monotonically toward 1f, fade & blur decrease monotonically
        controller.step(195_000_000L)
        assertTrue(controller.isActive)
        val midState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 1.35f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 1.0f,
            blurEnabled = true,
            blurMaxMix = 1.0f
        )
        assertTrue(midState.scale > 1f && midState.scale < 1.15f)
        assertTrue(midState.blackFadeAlpha >= 0f && midState.blackFadeAlpha < 0.35f)
        assertTrue(midState.blurMix >= 0f && midState.blurMix < 0.35f)

        // Complete remaining 195ms
        controller.step(195_000_000L)
        assertFalse(controller.isActive)
        val endState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 1.35f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 1.0f,
            blurEnabled = true,
            blurMaxMix = 1.0f
        )
        assertFalse(endState.isActive)
        assertEquals(1f, endState.scale, 0.0001f)
        assertEquals(0f, endState.blackFadeAlpha, 0.0001f)
        assertEquals(0f, endState.blurMix, 0.0001f)
    }

    @Test
    fun entryTransitionSupportsInterruptSmoothAccelerationTogglesAndReset() {
        val controller = EntryTransitionController()
        controller.trigger(durationMs = 500)
        controller.step(100_000_000L) // progress = 0.2f

        val beforeInterrupt = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 1.40f,
            blackFadeEnabled = false,
            blackFadeMaxAlpha = 0.9f,
            blurEnabled = false,
            blurMaxMix = 0.8f
        )
        assertTrue(beforeInterrupt.scale > 1f)
        assertEquals(0f, beforeInterrupt.blackFadeAlpha, 0.0001f)
        assertEquals(0f, beforeInterrupt.blurMix, 0.0001f)

        // Interrupt mid-animation: must NOT snap immediately, must mark interrupted & accelerate to completion within ~140ms
        controller.interrupt()
        assertTrue(controller.isActive)
        assertTrue(controller.isInterrupted)
        assertEquals(0.2f, controller.linearProgress, 0.001f)

        controller.step(50_000_000L)
        assertTrue(controller.isActive)
        val duringInterrupt = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 1.40f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 1.0f,
            blurEnabled = true,
            blurMaxMix = 1.0f
        )
        assertTrue(controller.linearProgress > 0.5f && controller.linearProgress < 1f)
        assertTrue(duringInterrupt.scale > 1f && duringInterrupt.scale < beforeInterrupt.scale)

        controller.step(100_000_000L)
        assertFalse(controller.isActive)
        assertFalse(controller.isInterrupted)
        assertEquals(1f, controller.linearProgress, 0.0001f)

        controller.reset()
        assertFalse(controller.isActive)
        assertEquals(1f, controller.linearProgress, 0.0001f)
    }

    @Test
    fun signalTrackerCoalescesZoomAndFormatsUntruncatedCommandDiagnostics() {
        val tracker = WallpaperSignalTracker(maxHistory = 8, zoomSupported = true)
        tracker.recordVisibility(visible = true, keyguardLocked = false, screenInteractive = true, timestampMillis = 1000L)
        assertTrue(tracker.recordZoom(0.9f, timestampMillis = 1050L))
        // Small delta (< 0.05) should not request display refresh; larger delta (>= 0.05) should refresh HUD display
        assertFalse(tracker.recordZoom(0.88f, timestampMillis = 1066L))
        assertTrue(tracker.recordZoom(0.75f, timestampMillis = 1082L))
        // Settling at 0.0f logs the completion
        assertTrue(tracker.recordZoom(0.0f, timestampMillis = 1200L))

        tracker.recordUserPresent(keyguardLocked = false, timestampMillis = 1300L)
        tracker.recordCommand("android.wallpaper.transform_exit", "x=540 y=1200 z=0", timestampMillis = 1400L)

        val lines = tracker.buildOverlayLines(entryProgress = 0.42f, entryActive = true)
        assertTrue(lines.any { it.contains("VIS:true") && it.contains("KG:false") && it.contains("SCR:true") })
        assertTrue(lines.any { it.contains("ZOOM:0.00") && it.contains("ENTRY:0.42") })
        assertTrue(lines.any { it.contains("CMD:android.wallpaper.transform_exit [x=540 y=1200 z=0]") })
        assertTrue(lines.any { it.contains("UNLOCK") })
    }
}
