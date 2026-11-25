package com.example

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.PageTransitionSetting
import com.example.data.repository.WallpaperRepository
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.ui.AppLanguage
import com.example.ui.ProvideAppLocale
import com.example.ui.WallpaperViewModel
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRefactorAndLocalizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun repositoryCreateDefaultPresetCreatesStarterPagesAndDefaultTransitions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)
        val initialCount = repository.appState.value.presets.size

        val createdId = repository.createDefaultPreset(
            name = "Fresh Setup",
            pageCount = 4,
            homePageIndex = 1
        )
        assertNotNull(createdId)
        val state = repository.appState.value
        assertEquals(initialCount + 1, state.presets.size)
        assertEquals(createdId, state.activePresetId)
        val created = state.presets.firstOrNull { it.id == createdId }
        assertNotNull(created)
        assertEquals("Fresh Setup", created!!.name)
        assertEquals(4, created.pages.size)
        assertEquals(3, created.transitions.size)
        assertEquals(PageTransitionSetting(), created.transitions.first())
    }

    @Test
    fun studioScreenCreatesNewDefaultPresetAndOmitsVerboseDescriptionsAndCalibrateButton() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = WallpaperViewModel(app)
        val initialPresetCount = viewModel.appState.value.presets.size

        composeRule.setContent {
            MyApplicationTheme {
                StudioScreen(
                    viewModel = viewModel,
                    onChooseMediaForPage = {},
                    onEditPage = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Verbose descriptive copy and duplicate Calibrate button removed
        assertEquals(0, composeRule.onAllNodesWithText("Launcher Pages", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Tap a screen to choose its wallpaper", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("saved automatically", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("quick_calibrate_button").fetchSemanticsNodes().size)

        // Create new preset using Default settings
        composeRule.onNodeWithTag("create_preset_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preset_name_field").performTextReplacement("Minimal Default")
        composeRule.onNodeWithTag("confirm_preset_action").performClick()
        composeRule.waitForIdle()

        val updatedState = viewModel.appState.value
        assertEquals(initialPresetCount + 1, updatedState.presets.size)
        val activePreset = updatedState.presets.find { it.id == updatedState.activePresetId }
        assertNotNull(activePreset)
        assertEquals("Minimal Default", activePreset!!.name)
    }

    @Test
    fun settingsScreenUsesDashboardPopupsAndSupportsRecordingHistoryAndClear() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WallpaperDiagnosticsRecorder.clearHistory()
        WallpaperDiagnosticsRecorder.startRecording()
        WallpaperDiagnosticsRecorder.stopRecording()

        val viewModel = WallpaperViewModel(app)
        viewModel.setAppLanguage("en")

        composeRule.setContent {
            MyApplicationTheme {
                SettingsScreen(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        // Battery-saving card was consolidated into Behavior tab, not in Settings
        assertEquals(0, composeRule.onAllNodesWithText("Battery-saving mode", substring = true).fetchSemanticsNodes().size)

        // Dashboard cards exist
        composeRule.onNodeWithTag("language_settings_card").assertIsDisplayed()
        composeRule.onNodeWithTag("calibration_dashboard_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("backup_dashboard_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("debug_overlays_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("wallpaper_diagnostics_card").performScrollTo().assertIsDisplayed()

        // Open Language popup, pick Simplified Chinese, Cancel -> remains "en"
        composeRule.onNodeWithTag("language_settings_card_edit_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("language_option_zh-Hans").performClick()
        composeRule.onNodeWithTag("editor_cancel_button").performClick()
        composeRule.waitForIdle()
        assertEquals("en", viewModel.appState.value.appLanguage)

        // Re-open Language popup, pick Traditional Chinese, Confirm -> saves "zh-Hant"
        composeRule.onNodeWithTag("language_settings_card_edit_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("language_option_zh-Hant").performClick()
        composeRule.onNodeWithTag("editor_confirm_button").performClick()
        composeRule.waitForIdle()
        assertEquals("zh-Hant", viewModel.appState.value.appLanguage)

        // Reset language back to "en" so dialog labels match
        viewModel.setAppLanguage("en")
        composeRule.waitForIdle()

        // View recording history dialog
        assertTrue(WallpaperDiagnosticsRecorder.state.value.history.isNotEmpty())
        composeRule.onNodeWithTag("view_recording_history_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recording_history_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Recorded history").assertIsDisplayed()
        composeRule.onNodeWithTag("close_recording_history_button").performClick()
        composeRule.waitForIdle()

        // Clear recording history with confirmation
        composeRule.onNodeWithTag("clear_recording_history_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirm_clear_recording_history").performClick()
        composeRule.waitForIdle()
        assertTrue(WallpaperDiagnosticsRecorder.state.value.history.isEmpty())
    }

    @Test
    fun localizedStringsResolveForEnglishSimplifiedAndTraditionalChinese() {
        val base = ApplicationProvider.getApplicationContext<Context>()

        fun stringForLocale(locale: Locale, resId: Int): String {
            val config = Configuration(base.resources.configuration).apply {
                setLocale(locale)
            }
            return base.createConfigurationContext(config).getString(resId)
        }

        assertEquals("Behavior", stringForLocale(Locale.ENGLISH, R.string.tab_behavior))
        assertEquals(
            "行为",
            stringForLocale(
                Locale.Builder().setLanguage("zh").setScript("Hans").setRegion("CN").build(),
                R.string.tab_behavior
            )
        )
        assertEquals(
            "行為",
            stringForLocale(
                Locale.Builder().setLanguage("zh").setScript("Hant").setRegion("TW").build(),
                R.string.tab_behavior
            )
        )
        assertEquals(
            "Darken conditions",
            stringForLocale(Locale.ENGLISH, R.string.darken_conditions_title)
        )
        assertEquals(
            "变暗条件",
            stringForLocale(
                Locale.Builder().setLanguage("zh").setScript("Hans").setRegion("CN").build(),
                R.string.darken_conditions_title
            )
        )
        assertEquals(
            "變暗條件",
            stringForLocale(
                Locale.Builder().setLanguage("zh").setScript("Hant").setRegion("TW").build(),
                R.string.darken_conditions_title
            )
        )

        composeRule.setContent {
            ProvideAppLocale(AppLanguage.SIMPLIFIED_CHINESE.tag) {
                Text(text = stringResource(R.string.tab_behavior))
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("行为").assertIsDisplayed()
    }
}
