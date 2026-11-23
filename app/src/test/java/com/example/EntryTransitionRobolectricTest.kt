package com.example

import com.example.data.model.CalibrationConfig
import com.example.data.model.WallpaperAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EntryTransitionRobolectricTest {

    @Test
    fun appStateSerializesAndDefaultsEntryAnimationAndSignalOverlaySettings() {
        val state = WallpaperAppState(
            pages = emptyList(),
            calibration = CalibrationConfig(pageCount = 3, homePageIndex = 1),
            resumeFadeEnabled = true,
            entryZoomEnabled = false,
            entryZoomStartScale = 0.78f,
            entryBlackFadeEnabled = true,
            entryBlackFadeMaxAlpha = 0.9f,
            entryBlurEnabled = true,
            entryBlurStrength = 0.55f,
            entryDurationMs = 600,
            showSignalDebugOverlay = true
        )
        val restored = WallpaperAppState.fromJson(state.toJson())
        assertTrue(restored.resumeFadeEnabled)
        assertFalse(restored.entryZoomEnabled)
        assertEquals(0.78f, restored.entryZoomStartScale, 0.001f)
        assertTrue(restored.entryBlackFadeEnabled)
        assertEquals(0.9f, restored.entryBlackFadeMaxAlpha, 0.001f)
        assertTrue(restored.entryBlurEnabled)
        assertEquals(0.55f, restored.entryBlurStrength, 0.001f)
        assertEquals(600, restored.entryDurationMs)
        assertTrue(restored.showSignalDebugOverlay)

        val defaults = WallpaperAppState.fromJson(
            state.toJson().apply {
                remove("entryZoomEnabled")
                remove("entryZoomStartScale")
                remove("entryBlackFadeEnabled")
                remove("entryBlackFadeMaxAlpha")
                remove("entryBlurEnabled")
                remove("entryBlurStrength")
                remove("entryDurationMs")
                remove("showSignalDebugOverlay")
            }
        )
        assertTrue(defaults.entryZoomEnabled)
        assertEquals(
            WallpaperAppState.DEFAULT_ENTRY_ZOOM_START_SCALE,
            defaults.entryZoomStartScale,
            0.001f
        )
        assertTrue(defaults.entryBlackFadeEnabled)
        assertEquals(
            WallpaperAppState.DEFAULT_ENTRY_BLACK_FADE_MAX_ALPHA,
            defaults.entryBlackFadeMaxAlpha,
            0.001f
        )
        assertFalse(defaults.entryBlurEnabled)
        assertEquals(
            WallpaperAppState.DEFAULT_ENTRY_BLUR_STRENGTH,
            defaults.entryBlurStrength,
            0.001f
        )
        assertEquals(WallpaperAppState.DEFAULT_ENTRY_DURATION_MS, defaults.entryDurationMs)
        assertFalse(defaults.showSignalDebugOverlay)
    }
}
