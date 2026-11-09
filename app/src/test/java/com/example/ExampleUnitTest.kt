package com.example

import com.example.data.model.CalibrationConfig
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTrigger
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.WallpaperAppState
import com.example.transition.TransitionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun testCalibrationConfigSerialization() {
        val config = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 1,
            autoCalibrate = false,
            customOffsets = listOf(0.0f, 0.48f, 1.0f)
        )
        val json = config.toJson()
        val restored = CalibrationConfig.fromJson(json)

        assertEquals(3, restored.pageCount)
        assertEquals(1, restored.homePageIndex)
        assertEquals(3, restored.customOffsets.size)
        assertEquals(0.48f, restored.customOffsets[1], 0.001f)
    }

    @Test
    fun testCalibrationConfigGetHomeOffset() {
        // Page 2 as home in 3 page setup with custom anchors
        val configWithCustom = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 1,
            customOffsets = listOf(0.0f, 0.48f, 1.0f)
        )
        assertEquals(0.48f, configWithCustom.getHomeOffset(), 0.001f)

        // Page 1 as home in 3 page setup
        val configPage1Home = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 0,
            customOffsets = listOf(0.0f, 0.5f, 1.0f)
        )
        assertEquals(0.0f, configPage1Home.getHomeOffset(), 0.001f)

        // Page 3 as home in 3 page setup
        val configPage3Home = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 2,
            customOffsets = listOf(0.0f, 0.5f, 1.0f)
        )
        assertEquals(1.0f, configPage3Home.getHomeOffset(), 0.001f)

        // 4 pages uniform with Page 2 (index 1) as home
        val config4Pages = CalibrationConfig(
            pageCount = 4,
            homePageIndex = 1,
            customOffsets = listOf(0.0f, 0.333f, 0.667f, 1.0f)
        )
        assertEquals(0.333f, config4Pages.getHomeOffset(), 0.001f)
    }

    @Test
    fun testOriginalMediaParallaxSerialization() {
        val media = OriginalMedia(
            id = "media",
            fileName = "media.jpg",
            dateAdded = 1L,
            width = 1080,
            height = 2400,
            averageLuminance = 0.5f,
            isDarkDetected = false,
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 0.7f
        )

        assertEquals(0.7f, OriginalMedia.fromJson(media.toJson()).parallaxAmount, 0.001f)
        assertEquals(0f, OriginalMedia.fromJson(media.toJson().apply {
            remove("parallaxAmount")
        }).parallaxAmount, 0.001f)
    }

    @Test
    fun testTransitionRegistry() {
        val registry = TransitionRegistry
        assertEquals(7, registry.transitions.size)
        assertNotNull(registry.get("scroll"))
        assertNotNull(registry.get("parallax"))
        assertNotNull(registry.get("fade"))
        assertNotNull(registry.get("depth"))
        assertNotNull(registry.get("slide_over"))
        assertNotNull(registry.get("cube"))
        assertEquals("Classic Scroll", registry.get("scroll").displayName)
        assertEquals("Parallax Scroll", registry.get("parallax").displayName)
    }

    @Test
    fun testDarkenConfigSerialization() {
        val config = DarkenConfig(
            triggerMode = DarkenTrigger.EXTRA_DIM,
            autoDetectDark = true,
            luminanceThreshold = 0.35f,
            dimFactor = 0.45f
        )
        val json = config.toJson()
        val restored = DarkenConfig.fromJson(json)

        assertEquals(DarkenTrigger.EXTRA_DIM, restored.triggerMode)
        assertTrue(restored.autoDetectDark)
        assertEquals(0.45f, restored.dimFactor, 0.001f)
    }

    @Test
    fun testWallpaperAppStateSerialization() {
        val pages = listOf(
            PageItem(pageIndex = 0, customLabel = "Left"),
            PageItem(pageIndex = 1, customLabel = "Home"),
            PageItem(pageIndex = 2, customLabel = "Right")
        )
        val state = WallpaperAppState(
            pages = pages,
            calibration = CalibrationConfig(pageCount = 3, homePageIndex = 1),
            transitionType = "cube",
            resumeFadeEnabled = true
        )
        val json = state.toJson()
        val restored = WallpaperAppState.fromJson(json)

        assertEquals(3, restored.pages.size)
        assertEquals("Home", restored.pages[1].customLabel)
        assertEquals("cube", restored.transitionType)
        assertTrue(restored.resumeFadeEnabled)
    }

    @Test
    fun testWallpaperAppStateDefaultsResumeFadeOffForExistingConfigs() {
        val restored = WallpaperAppState.fromJson(
            WallpaperAppState(
                pages = emptyList(),
                calibration = CalibrationConfig()
            ).toJson().apply {
                remove("resumeFadeEnabled")
            }
        )

        assertFalse(restored.resumeFadeEnabled)
    }
}
