package com.example

import android.app.Application
import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CalibrationConfig
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTriggerMode
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.repository.WallpaperRepository
import com.example.service.WallpaperColorHelper
import com.example.ui.WallpaperViewModel
import com.example.ui.screens.EffectsScreen
import com.example.ui.screens.MediaEditorScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.screens.calculateFullImagePreviewPan
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ImageProcessingUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedbackReworkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalizedDarkeningConvergesDifferentBrightnessWallpapersWithoutBrightening() {
        val washedOutBright = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val midContrast = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) {
            val t = y / 119f
            val washedV = (155 + (245 - 155) * t).toInt()
            val midV = (60 + (210 - 60) * t).toInt()
            for (x in 0 until 120) {
                washedOutBright.setPixel(x, y, Color.rgb(washedV, washedV, washedV))
                midContrast.setPixel(x, y, Color.rgb(midV, midV, midV))
            }
        }

        val darkWashed = ImageProcessingUtils.createDarkenedCopy(washedOutBright, dimFactor = 0.55f)
        val darkMid = ImageProcessingUtils.createDarkenedCopy(midContrast, dimFactor = 0.55f)

        fun stats(bmp: Bitmap): Triple<Float, Float, Float> {
            var sum = 0.0
            var minL = 1f
            var maxL = 0f
            for (y in 0 until bmp.height) {
                val p = bmp.getPixel(60, y)
                val l = (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
                sum += l
                if (l < minL) minL = l
                if (l > maxL) maxL = l
            }
            return Triple((sum / bmp.height).toFloat(), minL, maxL)
        }

        val (meanWashed, _, peakWashed) = stats(darkWashed)
        val (meanMid, _, peakMid) = stats(darkMid)

        assertTrue(
            "Expected normalized means to converge closely, got washed=$meanWashed vs mid=$meanMid",
            abs(meanWashed - meanMid) < 0.05f
        )
        assertTrue(
            "Expected peaks to converge closely, got washed=$peakWashed vs mid=$peakMid",
            abs(peakWashed - peakMid) < 0.08f
        )

        // Ensure no pixel was brightened beyond its original value
        for (y in 0 until 120) {
            assertTrue(Color.red(darkWashed.getPixel(60, y)) <= Color.red(washedOutBright.getPixel(60, y)))
            assertTrue(Color.red(darkMid.getPixel(60, y)) <= Color.red(midContrast.getPixel(60, y)))
        }

        washedOutBright.recycle()
        midContrast.recycle()
        darkWashed.recycle()
        darkMid.recycle()
    }

    @Test
    fun wallpaperColorHelperNeverEmitsDarkTextHintOnAlreadyDarkOrDarkenedPages() {
        val brightBmp = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(245, 248, 250))
        }
        val alreadyDarkPage = PageItem(pageIndex = 1, isAlreadyDark = true)
        val lightPage = PageItem(pageIndex = 0, isAlreadyDark = false)

        val alreadyDarkDecision = WallpaperColorHelper.decideHints(
            page = alreadyDarkPage,
            isDarkened = false,
            topBandLuminance = 0.96f,
            overallLuminance = 0.96f
        )
        assertFalse(alreadyDarkDecision.allowDarkTextHint)
        assertTrue(alreadyDarkDecision.suggestDarkThemeHint)

        val darkenedDecision = WallpaperColorHelper.decideHints(
            page = lightPage,
            isDarkened = true,
            topBandLuminance = 0.96f,
            overallLuminance = 0.96f
        )
        assertFalse(darkenedDecision.allowDarkTextHint)
        assertTrue(darkenedDecision.suggestDarkThemeHint)

        val colors = WallpaperColorHelper.computeWallpaperColors(
            bitmap = brightBmp,
            page = alreadyDarkPage,
            isDarkened = false
        )
        assertTrue(colors.primaryColor.luminance() <= 0.35f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(0, colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
            assertNotEquals(0, colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_THEME)
        }

        val calibration = CalibrationConfig(
            pageCount = 3,
            homePageIndex = 1,
            customOffsets = listOf(0f, 0.5f, 1f)
        )
        assertEquals(0, WallpaperColorHelper.nearestPageIndex(0.15f, calibration))
        assertEquals(1, WallpaperColorHelper.nearestPageIndex(0.48f, calibration))
        assertEquals(2, WallpaperColorHelper.nearestPageIndex(0.92f, calibration))

        brightBmp.recycle()
    }

    @Test
    fun studioScreenInlinesNewPresetButtonAndShowsAllPagesDarkenGridSimultaneously() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = WallpaperViewModel(app)
        viewModel.setPageCount(5)

        composeRule.setContent {
            MyApplicationTheme {
                StudioScreen(
                    viewModel = viewModel,
                    onChooseMediaForPage = {},
                    onEditPage = {},
                    showDarkenPreview = true
                )
            }
        }
        composeRule.waitForIdle()

        // Presets header title removed; New button inline with chips
        assertEquals(0, composeRule.onAllNodesWithText("Presets").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("create_preset_button").assertIsDisplayed()

        // All 5 pages visible simultaneously in darken preview card
        composeRule.onNodeWithTag("studio_all_pages_darken_card").assertIsDisplayed()
        for (i in 0 until 5) {
            composeRule.onNodeWithTag("darken_grid_page_$i").assertIsDisplayed()
        }

        // Toggle page 2 Already dark via grid badge
        val wasDark = viewModel.appState.value.pages[2].isAlreadyDark
        composeRule.onNodeWithTag("darken_grid_page_2").performClick()
        composeRule.waitForIdle()
        assertEquals(!wasDark, viewModel.appState.value.pages[2].isAlreadyDark)

        // Unified darken slider updates DarkenConfig.dimFactor
        composeRule.onNodeWithTag("studio_unified_darken_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                action(0.70f)
            }
        composeRule.waitForIdle()
        assertEquals(0.70f, viewModel.appState.value.darken.dimFactor, 0.02f)
    }

    @Test
    fun effectsScreenDarkenEditorIncludesUnifiedSliderAndAllPagesPreview() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = WallpaperViewModel(app)
        viewModel.setPageCount(4)
        viewModel.updateDarkenConfig(
            DarkenConfig(
                enabled = true,
                triggerMode = DarkenTriggerMode.DARK_MODE_ONLY,
                dimFactor = 0.50f
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                EffectsScreen(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("darken_settings_card_edit_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("darken_unified_level_slider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("darken_dialog_all_pages_preview").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("darken_unified_level_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                action(0.65f)
            }
        composeRule.onNodeWithTag("editor_confirm_button").performClick()
        composeRule.waitForIdle()

        assertEquals(0.65f, viewModel.appState.value.darken.dimFactor, 0.02f)
    }

    @Test
    fun mediaEditorTogglesUnifiedPreviewModeAndDoesNotPersistTemporarySplit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)
        repository.resetEditorPreviewSplitFraction()

        val originalsDir = File(context.filesDir, "originals").apply { mkdirs() }
        val fileName = "test_rework_crop.jpg"
        val bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(210, 180, 140))
        }
        FileOutputStream(File(originalsDir, fileName)).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()

        val media = OriginalMedia(
            id = "media_rework_crop",
            fileName = fileName,
            dateAdded = 2000L,
            width = 180,
            height = 180,
            averageLuminance = 0.72f,
            isDarkDetected = false
        )
        val page = PageItem(pageIndex = 0, originalId = media.id)

        composeRule.setContent {
            MyApplicationTheme {
                MediaEditorScreen(
                    media = media,
                    page = page,
                    pageCount = 3,
                    isSaving = false,
                    onDismiss = {},
                    onSave = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Single-page preview shown initially inside unified container; body clicks do not toggle modes
        composeRule.onNodeWithTag("crop_unified_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_single_page_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").assertDoesNotExist()

        composeRule.onNodeWithTag("crop_single_page_preview").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_single_page_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").assertDoesNotExist()

        // Tapping the mode toggle pill badge switches to full-image preview
        composeRule.onNodeWithTag("crop_preview_mode_badge").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_single_page_preview").assertDoesNotExist()

        // Clicking the full-image preview body itself does not toggle back
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_single_page_preview").assertDoesNotExist()

        // Drag split-screen separator downwards -> temporary in-memory adjustment only, storage stays at default
        composeRule.onNodeWithTag("crop_split_separator").performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(
            WallpaperRepository.DEFAULT_EDITOR_PREVIEW_SPLIT_FRACTION,
            repository.getEditorPreviewSplitFraction(),
            0.001f
        )

        // Settings area below remains scrollable
        composeRule.onNodeWithTag("crop_settings_scroll_area").assertIsDisplayed()
    }

    @Test
    fun mainAppTopMenuConditionallyShowsStudioDarkenAndSettingsDebugAndFlagsDialogs() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = WallpaperRepository.getInstance(app)
        repository.resetEditorPreviewSplitFraction()
        repository.resetEditorDefaultFullPreview()
        val viewModel = WallpaperViewModel(app)

        composeRule.setContent {
            MyApplicationTheme {
                MainAppScaffold(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        // Studio tab active by default -> Darken button visible in forehead, Debug/Flags absent
        composeRule.onNodeWithTag("top_menu_studio_darken_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_menu_settings_debug_button").assertDoesNotExist()
        composeRule.onNodeWithTag("top_menu_settings_flags_button").assertDoesNotExist()

        // Click Studio Darken button -> All-pages darken preview appears
        composeRule.onNodeWithTag("top_menu_studio_darken_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("studio_all_pages_darken_card").assertIsDisplayed()

        // Switch to Behavior -> none of Studio/Settings forehead buttons shown
        composeRule.onNodeWithTag("tab_behavior").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_menu_studio_darken_button").assertDoesNotExist()
        composeRule.onNodeWithTag("top_menu_settings_debug_button").assertDoesNotExist()
        composeRule.onNodeWithTag("top_menu_settings_flags_button").assertDoesNotExist()

        // Switch to Settings -> Debug and Flags buttons visible in forehead
        composeRule.onNodeWithTag("tab_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_menu_settings_debug_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_menu_settings_flags_button").assertIsDisplayed()
        composeRule.onNodeWithTag("top_menu_studio_darken_button").assertDoesNotExist()

        // Clicking Debug scrolls Settings to Diagnostics & Debug section
        composeRule.onNodeWithTag("top_menu_settings_debug_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_debug_section").assertIsDisplayed()

        // Open Chrome-style Flags UI
        composeRule.onNodeWithTag("top_menu_settings_flags_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flags_editor_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("flags_bottom_hint").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_cancel_button").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_confirm_button").assertIsDisplayed()

        val rootBounds = composeRule.onNodeWithTag("flags_editor_dialog").getBoundsInRoot()
        val hintBounds = composeRule.onNodeWithTag("flags_bottom_hint").getBoundsInRoot()
        val cancelBounds = composeRule.onNodeWithTag("editor_cancel_button").getBoundsInRoot()
        val confirmBounds = composeRule.onNodeWithTag("editor_confirm_button").getBoundsInRoot()
        assertTrue(hintBounds.bottom <= rootBounds.bottom)
        assertTrue(cancelBounds.bottom <= rootBounds.bottom)
        assertTrue(confirmBounds.bottom <= rootBounds.bottom)

        // Initially no modified dots exist; Modified tab is empty while Unmodified shows both flags
        composeRule.onNodeWithTag("flag_dot_split").assertDoesNotExist()
        composeRule.onNodeWithTag("flag_dot_preview_mode").assertDoesNotExist()

        composeRule.onNodeWithTag("flags_tab_modified").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flags_empty_state").assertIsDisplayed()

        composeRule.onNodeWithTag("flags_tab_unmodified").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_item_split").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("flag_item_preview_mode").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("flags_tab_all").performClick()
        composeRule.waitForIdle()

        // Search matches programmatic name (#editor-default-preview-mode), description, and title
        composeRule.onNodeWithTag("flags_search_input").performTextInput("preview-mode")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_item_preview_mode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("flag_item_split").assertDoesNotExist()

        composeRule.onNodeWithTag("flags_search_input").performTextClearance()
        composeRule.onNodeWithTag("flags_search_input").performTextInput("height ratio")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_item_split").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("flag_item_preview_mode").assertDoesNotExist()

        composeRule.onNodeWithTag("flags_search_clear_button").performClick()
        composeRule.waitForIdle()

        // Modify split slider -> blue dot appears; split moves to Modified tab while preview_mode stays Unmodified
        composeRule.onNodeWithTag("flag_default_split_slider")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                action(0.60f)
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_dot_split").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("flag_dot_preview_mode").assertDoesNotExist()

        composeRule.onNodeWithTag("flags_tab_modified").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_item_split").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("flag_item_preview_mode").assertDoesNotExist()

        // Change preview mode via Chrome selector dropdown as well
        composeRule.onNodeWithTag("flags_tab_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_preview_mode_selector").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_preview_mode_option_full").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_dot_preview_mode").performScrollTo().assertIsDisplayed()

        // Single-item icon reset on split returns split to default while leaving preview mode modified
        composeRule.onNodeWithTag("flag_reset_split_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_dot_split").assertDoesNotExist()
        composeRule.onNodeWithTag("flag_dot_preview_mode").performScrollTo().assertIsDisplayed()

        // Apply changes -> preview mode persists while split remains default
        composeRule.onNodeWithTag("editor_confirm_button").performClick()
        composeRule.waitForIdle()
        assertEquals(
            WallpaperRepository.DEFAULT_EDITOR_PREVIEW_SPLIT_FRACTION,
            repository.getEditorPreviewSplitFraction(),
            0.001f
        )
        assertTrue(repository.getEditorDefaultFullPreview())

        // Re-open Flags, hit Reset all, and Apply -> everything restored to default
        composeRule.onNodeWithTag("top_menu_settings_flags_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flags_reset_default_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flag_dot_preview_mode").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_confirm_button").performClick()
        composeRule.waitForIdle()
        assertFalse(repository.getEditorDefaultFullPreview())
    }

    @Test
    fun mediaEditorRespectsInitialPreviewModeFlag() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)
        repository.saveEditorDefaultFullPreview(true)

        val originalsDir = File(context.filesDir, "originals").apply { mkdirs() }
        val fileName = "test_rework_crop_full_flag.jpg"
        val bmp = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(180, 195, 210))
        }
        FileOutputStream(File(originalsDir, fileName)).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()

        val media = OriginalMedia(
            id = "media_rework_crop_full_flag",
            fileName = fileName,
            dateAdded = 2500L,
            width = 180,
            height = 180,
            averageLuminance = 0.72f,
            isDarkDetected = false
        )
        val page = PageItem(pageIndex = 0, originalId = media.id)

        composeRule.setContent {
            MyApplicationTheme {
                MediaEditorScreen(
                    media = media,
                    page = page,
                    pageCount = 3,
                    isSaving = false,
                    onDismiss = {},
                    onSave = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("crop_parallax_bounds_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("crop_single_page_preview").assertDoesNotExist()

        repository.resetEditorDefaultFullPreview()
    }

    @Test
    fun fullImagePreviewCalculatePanTracksFingerOneToOneInCropAndBlurModes() {
        // Landscape 1000x1000 image fitted in 400x400 canvas (imageScale = 0.4) with 90x195 viewport
        val (cropPanX, cropPanY) = calculateFullImagePreviewPan(
            currentPanX = 0f,
            currentPanY = 0f,
            panDelta = Offset(40f, 0f),
            canvasWidth = 400f,
            canvasHeight = 400f,
            sourceWidth = 1000,
            sourceHeight = 1000,
            viewportWidthPx = 90f,
            viewportHeightPx = 195f,
            zoom = 1f,
            cropFillMode = CropFillMode.CROP
        )
        // Moving finger +40 canvas px = +100 source px right -> panX decreases for direct frame tracking
        assertTrue("Expected negative panX when dragging frame right, got $cropPanX", cropPanX < -0.3f)
        assertEquals(0f, cropPanY, 0.0001f)

        // BLUR mode at 2x zoom: dragging right & down shifts panX and panY negative when overflow exists
        val (blurPanX, blurPanY) = calculateFullImagePreviewPan(
            currentPanX = 0f,
            currentPanY = 0f,
            panDelta = Offset(40f, 0f),
            canvasWidth = 400f,
            canvasHeight = 400f,
            sourceWidth = 1000,
            sourceHeight = 1000,
            viewportWidthPx = 90f,
            viewportHeightPx = 195f,
            zoom = 2f,
            cropFillMode = CropFillMode.BLUR
        )
        assertEquals(-0.4f, blurPanX, 0.001f)
        assertEquals(0f, blurPanY, 0.0001f)

        val (blurTallPanX, blurTallPanY) = calculateFullImagePreviewPan(
            currentPanX = 0f,
            currentPanY = 0f,
            panDelta = Offset(0f, 20f),
            canvasWidth = 400f,
            canvasHeight = 400f,
            sourceWidth = 400,
            sourceHeight = 1000,
            viewportWidthPx = 90f,
            viewportHeightPx = 195f,
            zoom = 2f,
            cropFillMode = CropFillMode.BLUR
        )
        assertEquals(0f, blurTallPanX, 0.0001f)
        assertTrue("Expected negative panY when dragging frame down in tall BLUR mode, got $blurTallPanY", blurTallPanY < -0.1f)
    }

    @Test
    fun fullImagePreviewDragAndPinchUpdateSavedCropAndSinglePagePinchUpdatesScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)
        repository.resetEditorDefaultFullPreview()

        val originalsDir = File(context.filesDir, "originals").apply { mkdirs() }
        val fileName = "test_full_preview_gestures.jpg"
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(120, 160, 200))
        }
        FileOutputStream(File(originalsDir, fileName)).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()

        val media = OriginalMedia(
            id = "media_full_preview_gestures",
            fileName = fileName,
            dateAdded = 3000L,
            width = 400,
            height = 200,
            averageLuminance = 0.6f,
            isDarkDetected = false
        )
        val initialPage = PageItem(
            pageIndex = 0,
            originalId = media.id,
            cropFillMode = CropFillMode.CROP,
            cropPanX = 0f,
            cropPanY = 0f,
            cropScale = 1f
        )
        var savedPage: PageItem? = null

        composeRule.setContent {
            MyApplicationTheme {
                MediaEditorScreen(
                    media = media,
                    page = initialPage,
                    pageCount = 3,
                    isSaving = false,
                    onDismiss = {},
                    onSave = { savedPage = it }
                )
            }
        }
        composeRule.waitForIdle()

        // 1. Pinch out in Single Page mode -> increases zoom from 1.0x
        composeRule.onNodeWithTag("crop_single_page_preview").performTouchInput {
            pinch(
                start0 = center + Offset(-10f, 0f),
                end0 = center + Offset(-40f, 0f),
                start1 = center + Offset(10f, 0f),
                end1 = center + Offset(40f, 0f)
            )
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Zoom: 1.0x").fetchSemanticsNodes().size)

        // 2. Toggle to Full Image mode via pill badge
        composeRule.onNodeWithTag("crop_preview_mode_badge").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").assertIsDisplayed()

        // 3. Pinch out further in Full Image mode -> increases cropScale further
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").performTouchInput {
            pinch(
                start0 = center + Offset(-20f, 0f),
                end0 = center + Offset(-90f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(90f, 0f)
            )
        }
        composeRule.waitForIdle()

        // 4. Drag right and down in Full Image mode -> moves cyan static frame right & down (negative panX and panY)
        composeRule.onNodeWithTag("crop_parallax_bounds_preview").performTouchInput {
            down(center)
            moveBy(Offset(20f, 15f), delayMillis = 20L)
            moveBy(Offset(20f, 15f), delayMillis = 20L)
            moveBy(Offset(20f, 15f), delayMillis = 20L)
            moveBy(Offset(20f, 15f), delayMillis = 20L)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("crop_confirm_button").performClick()
        composeRule.waitForIdle()

        val result = savedPage
        assertTrue("Expected page to be saved", result != null)
        assertTrue("Expected pinch zoom to increase cropScale above 2.0x, got ${result!!.cropScale}", result.cropScale > 2.0f)
        assertTrue("Expected dragging frame right in full-image preview to yield negative cropPanX, got ${result.cropPanX}", result.cropPanX < -0.1f)
        assertTrue("Expected dragging frame down in full-image preview to yield negative cropPanY, got ${result.cropPanY}", result.cropPanY < -0.1f)
    }
}
