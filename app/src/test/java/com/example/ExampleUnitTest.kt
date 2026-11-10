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
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.model.PageTransitionSetting
import com.example.data.model.WallpaperAppState
import com.example.data.model.WallpaperPreset
import com.example.data.migration.PresetDataMigration
import com.example.data.repository.WallpaperRepository
import org.json.JSONObject
import com.example.transition.TransitionRegistry
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
    fun testParallaxTransitionUsesDirectionalTravelLimits() {
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
            pageABitmap = source,
            pageBBitmap = null,
            progress = 0.5f,
            screenWidth = 100,
            screenHeight = 1,
            paint = Paint(),
            pageALeftTravelPx = 10,
            pageARightTravelPx = 30,
            pageBLeftTravelPx = 0,
            pageBRightTravelPx = 0,
            reverseDirection = true
        )
        assertEquals(55, Color.red(output.getPixel(0, 0)))
        source.recycle()
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
            showOffsetDebugOverlay = true
        )
        val json = state.toJson()
        val restored = WallpaperAppState.fromJson(json)

        assertEquals(3, restored.pages.size)
        assertEquals("Home", restored.pages[1].customLabel)
        assertEquals("cube", restored.transitionType)
        assertTrue(restored.resumeFadeEnabled)
        assertTrue(restored.showOffsetDebugOverlay)
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
        assertFalse(restored.showOffsetDebugOverlay)
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
                    separateDirections = true
                )
            )
        )
        val restored = WallpaperPreset.fromJson(preset.toJson())

        assertEquals(0.4f, restored.pages[0].parallaxAmount, 0.001f)
        assertEquals(0.3f, restored.pages[1].cropPanX, 0.001f)
        assertEquals("parallax", restored.transitions[0].transitionId(forward = true))
        assertEquals("fade", restored.transitions[0].transitionId(forward = false))
        assertEquals("cube", restored.transitions[0].universalTransitionId)
        assertEquals("fade", restored.toJson().getJSONArray("transitions")
            .getJSONObject(0).getString("leftTransitionId"))
        val universalSetting = restored.transitions[0].copy(separateDirections = false)
        assertEquals("cube", universalSetting.transitionId(forward = true))
        assertEquals("cube", universalSetting.transitionId(forward = false))
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

        assertEquals(3, migrated.schemaVersion)
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
