package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.darken.ExtraDimDetector
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTriggerMode
import com.example.data.model.OriginalMedia
import com.example.data.model.PageItem
import com.example.data.repository.WallpaperRepository
import com.example.render.DarkenTransitionController
import com.example.ui.WallpaperViewModel
import com.example.ui.screens.EffectsScreen
import com.example.ui.screens.MediaEditorScreen
import com.example.ui.theme.MyApplicationTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkenControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun legacyDarkenTriggerModesMigrateToEnabledAndTriggerMode() {
        val darkTheme = DarkenConfig.fromJson(JSONObject().put("triggerMode", "DARK_THEME"))
        assertTrue(darkTheme.enabled)
        assertEquals(DarkenTriggerMode.DARK_MODE_ONLY, darkTheme.triggerMode)
        assertTrue(darkTheme.useDarkMode)
        assertFalse(darkTheme.useSchedule)

        val scheduled = DarkenConfig.fromJson(JSONObject().put("triggerMode", "SCHEDULED"))
        assertTrue(scheduled.enabled)
        assertEquals(DarkenTriggerMode.SCHEDULE_ONLY, scheduled.triggerMode)
        assertFalse(scheduled.useDarkMode)
        assertTrue(scheduled.useSchedule)

        val always = DarkenConfig.fromJson(JSONObject().put("triggerMode", "ALWAYS"))
        assertTrue(always.enabled)
        assertEquals(DarkenTriggerMode.BOTH_OR, always.triggerMode)
        assertTrue(always.useDarkMode)
        assertTrue(always.useSchedule)

        val extraDim = DarkenConfig.fromJson(JSONObject().put("triggerMode", "EXTRA_DIM"))
        assertFalse(extraDim.enabled)
        assertFalse(extraDim.useDarkMode)
        assertFalse(extraDim.useSchedule)

        val off = DarkenConfig.fromJson(JSONObject().put("triggerMode", "OFF"))
        assertFalse(off.enabled)

        val legacyBoth = DarkenConfig.fromJson(
            JSONObject()
                .put("useDarkMode", true)
                .put("useSchedule", true)
        )
        assertTrue(legacyBoth.enabled)
        assertEquals(DarkenTriggerMode.BOTH_OR, legacyBoth.triggerMode)

        val legacyNeither = DarkenConfig.fromJson(
            JSONObject()
                .put("useDarkMode", false)
                .put("useSchedule", false)
        )
        assertFalse(legacyNeither.enabled)
    }

    @Test
    fun triggerModesEvaluateScheduleOnlyDarkModeOnlyAndBothOr() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val disabled = DarkenConfig(
            enabled = false,
            triggerMode = DarkenTriggerMode.BOTH_OR,
            scheduleStartHour = 20,
            scheduleStartMinute = 30,
            scheduleEndHour = 7,
            scheduleEndMinute = 15
        )
        assertFalse(
            ExtraDimDetector.isDimConditionActive(
                context,
                disabled,
                isDarkModeOverride = true,
                hourOfDay = 22,
                minute = 0
            )
        )

        val darkModeOnly = disabled.copy(enabled = true, triggerMode = DarkenTriggerMode.DARK_MODE_ONLY)
        assertTrue(ExtraDimDetector.isDimConditionActive(context, darkModeOnly, isDarkModeOverride = true, hourOfDay = 12, minute = 0))
        assertFalse(ExtraDimDetector.isDimConditionActive(context, darkModeOnly, isDarkModeOverride = false, hourOfDay = 22, minute = 0))

        val scheduleOnlyOvernight = disabled.copy(enabled = true, triggerMode = DarkenTriggerMode.SCHEDULE_ONLY)
        assertTrue(ExtraDimDetector.isDimConditionActive(context, scheduleOnlyOvernight, isDarkModeOverride = true, hourOfDay = 20, minute = 30))
        assertTrue(ExtraDimDetector.isDimConditionActive(context, scheduleOnlyOvernight, isDarkModeOverride = false, hourOfDay = 23, minute = 45))
        assertTrue(ExtraDimDetector.isDimConditionActive(context, scheduleOnlyOvernight, isDarkModeOverride = false, hourOfDay = 7, minute = 14))
        assertFalse(ExtraDimDetector.isDimConditionActive(context, scheduleOnlyOvernight, isDarkModeOverride = true, hourOfDay = 12, minute = 0))
        assertFalse(ExtraDimDetector.isDimConditionActive(context, scheduleOnlyOvernight, isDarkModeOverride = false, hourOfDay = 7, minute = 15))
        assertFalse(ExtraDimDetector.isDimConditionActive(context, scheduleOnlyOvernight, isDarkModeOverride = false, hourOfDay = 20, minute = 29))

        val sameDaySchedule = DarkenConfig(
            enabled = true,
            triggerMode = DarkenTriggerMode.SCHEDULE_ONLY,
            scheduleStartHour = 9,
            scheduleStartMinute = 15,
            scheduleEndHour = 17,
            scheduleEndMinute = 45
        )
        assertTrue(ExtraDimDetector.isScheduledActive(sameDaySchedule, 9, 15))
        assertTrue(ExtraDimDetector.isScheduledActive(sameDaySchedule, 13, 0))
        assertFalse(ExtraDimDetector.isScheduledActive(sameDaySchedule, 17, 45))
        assertFalse(ExtraDimDetector.isScheduledActive(sameDaySchedule, 8, 59))

        val identicalTimes = sameDaySchedule.copy(scheduleEndHour = 9, scheduleEndMinute = 15)
        assertFalse(ExtraDimDetector.isScheduledActive(identicalTimes, 9, 15))

        val bothOr = scheduleOnlyOvernight.copy(triggerMode = DarkenTriggerMode.BOTH_OR)
        // Active if Dark Mode is on even outside scheduled hours
        assertTrue(ExtraDimDetector.isDimConditionActive(context, bothOr, isDarkModeOverride = true, hourOfDay = 12, minute = 0))
        // Active if inside scheduled hours even when Dark Mode is off
        assertTrue(ExtraDimDetector.isDimConditionActive(context, bothOr, isDarkModeOverride = false, hourOfDay = 22, minute = 10))
        // Inactive only when both signals are off
        assertFalse(ExtraDimDetector.isDimConditionActive(context, bothOr, isDarkModeOverride = false, hourOfDay = 12, minute = 0))
    }

    @Test
    fun darkenTransitionControllerAnimatesAround550MsAndSupportsReversal() {
        assertEquals(550, DarkenTransitionController.DEFAULT_DURATION_MS)
        val controller = DarkenTransitionController()
        assertEquals(0f, controller.currentMix, 0.0001f)
        assertFalse(controller.isActive)

        controller.snapTo(true)
        assertEquals(1f, controller.currentMix, 0.0001f)
        assertFalse(controller.isActive)

        controller.transitionTo(darkened = false)
        assertTrue(controller.isActive)
        assertEquals(0f, controller.targetMix, 0.0001f)

        controller.step(275_000_000L) // halfway through 550ms
        val midDownMix = controller.currentMix
        assertTrue("Expected smooth halfway mix near 0.5, got $midDownMix", midDownMix in 0.45f..0.55f)
        assertTrue(controller.isActive)

        // Reverse direction mid-flight without snapping
        controller.transitionTo(darkened = true)
        assertEquals(midDownMix, controller.currentMix, 0.0001f)
        assertEquals(1f, controller.targetMix, 0.0001f)
        assertTrue(controller.isActive)

        controller.step(100_000_000L)
        assertTrue(controller.currentMix > midDownMix)
        assertTrue(controller.currentMix < 1f)

        controller.step(250_000_000L)
        assertEquals(1f, controller.currentMix, 0.0001f)
        assertFalse(controller.isActive)
    }

    @Test
    fun repositoryGeneratesCachesAndInvalidatesDarkenedBitmapsUsingUnifiedDimFactor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)

        val croppedDir = File(context.filesDir, "cropped").apply { mkdirs() }
        val brightSource = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(200, 200, 200))
        }
        FileOutputStream(File(croppedDir, "test_bright_page.jpg")).use { out ->
            brightSource.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        brightSource.recycle()

        repository.updateState { state ->
            state.copy(
                pages = listOf(
                    PageItem(
                        pageIndex = 0,
                        croppedFileName = "test_bright_page.jpg",
                        isAlreadyDark = false,
                        isBlank = false,
                        dimFactor = 0.20f
                    ),
                    PageItem(
                        pageIndex = 1,
                        croppedFileName = "test_bright_page.jpg",
                        isAlreadyDark = true,
                        isBlank = false,
                        dimFactor = 0.80f
                    ),
                    PageItem(
                        pageIndex = 2,
                        croppedFileName = "test_bright_page.jpg",
                        isAlreadyDark = false,
                        isBlank = false,
                        dimFactor = 0.90f
                    )
                ),
                darken = DarkenConfig(
                    enabled = true,
                    triggerMode = DarkenTriggerMode.DARK_MODE_ONLY,
                    autoDetectDark = true,
                    dimFactor = 0.50f
                )
            )
        }

        val normalBmp = repository.getCroppedBitmap(0, 100, 200, darkened = false)
        assertNotNull(normalBmp)
        val darkenedBmp = repository.getCroppedBitmap(0, 100, 200, darkened = true)
        assertNotNull(darkenedBmp)
        val page2DarkenedBmp = repository.getCroppedBitmap(2, 100, 200, darkened = true)
        assertNotNull(page2DarkenedBmp)

        val normalRed = Color.red(normalBmp!!.getPixel(50, 100))
        val darkRed = Color.red(darkenedBmp!!.getPixel(50, 100))
        val page2DarkRed = Color.red(page2DarkenedBmp!!.getPixel(50, 100))
        assertTrue("Expected normal ($normalRed) brighter than darkened ($darkRed)", darkRed < normalRed - 50)
        // Both light pages use unified DarkenConfig.dimFactor rather than per-page dimFactor
        assertEquals(darkRed, page2DarkRed)

        val diskCacheFile = repository.getDarkenedCacheFileForTesting(0, 100, 200)
        assertNotNull(diskCacheFile)
        assertTrue("Expected darkened cache file to be persisted at ${diskCacheFile?.path}", diskCacheFile!!.exists())

        // Page 1 flagged as already dark should NOT be darkened when autoDetectDark is enabled
        val alreadyDarkResult = repository.getCroppedBitmap(1, 100, 200, darkened = true)
        assertNotNull(alreadyDarkResult)
        assertTrue(Color.red(alreadyDarkResult!!.getPixel(50, 100)) > 170)

        // Updating unified DarkenConfig.dimFactor should invalidate darkened cache and regenerate a darker cached file while pruning the old one
        val prevGeneration = repository.darkenedBitmapGeneration
        repository.updateState { state ->
            state.copy(
                darken = state.darken.copy(dimFactor = 0.75f)
            )
        }
        assertTrue(repository.darkenedBitmapGeneration > prevGeneration)

        val darkerBmp = repository.getCroppedBitmap(0, 100, 200, darkened = true)!!
        val darkerRed = Color.red(darkerBmp.getPixel(50, 100))
        assertTrue("Expected 75% dim ($darkerRed) darker than 50% dim ($darkRed)", darkerRed < darkRed - 15)
        val newDiskCacheFile = repository.getDarkenedCacheFileForTesting(0, 100, 200)!!
        assertTrue(newDiskCacheFile.exists())
        assertFalse("Expected old cache file to be pruned", diskCacheFile.exists())
    }

    @Test
    fun batterySaverDarkenTriggerAndSupplementalToggleEvaluateAndRoundTripJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val batteryOnly = DarkenConfig(
            enabled = true,
            triggerMode = DarkenTriggerMode.BATTERY_SAVER_ONLY
        )
        assertTrue(batteryOnly.useBatterySaver)
        assertFalse(batteryOnly.useDarkMode)
        assertFalse(batteryOnly.useSchedule)
        assertFalse(
            ExtraDimDetector.isDimConditionActive(
                context,
                batteryOnly,
                isDarkModeOverride = true,
                isBatterySaverActive = false
            )
        )
        assertTrue(
            ExtraDimDetector.isDimConditionActive(
                context,
                batteryOnly,
                isDarkModeOverride = false,
                isBatterySaverActive = true
            )
        )

        val supplemental = DarkenConfig(
            enabled = true,
            triggerMode = DarkenTriggerMode.DARK_MODE_ONLY,
            darkenOnBatterySaver = true
        )
        assertTrue(supplemental.useBatterySaver)
        assertTrue(
            ExtraDimDetector.isDimConditionActive(
                context,
                supplemental,
                isDarkModeOverride = false,
                isBatterySaverActive = true
            )
        )

        val roundTrip = DarkenConfig.fromJson(supplemental.toJson())
        assertEquals(DarkenTriggerMode.DARK_MODE_ONLY, roundTrip.triggerMode)
        assertTrue(roundTrip.darkenOnBatterySaver)
        assertTrue(roundTrip.enabled)

        val batteryOnlyRoundTrip = DarkenConfig.fromJson(batteryOnly.toJson())
        assertEquals(DarkenTriggerMode.BATTERY_SAVER_ONLY, batteryOnlyRoundTrip.triggerMode)
        assertTrue(batteryOnlyRoundTrip.useBatterySaver)
    }

    @Test
    fun effectsScreenProvidesTriggerChoicesBatterySaverConsolidationAndPopupCancelConfirm() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = WallpaperViewModel(app)
        viewModel.updateDarkenConfig(
            DarkenConfig(
                enabled = false,
                triggerMode = DarkenTriggerMode.DARK_MODE_ONLY,
                scheduleStartHour = 20,
                scheduleStartMinute = 0,
                scheduleEndHour = 7,
                scheduleEndMinute = 0
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                EffectsScreen(viewModel = viewModel)
            }
        }

        // Verify no cache-related copy or global dim slider is exposed on dashboard
        assertEquals(0, composeRule.onAllNodesWithText("cache", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Pre-generate", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("dim_factor_slider").assertDoesNotExist()
        // Toggles are not exposed inline while scrolling
        composeRule.onNodeWithTag("toggle_darken_enabled").assertDoesNotExist()

        // Open consolidated Darken & Battery conditions editor popup, toggle on, then Cancel -> state stays disabled
        composeRule.onNodeWithTag("darken_settings_card_edit_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("darken_conditions_editor_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_darken_enabled").performScrollTo().performClick()
        composeRule.onNodeWithTag("editor_cancel_button").performClick()
        composeRule.waitForIdle()
        assertFalse(viewModel.appState.value.darken.enabled)

        // Re-open editor popup, enable darken, verify consolidated controls & 4 trigger choices
        composeRule.onNodeWithTag("darken_settings_card_edit_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("toggle_darken_enabled").performScrollTo().performClick()

        // Choice 1: Battery Saver Only & consolidated Battery Saver section
        composeRule.onNodeWithTag("darken_mode_battery_saver_only").performScrollTo().performClick()
        composeRule.onNodeWithTag("toggle_battery_saver").performScrollTo().assertIsDisplayed()

        // Choice 2: Scheduled Time Only
        composeRule.onNodeWithTag("darken_mode_schedule_only").performScrollTo().performClick()
        composeRule.onNodeWithTag("darken_schedule_start_button").performScrollTo().assertIsDisplayed()

        // Choice 3: System Dark Mode Only
        composeRule.onNodeWithTag("darken_mode_dark_mode_only").performScrollTo().performClick()
        composeRule.onNodeWithTag("toggle_darken_on_battery_saver").performScrollTo().performClick()

        // Choice 4: Both (Either Triggers)
        composeRule.onNodeWithTag("darken_mode_both_or").performScrollTo().performClick()

        // Open Start time dialog, switch to keyboard input, enter 21:45, and confirm
        composeRule.onNodeWithTag("darken_schedule_start_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("darken_time_picker_mode_toggle").performClick()
        composeRule.onNodeWithContentDescription("for hour").performTextReplacement("21")
        composeRule.onNodeWithContentDescription("for minutes").performTextReplacement("45")
        composeRule.onNodeWithTag("darken_time_picker_confirm").performClick()
        composeRule.waitForIdle()

        // Confirm editor popup
        composeRule.onNodeWithTag("editor_confirm_button").performClick()
        composeRule.waitForIdle()

        val updated = viewModel.appState.value.darken
        assertTrue(updated.enabled)
        assertEquals(DarkenTriggerMode.BOTH_OR, updated.triggerMode)
        assertTrue(updated.darkenOnBatterySaver)
        assertEquals(21, updated.scheduleStartHour)
        assertEquals(45, updated.scheduleStartMinute)
        composeRule.onNodeWithTag("darken_settings_card").performScrollTo()
        composeRule.onNodeWithText("21:45 – 07:00").assertIsDisplayed()
    }

    @Test
    fun pageSettingsShowsUnifiedDarkenPreviewAndAlreadyDarkToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalsDir = File(context.filesDir, "originals").apply { mkdirs() }
        val fileName = "test_page_settings_media.jpg"
        val bmp = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(180, 190, 210))
        }
        FileOutputStream(File(originalsDir, fileName)).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()

        val media = OriginalMedia(
            id = "media_test_0",
            fileName = fileName,
            dateAdded = 1000L,
            width = 120,
            height = 240,
            averageLuminance = 0.75f,
            isDarkDetected = false
        )
        val initialPage = PageItem(
            pageIndex = 0,
            originalId = media.id,
            isAlreadyDark = false,
            dimFactor = 0.40f
        )
        var savedPage: PageItem? = null

        composeRule.setContent {
            MyApplicationTheme {
                MediaEditorScreen(
                    media = media,
                    page = initialPage,
                    pageCount = 3,
                    isSaving = false,
                    unifiedDimFactor = 0.40f,
                    onDismiss = {},
                    onSave = { savedPage = it }
                )
            }
        }

        composeRule.waitForIdle()

        // Toggle darkened preview on and verify unified preview badge & note appear
        composeRule.onNodeWithTag("toggle_preview_darkened").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_darken_preview_badge", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Darkened preview · 40%", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("crop_unified_darken_note").performScrollTo().assertIsDisplayed()

        // Toggle Already dark on -> preview badge hides
        composeRule.onNodeWithTag("toggle_is_already_dark").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_darken_preview_badge", useUnmergedTree = true).assertDoesNotExist()

        // Toggle Already dark back off and save
        composeRule.onNodeWithTag("toggle_is_already_dark").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_confirm_button").performClick()

        assertNotNull(savedPage)
        assertFalse(savedPage!!.isAlreadyDark)
    }
}
