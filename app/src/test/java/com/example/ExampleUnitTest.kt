package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BlurRainbowStyle
import com.example.data.model.BlurStyle
import com.example.data.model.BlurTintMode
import com.example.data.model.CalibrationConfig
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTrigger
import com.example.data.model.OffsetEasingStyle
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.ParallaxMotionSettings
import com.example.data.model.RevealMotionRates
import com.example.data.model.WallpaperAppState
import com.example.data.model.WallpaperPreset
import com.example.data.migration.PresetDataMigration
import com.example.data.repository.WallpaperRepository
import com.example.render.EdgeOverScrollController
import com.example.render.EdgeOverScrollScaling
import com.example.render.HorizontalMotionBlur
import com.example.render.OffsetEasing
import com.example.render.SwipeDirectionLock
import com.example.util.ImageProcessingUtils
import org.json.JSONArray
import org.json.JSONObject
import com.example.transition.TransitionRegistry
import com.example.transition.ClassicParallaxScrollTransition
import com.example.transition.ParallaxScrollTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    fun testPageAppearanceSettingsSerialization() {
        val page = PageItem(
            pageIndex = 0,
            originalId = "media",
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 0.7f
        )
        val restored = PageItem.fromJson(page.toJson())
        assertEquals(CropFillMode.CROP, restored.cropFillMode)
        assertEquals(0.7f, restored.parallaxAmount, 0.001f)

        val legacyPage = PageItem.fromJson(org.json.JSONObject().put("pageIndex", 0))
        assertEquals(CropFillMode.BLUR, legacyPage.cropFillMode)
        assertEquals(0f, legacyPage.parallaxAmount, 0.001f)
    }

    @Test
    fun testOriginalMediaSerializationKeepsOnlySourceMetadata() {
        val media = OriginalMedia(
            id = "media",
            fileName = "media.jpg",
            dateAdded = 1L,
            width = 1080,
            height = 2400,
            averageLuminance = 0.5f,
            isDarkDetected = false
        )

        val legacyJson = media.toJson()
            .put("cropFillMode", "CROP")
            .put("parallaxAmount", 0.7)
        val restored = OriginalMedia.fromJson(legacyJson)
        assertEquals(media.id, restored.id)
        assertFalse(restored.toJson().has("cropFillMode"))
        assertFalse(restored.toJson().has("parallaxAmount"))
    }

    @Test
    fun testTransitionRegistry() {
        val registry = TransitionRegistry
        assertEquals(10, registry.transitions.size)
        assertNotNull(registry.get("scroll"))
        assertNotNull(registry.get("parallax"))
        assertNotNull(registry.get("parallax_classic"))
        assertNotNull(registry.get("swipe_reveal"))
        assertNotNull(registry.get("swipe_reveal_stacked"))
        assertNotNull(registry.get("fade"))
        assertNotNull(registry.get("depth"))
        assertNotNull(registry.get("slide_over"))
        assertNotNull(registry.get("cube"))
        assertEquals("Classic Scroll", registry.get("scroll").displayName)
        assertEquals("Parallax Scroll", registry.get("parallax").displayName)
        assertEquals("Classic Parallax", registry.get("parallax_classic").displayName)
        assertEquals("Swipe to Reveal", registry.get("swipe_reveal").displayName)
        assertEquals("Swipe to Reveal Stacked", registry.get("swipe_reveal_stacked").displayName)
    }

    @Test
    fun testSwipeRevealMotionSettingSerializationAndDirectionSelection() {
        val setting = PageTransitionSetting(
            universalTransitionId = "swipe_reveal",
            separateDirections = true,
            leftRevealMotionRates = RevealMotionRates(outgoingRate = 0.2f, incomingRate = 0.8f),
            rightRevealMotionRates = RevealMotionRates(outgoingRate = 0.65f, incomingRate = 0.35f)
        )
        val restored = PageTransitionSetting.fromJson(setting.toJson())

        assertEquals(0.2f, restored.revealMotionRates(forward = false).outgoingRate, 0.001f)
        assertEquals(0.8f, restored.revealMotionRates(forward = false).incomingRate, 0.001f)
        assertEquals(0.65f, restored.revealMotionRates(forward = true).outgoingRate, 0.001f)
        assertEquals(0.35f, restored.revealMotionRates(forward = true).incomingRate, 0.001f)

        val legacySetting = PageTransitionSetting.fromJson(
            JSONObject()
                .put("universalTransitionId", "swipe_reveal")
                .put("separateDirections", true)
                .put("leftRevealImageMotionPercent", 0.25)
                .put("rightRevealImageMotionPercent", 0.75)
        )
        assertEquals(0.25f, legacySetting.revealMotionRates(forward = false).outgoingRate, 0.001f)
        assertEquals(0.25f, legacySetting.revealMotionRates(forward = false).incomingRate, 0.001f)
        assertEquals(0.75f, legacySetting.revealMotionRates(forward = true).outgoingRate, 0.001f)
        assertEquals(0.75f, legacySetting.revealMotionRates(forward = true).incomingRate, 0.001f)
        assertEquals(
            0f,
            PageTransitionSetting.fromJson(JSONObject()).universalRevealMotionRates.outgoingRate,
            0.001f
        )
    }

    @Test
    fun testSwipeRevealImageMotionUsesFullParallaxReachIndependently() {
        val outgoing = Bitmap.createBitmap(150, 1, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(150) { Color.rgb(it, 0, 0) }, 0, 150, 0, 0, 150, 1)
        }
        val incoming = Bitmap.createBitmap(150, 1, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(150) { Color.rgb(0, it, 0) }, 0, 150, 0, 0, 150, 1)
        }
        val output = Bitmap.createBitmap(100, 1, Bitmap.Config.ARGB_8888)
        val transition = TransitionRegistry.get("swipe_reveal")

        // Forward swipe at p = 0.5:
        // outgoing pageA covers [0..50) on output (-50..50) and incoming pageB is revealed at [50..100).
        // pageA sourceLeft = 10 + 40 * 1.0 * 0.5 = 30 -> output[0] samples source[30 + 50] = 80
        // pageB sourceLeft = 40 * (1 - 0.5 * 0.5) = 30 -> output[75] samples source[30 + 75] = 105
        transition.drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = outgoing,
            pageBBitmap = incoming,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 10,
            pageARightTravelPx = 40,
            pageBLeftTravelPx = 40,
            pageBRightTravelPx = 10,
            pageSwipeForward = true,
            outgoingMotionRate = 1f,
            incomingMotionRate = 0.5f
        )

        assertEquals(80, Color.red(output.getPixel(0, 0)))
        assertEquals(105, Color.green(output.getPixel(75, 0)))

        // Backward swipe at p = 0.5 swaps roles cleanly (pageB is outgoing on top, pageA is incoming underneath):
        // pageA uses incomingRate (0.25): sourceLeft = 10 + 40 * 0.25 * 0.5 = 15 -> output[25] samples source[15 + 25] = 40
        // pageB uses outgoingRate (1.0): sourceLeft = 40 * (1 - 1.0 * 0.5) = 20 -> output[75] samples source[20 + 25] = 45
        transition.drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = outgoing,
            pageBBitmap = incoming,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 10,
            pageARightTravelPx = 40,
            pageBLeftTravelPx = 40,
            pageBRightTravelPx = 10,
            pageSwipeForward = false,
            outgoingMotionRate = 1f,
            incomingMotionRate = 0.25f
        )

        assertEquals(40, Color.red(output.getPixel(25, 0)))
        assertEquals(45, Color.green(output.getPixel(75, 0)))

        outgoing.recycle()
        incoming.recycle()
        output.recycle()
    }

    @Test
    fun testSwipeRevealKeepsTheOutgoingPageOnTopWhenSwipingBackward() {
        val incoming = Bitmap.createBitmap(100, 1, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(100) { Color.rgb(it, 0, 0) }, 0, 100, 0, 0, 100, 1)
        }
        val outgoing = Bitmap.createBitmap(100, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val output = Bitmap.createBitmap(100, 1, Bitmap.Config.ARGB_8888)

        TransitionRegistry.get("swipe_reveal").drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = incoming,
            pageBBitmap = outgoing,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 0,
            pageARightTravelPx = 0,
            pageBLeftTravelPx = 0,
            pageBRightTravelPx = 0,
            pageSwipeForward = false
        )

        assertEquals(25, Color.red(output.getPixel(25, 0)))
        assertEquals(Color.BLUE, output.getPixel(75, 0))

        TransitionRegistry.get("swipe_reveal_stacked").drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = incoming,
            pageBBitmap = outgoing,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 0,
            pageARightTravelPx = 0,
            pageBLeftTravelPx = 0,
            pageBRightTravelPx = 0,
            pageSwipeForward = false
        )

        assertEquals(75, Color.red(output.getPixel(25, 0)))

        incoming.recycle()
        outgoing.recycle()
        output.recycle()
    }

    @Test
    fun testSwipeDirectionStaysLockedUntilTheStaticPageHasSettled() {
        val directionLock = SwipeDirectionLock()

        assertEquals(1, directionLock.onOffsetChanged(0.5f, 0.6f, false))
        assertEquals(1, directionLock.onOffsetChanged(0.6f, 0.55f, false))
        assertEquals(1, directionLock.onOffsetChanged(0.55f, 0.5f, true))
        assertEquals(1, directionLock.onOffsetChanged(0.5f, 0.49f, false))
        assertEquals(1, directionLock.onOffsetChanged(0.49f, 0.5f, true))
        assertEquals(1, directionLock.onFrameSettled())
        assertEquals(-1, directionLock.onOffsetChanged(0.5f, 0.49f, false))
    }

    @Test
    fun testPreviousSwipeRevealSelectionMigratesToStackedVariant() {
        val pages = listOf(PageItem(pageIndex = 0), PageItem(pageIndex = 1))
        val previousSetting = PageTransitionSetting(
            universalTransitionId = "swipe_reveal",
            leftTransitionId = "swipe_reveal",
            rightTransitionId = "swipe_reveal",
            separateDirections = true
        )
        val previousPreset = WallpaperPreset(
            id = "preset",
            name = "Preset",
            pages = pages,
            transitions = listOf(previousSetting)
        )
        val previousState = WallpaperAppState(
            pages = pages,
            calibration = CalibrationConfig(pageCount = 2),
            transitionType = "swipe_reveal",
            transitions = listOf(previousSetting),
            presets = listOf(previousPreset),
            activePresetId = previousPreset.id,
            schemaVersion = 4
        )

        val migrated = PresetDataMigration.migrateTransitionStorage(previousState)

        assertEquals(5, migrated.schemaVersion)
        assertEquals("swipe_reveal_stacked", migrated.transitionType)
        assertEquals(
            "swipe_reveal_stacked",
            migrated.transitions.single().transitionId(forward = true)
        )
        assertEquals(
            "swipe_reveal_stacked",
            migrated.presets.single().transitions.single().universalTransitionId
        )
    }

    @Test
    fun testParallaxTransitionUsesOutgoingAndIncomingPageReach() {
        val source = Bitmap.createBitmap(140, 1, Bitmap.Config.ARGB_8888)
        val columns = IntArray(140) { Color.rgb(it, 0, 0) }
        source.setPixels(columns, 0, 140, 0, 0, 140, 1)
        val output = Bitmap.createBitmap(100, 1, Bitmap.Config.ARGB_8888)
        val renderer = ParallaxScrollTransition()

        renderer.drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = source,
            pageBBitmap = null,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 10,
            pageARightTravelPx = 30,
            pageBLeftTravelPx = 0,
            pageBRightTravelPx = 0
        )
        assertEquals(75, Color.red(output.getPixel(0, 0)))

        renderer.drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = null,
            pageBBitmap = source,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 0,
            pageARightTravelPx = 0,
            pageBLeftTravelPx = 10,
            pageBRightTravelPx = 30
        )
        assertEquals(5, Color.red(output.getPixel(50, 0)))

        ClassicParallaxScrollTransition().drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = null,
            pageBBitmap = source,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageBLeftTravelPx = 10,
            pageBRightTravelPx = 30,
            pageALeftTravelPx = 0,
            pageARightTravelPx = 0
        )
        assertEquals(25, Color.red(output.getPixel(50, 0)))

        val slowSource = Bitmap.createBitmap(240, 1, Bitmap.Config.ARGB_8888)
        val slowColumns = IntArray(240) { Color.rgb(it, 0, 0) }
        slowSource.setPixels(slowColumns, 0, 240, 0, 0, 240, 1)

        // Slow image motion: 50% outgoing motion rate pans 50% of pageA's leftTravelPx (70 -> 52)
        // at p = 0.5, so screen pixel 0 (frame offset -50 -> bitmap +50) reads column 52 + 50 = 102.
        renderer.drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = slowSource,
            pageBBitmap = null,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 70,
            pageARightTravelPx = 70,
            pageBLeftTravelPx = 0,
            pageBRightTravelPx = 0,
            slowImageMotionEnabled = true,
            outgoingMotionRate = 0.5f,
            incomingMotionRate = 1f
        )
        assertEquals(102, Color.red(output.getPixel(0, 0)))

        // Slow image motion: 100% incoming motion rate pans 100% of pageB's rightTravelPx (70 + 70 * 0.5 = 105)
        // at p = 0.5, so screen pixel 50 (frame offset +50 -> bitmap +0) reads column 105.
        renderer.drawWithParallax(
            canvas = Canvas(output),
            pageABitmap = null,
            pageBBitmap = slowSource,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 0,
            pageARightTravelPx = 0,
            pageBLeftTravelPx = 70,
            pageBRightTravelPx = 70,
            slowImageMotionEnabled = true,
            outgoingMotionRate = 0.25f,
            incomingMotionRate = 1f
        )
        assertEquals(105, Color.red(output.getPixel(50, 0)))
        source.recycle()
        slowSource.recycle()
        output.recycle()
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
            resumeFadeEnabled = true,
            showOffsetDebugOverlay = true,
            showFrameDebugIndicator = true,
            motionBlurEnabled = false,
            motionBlurStrengthPercent = 150,
            offsetEasingEnabled = true,
            offsetEasingStyle = OffsetEasingStyle.FAST_START_SLOW_END,
            offsetEasingLinearZonePercent = 65,
            offsetEasingStrength = 75,
            animationFrameRate = 144
        )
        val json = state.toJson()
        val restored = WallpaperAppState.fromJson(json)

        assertEquals(3, restored.pages.size)
        assertEquals("Home", restored.pages[1].customLabel)
        assertEquals("cube", restored.transitionType)
        assertTrue(restored.resumeFadeEnabled)
        assertTrue(restored.showOffsetDebugOverlay)
        assertTrue(restored.showFrameDebugIndicator)
        assertFalse(restored.motionBlurEnabled)
        assertEquals(150, restored.motionBlurStrengthPercent)
        assertTrue(restored.offsetEasingEnabled)
        assertEquals(OffsetEasingStyle.FAST_START_SLOW_END, restored.offsetEasingStyle)
        assertEquals(65, restored.offsetEasingLinearZonePercent)
        assertEquals(75, restored.offsetEasingStrength)
        assertEquals(144, restored.animationFrameRate)
    }

    @Test
    fun testWallpaperAppStateDefaultsForMissingSettings() {
        val restored = WallpaperAppState.fromJson(
            WallpaperAppState(
                pages = emptyList(),
                calibration = CalibrationConfig()
            ).toJson().apply {
                remove("resumeFadeEnabled")
                remove("showFrameDebugIndicator")
                remove("motionBlurEnabled")
                remove("motionBlurStrengthPercent")
                remove("offsetEasingEnabled")
                remove("offsetEasingStyle")
                remove("offsetEasingLinearZonePercent")
                remove("offsetEasingStrength")
                remove("animationFrameRate")
            }
        )

        assertTrue(restored.resumeFadeEnabled)
        assertFalse(restored.showOffsetDebugOverlay)
        assertFalse(restored.showFrameDebugIndicator)
        assertTrue(restored.motionBlurEnabled)
        assertEquals(
            WallpaperAppState.DEFAULT_MOTION_BLUR_STRENGTH_PERCENT,
            restored.motionBlurStrengthPercent
        )
        assertFalse(restored.offsetEasingEnabled)
        assertEquals(OffsetEasingStyle.SYMMETRIC, restored.offsetEasingStyle)
        assertEquals(60, restored.offsetEasingLinearZonePercent)
        assertEquals(50, restored.offsetEasingStrength)
        assertEquals(WallpaperAppState.DEFAULT_ANIMATION_FRAME_RATE, restored.animationFrameRate)

        val invalidFrameRate = WallpaperAppState.fromJson(
            restored.toJson().put("animationFrameRate", 75)
        )
        assertEquals(WallpaperAppState.DEFAULT_ANIMATION_FRAME_RATE, invalidFrameRate.animationFrameRate)

        val clampedEasing = WallpaperAppState.fromJson(
            restored.toJson()
                .put("motionBlurStrengthPercent", 250)
                .put("offsetEasingLinearZonePercent", -1)
                .put("offsetEasingStrength", 101)
        )
        assertEquals(
            WallpaperAppState.MAX_MOTION_BLUR_STRENGTH_PERCENT,
            clampedEasing.motionBlurStrengthPercent
        )
        assertEquals(0, clampedEasing.offsetEasingLinearZonePercent)
        assertEquals(100, clampedEasing.offsetEasingStrength)
    }

    @Test
    fun testHorizontalMotionBlurSmearsPixelsOnOnlyOneSide() {
        val bitmap = Bitmap.createBitmap(5, 1, Bitmap.Config.ARGB_8888)
        val source = intArrayOf(
            Color.BLACK,
            Color.BLACK,
            Color.WHITE,
            Color.BLACK,
            Color.BLACK
        )
        bitmap.setPixels(source, 0, 5, 0, 0, 5, 1)

        HorizontalMotionBlur().apply(bitmap, radius = 1, smearDirection = 1)
        val movingRight = IntArray(5)
        bitmap.getPixels(movingRight, 0, 5, 0, 0, 5, 1)

        assertEquals(127, Color.red(movingRight[2]))
        assertEquals(127, Color.red(movingRight[3]))
        assertEquals(0, Color.red(movingRight[1]))
        assertEquals(0, Color.red(movingRight[4]))

        bitmap.setPixels(source, 0, 5, 0, 0, 5, 1)
        HorizontalMotionBlur().apply(bitmap, radius = 1, smearDirection = -1)
        val movingLeft = IntArray(5)
        bitmap.getPixels(movingLeft, 0, 5, 0, 0, 5, 1)

        assertEquals(127, Color.red(movingLeft[1]))
        assertEquals(127, Color.red(movingLeft[2]))
        assertEquals(0, Color.red(movingLeft[0]))
        assertEquals(0, Color.red(movingLeft[3]))
        bitmap.recycle()
    }

    @Test
    fun testOffsetEasingKeepsCenterLinearAndSlowsSymmetricEdges() {
        val start = OffsetEasing.map(0.1f, linearZonePercent = 60, strengthPercent = 100)
        val end = OffsetEasing.map(0.9f, linearZonePercent = 60, strengthPercent = 100)

        assertTrue(start > 0f)
        assertTrue(start < 0.1f)
        assertEquals(0.5f, OffsetEasing.map(0.5f, 60, 100), 0.0001f)
        assertEquals(0.2f, OffsetEasing.map(0.2f, 60, 100), 0.0001f)
        assertEquals(0.8f, OffsetEasing.map(0.8f, 60, 100), 0.0001f)
        assertEquals(1f - start, end, 0.0001f)
        assertEquals(0f, OffsetEasing.map(0f, 60, 100), 0f)
        assertEquals(1f, OffsetEasing.map(1f, 60, 100), 0f)
        assertEquals(0f, OffsetEasing.map(-0.1f, 60, 100), 0f)
        assertEquals(1f, OffsetEasing.map(1.1f, 60, 100), 0f)

        var previous = 0f
        for (index in 0..100) {
            val mapped = OffsetEasing.map(index / 100f, 60, 100)
            assertTrue(mapped >= previous)
            previous = mapped
        }
    }

    @Test
    fun testOffsetEasingCanResolveToLinearMapping() {
        for (index in 0..10) {
            val progress = index / 10f
            assertEquals(progress, OffsetEasing.map(progress, 60, 0), 0.0001f)
            assertEquals(progress, OffsetEasing.map(progress, 100, 100), 0.0001f)
        }
    }

    @Test
    fun testFastStartSlowEndEasingKeepsStartLinearAndSlowsAtEnd() {
        val style = OffsetEasingStyle.FAST_START_SLOW_END
        assertEquals(0.4f, OffsetEasing.map(0.4f, 60, 100, style), 0.0001f)
        assertEquals(0.6f, OffsetEasing.map(0.6f, 60, 100, style), 0.0001f)
        assertTrue(OffsetEasing.map(0.8f, 60, 100, style) > 0.8f)
        assertTrue(OffsetEasing.map(0.95f, 60, 100, style) > 0.95f)
        assertEquals(1f, OffsetEasing.map(1f, 60, 100, style), 0f)

        var previous = 0f
        for (index in 0..100) {
            val mapped = OffsetEasing.map(index / 100f, 60, 100, style)
            assertTrue(mapped >= previous)
            previous = mapped
        }
    }

    @Test
    fun testLegacyWallpaperConfigIsMarkedForMigration() {
        val legacy = WallpaperAppState(
            pages = listOf(PageItem(pageIndex = 0, originalId = "media")),
            calibration = CalibrationConfig(pageCount = 2)
        ).toJson().apply {
            remove("schemaVersion")
            remove("presets")
        }

        val restored = WallpaperAppState.fromJson(legacy)
        assertEquals(1, restored.schemaVersion)
        assertEquals("media", restored.pages.single().originalId)
    }

    @Test
    fun testLegacyMigrationMovesMediaSettingsIntoMigratedPreset() {
        val legacyConfig = WallpaperAppState(
            pages = listOf(
                PageItem(pageIndex = 0, originalId = "media"),
                PageItem(pageIndex = 1)
            ),
            calibration = CalibrationConfig(pageCount = 2),
            transitionType = "parallax"
        ).toJson().apply {
            remove("schemaVersion")
            remove("presets")
        }
        val legacyState = WallpaperAppState.fromJson(legacyConfig)
        val legacyMedia = JSONObject()
            .put("id", "media")
            .put("cropFillMode", "CROP")
            .put("parallaxAmount", 0.65)
            .put("cropPanX", 0.25)
        val migrated = PresetDataMigration.migrateLegacy(
            legacyState,
            mapOf("media" to legacyMedia)
        )

        assertEquals(2, migrated.schemaVersion)
        assertEquals(CropFillMode.CROP, migrated.pages[0].cropFillMode)
        assertEquals(0.65f, migrated.pages[0].parallaxAmount, 0.001f)
        assertEquals(0.25f, migrated.pages[0].cropPanX, 0.001f)
        assertEquals(migrated.pages, migrated.presets.single().pages)
        assertEquals("parallax", migrated.transitions.first().universalTransitionId)
    }

    @Test
    fun testPresetStoresPageAppearanceAndBidirectionalEffects() {
        val preset = WallpaperPreset(
            id = "preset-a",
            name = "Portrait",
            pages = listOf(
                PageItem(pageIndex = 0, originalId = "media", parallaxAmount = 0.4f),
                PageItem(pageIndex = 1, originalId = "media", cropPanX = 0.3f)
            ),
            transitions = listOf(
                PageTransitionSetting(
                    universalTransitionId = "cube",
                    leftTransitionId = "fade",
                    rightTransitionId = "parallax",
                    separateDirections = true,
                    leftParallaxMotion = ParallaxMotionSettings(true, 0.3f, 0.45f),
                    rightParallaxMotion = ParallaxMotionSettings(true, 0.7f, 0.85f)
                )
            )
        )
        val restored = WallpaperPreset.fromJson(preset.toJson())

        assertEquals(0.4f, restored.pages[0].parallaxAmount, 0.001f)
        assertEquals(0.3f, restored.pages[1].cropPanX, 0.001f)
        assertEquals("parallax", restored.transitions[0].transitionId(forward = true))
        assertEquals("fade", restored.transitions[0].transitionId(forward = false))
        assertTrue(restored.transitions[0].parallaxMotion(forward = true).slowImageMotionEnabled)
        assertTrue(restored.transitions[0].parallaxMotion(forward = false).slowImageMotionEnabled)
        assertEquals(
            0.7f,
            restored.transitions[0].parallaxMotion(forward = true).outgoingMotionRate,
            0.001f
        )
        assertEquals(
            0.85f,
            restored.transitions[0].parallaxMotion(forward = true).incomingMotionRate,
            0.001f
        )
        assertEquals(
            0.3f,
            restored.transitions[0].parallaxMotion(forward = false).outgoingMotionRate,
            0.001f
        )
        assertEquals(
            0.45f,
            restored.transitions[0].parallaxMotion(forward = false).incomingMotionRate,
            0.001f
        )
        assertEquals("cube", restored.transitions[0].universalTransitionId)
        assertEquals("fade", restored.toJson().getJSONArray("transitions")
            .getJSONObject(0).getString("leftTransitionId"))
        val universalSetting = restored.transitions[0].copy(separateDirections = false)
        assertEquals("cube", universalSetting.transitionId(forward = true))
        assertEquals("cube", universalSetting.transitionId(forward = false))
    }

    @Test
    fun testLegacyPageSlowParallaxSettingsMoveToTransitionSettings() {
        val legacyPages = JSONArray()
            .put(JSONObject().put("pageIndex", 0))
            .put(
                JSONObject()
                    .put("pageIndex", 1)
                    .put("slowParallax", true)
                    .put("parallaxImageSpeed", 0.35)
            )
            .put(JSONObject().put("pageIndex", 2))
        val legacyTransitions = JSONArray()
            .put(JSONObject().put("universalTransitionId", "parallax"))
            .put(JSONObject().put("universalTransitionId", "parallax"))
        val legacyState = JSONObject()
            .put("schemaVersion", 3)
            .put("transitionType", "parallax")
            .put("calibration", CalibrationConfig(pageCount = 3).toJson())
            .put("pages", legacyPages)
            .put("transitions", legacyTransitions)
            .put(
                "presets",
                JSONArray().put(
                    JSONObject()
                        .put("id", "default")
                        .put("name", "Default")
                        .put("pages", legacyPages)
                        .put("transitions", legacyTransitions)
                )
            )
        val migrated = WallpaperAppState.fromJson(legacyState)

        assertTrue(migrated.transitions[0].universalParallaxMotion.slowImageMotionEnabled)
        assertEquals(
            0.65f,
            migrated.transitions[0].universalParallaxMotion.outgoingMotionRate,
            0.001f
        )
        assertEquals(
            0.65f,
            migrated.transitions[0].universalParallaxMotion.incomingMotionRate,
            0.001f
        )
        assertTrue(migrated.transitions[1].universalParallaxMotion.slowImageMotionEnabled)
        assertTrue(
            migrated.presets.single().transitions[0]
                .universalParallaxMotion.slowImageMotionEnabled
        )
        assertFalse(migrated.toJson().getJSONArray("pages").getJSONObject(1).has("slowParallax"))
    }

    @Test
    fun testLegacyTransitionSettingsMigrateToDistinctAnimationItems() {
        val legacy = PageTransitionSetting.fromJson(
            JSONObject()
                .put("forwardTransitionId", "parallax")
                .put("reverseTransitionId", "fade")
                .put("separateDirections", true)
        )
        val migrated = PresetDataMigration.migrateTransitionStorage(
            WallpaperAppState(
                pages = listOf(PageItem(pageIndex = 0), PageItem(pageIndex = 1)),
                calibration = CalibrationConfig(pageCount = 2),
                transitions = listOf(legacy),
                schemaVersion = 2
            )
        )

        assertEquals(5, migrated.schemaVersion)
        assertEquals("parallax", migrated.transitions.single().universalTransitionId)
        assertEquals("parallax", migrated.transitions.single().rightTransitionId)
        assertEquals("fade", migrated.transitions.single().leftTransitionId)
        assertEquals("fade", migrated.transitions.single().transitionId(forward = false))
    }

    @Test
    fun testBackupRestoreCopiesEachZipEntryAndReplacesOldFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)
        val restoredImage = "restored image data".toByteArray()
        val staleImage = File(context.filesDir, "cropped/stale.jpg")
        staleImage.parentFile?.mkdirs()
        staleImage.writeText("stale data")
        val state = WallpaperAppState(
            pages = listOf(PageItem(pageIndex = 0), PageItem(pageIndex = 1)),
            calibration = CalibrationConfig(pageCount = 2)
        )
        val zipBytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                val entries = listOf(
                    "config.json" to state.toJson().toString().toByteArray(),
                    "originals_meta.json" to "[]".toByteArray(),
                    "cropped/restored.jpg" to restoredImage
                )
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

        assertTrue(repository.importFromZip(ByteArrayInputStream(zipBytes)))
        assertEquals(2, repository.appState.value.pages.size)
        assertTrue(File(context.filesDir, "cropped/restored.jpg").readBytes().contentEquals(restoredImage))
        assertFalse(staleImage.exists())
    }

    @Test
    fun testBlurStyleAndTintSerializationAndLegacyGlassFallback() {
        val page = PageItem(
            pageIndex = 1,
            originalId = "media",
            cropFillMode = CropFillMode.GLASS,
            blurAmount = 0.8f,
            blurStyle = BlurStyle.MODERN_ACRYLIC,
            blurTintMode = BlurTintMode.RAINBOW,
            blurTintColor = 0xFF10B981.toInt(),
            blurTintIntensity = 0.65f,
            blurRainbowStyle = BlurRainbowStyle.RADIAL_IRIDESCENCE
        )
        val restored = PageItem.fromJson(page.toJson())
        assertEquals(BlurStyle.MODERN_ACRYLIC, restored.blurStyle)
        assertEquals(BlurTintMode.RAINBOW, restored.blurTintMode)
        assertEquals(0xFF10B981.toInt(), restored.blurTintColor)
        assertEquals(0.65f, restored.blurTintIntensity, 0.001f)
        assertEquals(BlurRainbowStyle.RADIAL_IRIDESCENCE, restored.blurRainbowStyle)

        val legacyGlass = PageItem.fromJson(
            JSONObject()
                .put("pageIndex", 0)
                .put("cropFillMode", "GLASS")
        )
        assertEquals(CropFillMode.GLASS, legacyGlass.cropFillMode)
        assertEquals(BlurStyle.WIN7_AERO, legacyGlass.blurStyle)
        assertEquals(BlurTintMode.NONE, legacyGlass.blurTintMode)
    }

    @Test
    fun testOpticalBackdropBlurProducesSmoothTransitionsAndSupportsAllStylesAndTints() {
        val w = 200
        val h = 200
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            if (x < 100) Color.WHITE else Color.BLACK
        }
        val blurCheck = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
        ImageProcessingUtils.fastBlur(blurCheck, 18)
        ImageProcessingUtils.fastBlur(blurCheck, 12)

        val leftLum = Color.red(blurCheck.getPixel(80, 100))
        val centerLum = Color.red(blurCheck.getPixel(100, 100))
        val rightLum = Color.red(blurCheck.getPixel(120, 100))
        assertTrue(leftLum > centerLum)
        assertTrue(centerLum > rightLum)
        assertTrue(centerLum in 40..215)

        // Verify all 6 BlurStyles, Solid tint, and all 4 Rainbow styles render cleanly at target dimensions
        BlurStyle.values().forEach { style ->
            val tinted = ImageProcessingUtils.renderBackdropPreviewBitmap(
                srcBmp = blurCheck,
                targetWidth = 120,
                targetHeight = 160,
                blurAmount = 0.65f,
                blurStyle = style,
                blurTintMode = BlurTintMode.SOLID,
                blurTintColor = 0xFF38BDF8.toInt(),
                blurTintIntensity = 0.55f,
                blurRainbowStyle = BlurRainbowStyle.DIAGONAL_PRISM
            )
            assertEquals(120, tinted.width)
            assertEquals(160, tinted.height)
            tinted.recycle()
        }

        BlurRainbowStyle.values().forEach { rainbowStyle ->
            val rainbow = ImageProcessingUtils.renderBackdropPreviewBitmap(
                srcBmp = blurCheck,
                targetWidth = 120,
                targetHeight = 160,
                blurAmount = 0.7f,
                blurStyle = BlurStyle.WIN7_AERO,
                blurTintMode = BlurTintMode.RAINBOW,
                blurTintColor = Color.WHITE,
                blurTintIntensity = 0.8f,
                blurRainbowStyle = rainbowStyle
            )
            assertEquals(120, rainbow.width)
            assertEquals(160, rainbow.height)
            rainbow.recycle()
        }

        val smoothCopy = ImageProcessingUtils.createSmoothBlurredCopy(blurCheck, maxDimension = 100, blurRadius = 10)
        assertEquals(100, smoothCopy.width)
        assertEquals(100, smoothCopy.height)
        smoothCopy.recycle()
        blurCheck.recycle()
    }

    @Test
    fun testEdgeOverScrollControllerEngagesAtBothEdgesYieldsInwardAndDecaysSmoothly() {
        val calib = CalibrationConfig(pageCount = 3, customOffsets = listOf(0f, 0.5f, 1f))
        val controller = EdgeOverScrollController()
        val width = 1000
        val slop = 24

        // 1. Middle page (0.5f) never activates touch tracking
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 500f, 500f, width, slop, 0.5f, 0.5f, calib)
        assertFalse(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)

        // 2. Left edge (0.0f): dragging rightward (outward toward -1) locks negative fraction
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

        // Mid-decay re-grab continues seamlessly from current fraction without snapping
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 400f, 500f, width, slop, 0f, 0f, calib)
        assertTrue(controller.isTouchActive)
        assertTrue(controller.isOverScrollLocked)
        assertEquals(midReturnFraction, controller.overScrollFraction, 0.01f)
        controller.reset()

        // 3. Left edge inward swipe (leftward) yields immediately to launcher offset scrolling
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 500f, 500f, width, slop, 0f, 0f, calib)
        val inward = controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 500f - slop - 10f, 500f, width, slop, 0f, 0f, calib)
        assertFalse(inward.handled)
        assertFalse(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)
        assertEquals(0f, controller.overScrollFraction, 0.001f)

        // 4. Vertical swipe on edge yields cleanly
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 500f, 500f, width, slop, 0f, 0f, calib)
        val vertical = controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 505f, 500f + slop + 40f, width, slop, 0f, 0f, calib)
        assertFalse(vertical.handled)
        assertFalse(controller.isTouchActive)

        // 5. Right edge (1.0f): dragging leftward (outward) locks positive fraction
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
    fun testEdgeOverScrollIntensityCombinesDistanceAndVelocity() {
        val calib = CalibrationConfig(pageCount = 3, customOffsets = listOf(0f, 0.5f, 1f))
        val width = 1000
        val slop = 24

        // Slow crawl of 120px over 600ms
        val slowController = EdgeOverScrollController()
        slowController.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 200f, 500f, width, slop, 0f, 0f, calib, 0L)
        slowController.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 200f + 120f, 500f, width, slop, 0f, 0f, calib, 600L)
        val slowIntensity = kotlin.math.abs(slowController.overScrollFraction)

        // Fast flick of 120px in 40ms
        val fastController = EdgeOverScrollController()
        fastController.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 200f, 500f, width, slop, 0f, 0f, calib, 0L)
        fastController.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 200f + 120f, 500f, width, slop, 0f, 0f, calib, 40L)
        val fastIntensity = kotlin.math.abs(fastController.overScrollFraction)

        assertTrue(
            "Fast flick ($fastIntensity) should exceed slow drag ($slowIntensity)",
            fastIntensity > slowIntensity + 0.08f
        )
        assertTrue("Slow drag minimum intensity ($slowIntensity) should remain responsive", slowIntensity > 0.18f)
        assertTrue("Fast flick intensity ($fastIntensity) should be softly capped", fastIntensity < 0.34f)
    }

    @Test
    fun testEdgeOverScrollReversalsControlSnapBackModeAndYielding() {
        val calib = CalibrationConfig(pageCount = 3, customOffsets = listOf(0f, 0.5f, 1f))
        val controller = EdgeOverScrollController()
        val width = 1000
        val slop = 24

        // Case 1: Swipe right +150px, then reverse left -100px (net +50px outward -> stays on Page 1, slow snap-back)
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 450f, 500f, width, slop, 0f, 0f, calib, 100L)
        val peakFraction = controller.overScrollFraction
        assertTrue(peakFraction < -0.15f)

        val partialReversal = controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 350f, 500f, width, slop, 0f, 0f, calib, 220L)
        assertTrue(partialReversal.handled)
        assertTrue(controller.isOverScrollLocked)
        assertTrue("Partial reversal should still remain negative (outward)", controller.overScrollFraction < 0f)
        assertTrue("Partial reversal should reduce magnitude", controller.overScrollFraction > peakFraction)

        controller.onTouchSignal(MotionEvent.ACTION_UP, 0, 350f, 500f, width, slop, 0f, 0f, calib, 240L)
        assertEquals(EdgeOverScrollController.SnapBackMode.SLOW, controller.snapBackMode)
        controller.stepRelease(EdgeOverScrollController.SLOW_SNAP_BACK_DURATION_NANOS, 0.5f)
        assertEquals(0f, controller.overScrollFraction, 0.001f)

        // Case 2: Swipe right +150px, then reverse left -180px (exceeds outward swipe -> yields for Page 2, retains live fraction for smooth overlap)
        controller.reset()
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 450f, 500f, width, slop, 0f, 0f, calib, 100L)
        val fractionBeforeReversal = controller.overScrollFraction
        assertTrue(fractionBeforeReversal < -0.15f)

        val fullReversal = controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 270f, 500f, width, slop, 0.1f, 0f, calib, 200L)
        assertFalse(fullReversal.handled)
        assertTrue(fullReversal.needsRedraw)
        assertFalse(controller.isTouchActive)
        assertFalse(controller.isOverScrollLocked)
        assertTrue(controller.hasActiveFraction())
        assertEquals(fractionBeforeReversal, controller.overScrollFraction, 0.001f)
        assertEquals(EdgeOverScrollController.SnapBackMode.FAST, controller.snapBackMode)

        controller.stepRelease(16_666_667L, 0.5f)
        assertTrue(
            "FAST snap-back should decay smoothly over frames rather than snapping to 0 in one frame",
            controller.overScrollFraction > fractionBeforeReversal && controller.overScrollFraction < 0f
        )
        repeat(24) {
            controller.stepRelease(16_666_667L, 0.5f)
        }
        assertEquals(0f, controller.overScrollFraction, 0.001f)

        // Case 3: Post-release promotion to FAST snap-back uses time-capped exponential smoothing
        controller.reset()
        controller.onTouchSignal(MotionEvent.ACTION_DOWN, 0, 300f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_MOVE, 0, 300f + slop + 275f, 500f, width, slop, 0f, 0f, calib, 0L)
        controller.onTouchSignal(MotionEvent.ACTION_UP, 0, 300f + slop + 275f, 500f, width, slop, 0f, 0f, calib, 0L)
        assertEquals(EdgeOverScrollController.SnapBackMode.SLOW, controller.snapBackMode)
        controller.promoteToFastSnapBack()
        assertEquals(EdgeOverScrollController.SnapBackMode.FAST, controller.snapBackMode)
        controller.stepRelease(16_666_667L, 0.5f)
        assertTrue(
            "FAST snap-back step (${controller.overScrollFraction}) should be smoothly capped around -0.345",
            controller.overScrollFraction < -0.28f && controller.overScrollFraction > -0.40f
        )
    }

    @Test
    fun testEdgeOverScrollParallaxScalingIsScreenAnchoredAndCapped() {
        val screenWidth = 1000
        val smallReachPx = 150    // 0.15 screen widths
        val standardReachPx = 500 // 0.50 screen widths (2x screen wide centered)
        val ultraWideReachPx = 1250 // 1.25 screen widths

        val dimUltraHalf = EdgeOverScrollScaling.computeTravelPx(0.5f, ultraWideReachPx, screenWidth)
        val dimUltraFull = EdgeOverScrollScaling.computeTravelPx(1.0f, ultraWideReachPx, screenWidth)
        val dimStdFull = EdgeOverScrollScaling.computeTravelPx(1.0f, standardReachPx, screenWidth)
        val dimSmallFull = EdgeOverScrollScaling.computeTravelPx(1.0f, smallReachPx, screenWidth)

        assertTrue(dimUltraFull <= 720.5f && dimUltraFull < ultraWideReachPx)
        assertTrue("Diminishing curve should give >55% of its travel by 50% intensity", dimUltraHalf > dimUltraFull * 0.55f)
        assertEquals(standardReachPx.toFloat(), dimStdFull, 0.5f)
        assertEquals(smallReachPx.toFloat(), dimSmallFull, 0.5f)

        val leftSourceFull = EdgeOverScrollScaling.computeSourceLeftPx(
            intensity = -1f,
            leftTravelPx = ultraWideReachPx,
            rightTravelPx = ultraWideReachPx,
            screenWidth = screenWidth
        )
        assertEquals(ultraWideReachPx - 720f, leftSourceFull, 0.5f)

        val rightSourceSmall = EdgeOverScrollScaling.computeSourceLeftPx(
            intensity = 1f,
            leftTravelPx = smallReachPx,
            rightTravelPx = smallReachPx,
            screenWidth = screenWidth
        )
        assertEquals((smallReachPx * 2).toFloat(), rightSourceSmall, 0.5f)
    }

    @Test
    fun testEdgeOverScrollCurveKeyIsIgnoredWhenRestoringState() {
        val state = WallpaperAppState(
            pages = listOf(PageItem(0)),
            calibration = CalibrationConfig()
        )
        val jsonWithLegacyCurve = state.toJson().apply {
            put("edgeOverScrollCurve", "POWER_FLING")
        }
        val restored = WallpaperAppState.fromJson(jsonWithLegacyCurve)
        assertEquals(1, restored.pages.size)
        assertFalse(state.toJson().has("edgeOverScrollCurve"))
    }
}

