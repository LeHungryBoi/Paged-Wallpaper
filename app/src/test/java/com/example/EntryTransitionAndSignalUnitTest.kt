package com.example

import com.example.diagnostics.WallpaperSignalTracker
import com.example.render.EntryTransitionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryTransitionAndSignalUnitTest {

    @Test
    fun entryTransitionControllerEasesZoomFadeAndBlurSmoothly() {
        val controller = EntryTransitionController()
        assertFalse(controller.isActive)

        val idleState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 0.85f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 0.8f,
            blurEnabled = true,
            blurMaxMix = 0.7f
        )
        assertFalse(idleState.isActive)
        assertEquals(1f, idleState.scale, 0.0001f)
        assertEquals(0f, idleState.blackFadeAlpha, 0.0001f)
        assertEquals(0f, idleState.blurMix, 0.0001f)

        controller.trigger(durationMs = 400)
        assertTrue(controller.isActive)
        val startState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 0.85f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 0.8f,
            blurEnabled = true,
            blurMaxMix = 0.7f
        )
        assertTrue(startState.isActive)
        assertEquals(0.85f, startState.scale, 0.001f)
        assertEquals(0.8f, startState.blackFadeAlpha, 0.001f)
        assertEquals(0.7f, startState.blurMix, 0.001f)

        // Step halfway (200ms) -> scale increases monotonically, fade & blur decrease monotonically
        controller.step(200_000_000L)
        assertTrue(controller.isActive)
        val midState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 0.85f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 0.8f,
            blurEnabled = true,
            blurMaxMix = 0.7f
        )
        assertTrue(midState.scale > 0.90f && midState.scale < 1f)
        assertTrue(midState.blackFadeAlpha >= 0f && midState.blackFadeAlpha < 0.35f)
        assertTrue(midState.blurMix >= 0f && midState.blurMix < 0.35f)

        // Complete remaining 200ms
        controller.step(200_000_000L)
        assertFalse(controller.isActive)
        val endState = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 0.85f,
            blackFadeEnabled = true,
            blackFadeMaxAlpha = 0.8f,
            blurEnabled = true,
            blurMaxMix = 0.7f
        )
        assertFalse(endState.isActive)
        assertEquals(1f, endState.scale, 0.0001f)
        assertEquals(0f, endState.blackFadeAlpha, 0.0001f)
        assertEquals(0f, endState.blurMix, 0.0001f)
    }

    @Test
    fun entryTransitionSupportsIndividualElementTogglesAndReset() {
        val controller = EntryTransitionController()
        controller.trigger(durationMs = 500)
        controller.step(100_000_000L)

        val zoomOnly = controller.evaluate(
            enabled = true,
            zoomEnabled = true,
            zoomStartScale = 0.80f,
            blackFadeEnabled = false,
            blackFadeMaxAlpha = 0.9f,
            blurEnabled = false,
            blurMaxMix = 0.8f
        )
        assertTrue(zoomOnly.scale < 1f)
        assertEquals(0f, zoomOnly.blackFadeAlpha, 0.0001f)
        assertEquals(0f, zoomOnly.blurMix, 0.0001f)

        controller.reset()
        assertFalse(controller.isActive)
        assertEquals(1f, controller.linearProgress, 0.0001f)
    }

    @Test
    fun signalTrackerCoalescesZoomAndFormatsDiagnosticsOverlay() {
        val tracker = WallpaperSignalTracker(maxHistory = 6, zoomSupported = true)
        tracker.recordVisibility(visible = true, keyguardLocked = false, screenInteractive = true, timestampMillis = 1000L)
        assertTrue(tracker.recordZoom(0.9f, timestampMillis = 1050L))
        // Small incremental zoom steps should not spam the history log
        assertFalse(tracker.recordZoom(0.85f, timestampMillis = 1066L))
        assertFalse(tracker.recordZoom(0.75f, timestampMillis = 1082L))
        // Settling at 0.0f logs the completion
        assertTrue(tracker.recordZoom(0.0f, timestampMillis = 1200L))

        tracker.recordUserPresent(keyguardLocked = false, timestampMillis = 1300L)
        tracker.recordCommand("android.home", timestampMillis = 1400L)

        val lines = tracker.buildOverlayLines(entryProgress = 0.42f, entryActive = true)
        assertTrue(lines.any { it.contains("VIS:true") && it.contains("KG:false") && it.contains("SCR:true") })
        assertTrue(lines.any { it.contains("ZOOM:0.00") && it.contains("ENTRY:0.42") })
        assertTrue(lines.any { it.contains("CMD:android.home") })
        assertTrue(lines.any { it.contains("UNLOCK") })
    }
}
