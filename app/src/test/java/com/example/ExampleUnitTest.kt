package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CalibrationConfig
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTrigger
import com.example.data.model.OffsetEasingStyle
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.ParallaxMotionSettings
import com.example.data.model.WallpaperAppState
import com.example.data.model.WallpaperPreset
import com.example.data.migration.PresetDataMigration
import com.example.data.repository.WallpaperRepository
import com.example.render.HorizontalMotionBlur
import com.example.render.OffsetEasing
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
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        assertEquals(8, registry.transitions.size)
        assertNotNull(registry.get("scroll"))
        assertNotNull(registry.get("parallax"))
        assertNotNull(registry.get("parallax_classic"))
        assertNotNull(registry.get("fade"))
        assertNotNull(registry.get("depth"))
        assertNotNull(registry.get("slide_over"))
        assertNotNull(registry.get("cube"))
        assertEquals("Classic Scroll", registry.get("scroll").displayName)
        assertEquals("Parallax Scroll", registry.get("parallax").displayName)
        assertEquals("Classic Parallax", registry.get("parallax_classic").displayName)
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
            pageASlowImageSpeed = 0.5f
        )
        assertEquals(95, Color.red(output.getPixel(0, 0)))
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
            pageBSlowImageSpeed = 0.5f
        )
        assertEquals(95, Color.red(output.getPixel(50, 0)))
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

        assertFalse(restored.resumeFadeEnabled)
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
                    leftParallaxMotion = ParallaxMotionSettings(true, 0.3f),
                    rightParallaxMotion = ParallaxMotionSettings(true, 0.7f)
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
            restored.transitions[0].parallaxMotion(forward = true).imageSpeed,
            0.001f
        )
        assertEquals(
            0.3f,
            restored.transitions[0].parallaxMotion(forward = false).imageSpeed,
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
            0.35f,
            migrated.transitions[0].universalParallaxMotion.imageSpeed,
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

        assertEquals(4, migrated.schemaVersion)
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
}
