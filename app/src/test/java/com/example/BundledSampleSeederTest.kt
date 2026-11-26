package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.BundledSampleSeeder
import com.example.data.repository.WallpaperRepository
import com.example.ui.WallpaperViewModel
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BundledSampleSeederTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun freshLaunchSeedsThreeAspectRatioSamplesAndAssignsAllThreeDefaultPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WallpaperRepository.getInstance(context)
        repository.awaitStartupForTesting()

        val originals = repository.originals.value
        assertEquals(3, originals.size)

        val tall = originals.firstOrNull { it.id == BundledSampleSeeder.SAMPLE_TALL_ID }
        val wide = originals.firstOrNull { it.id == BundledSampleSeeder.SAMPLE_WIDE_ID }
        val square = originals.firstOrNull { it.id == BundledSampleSeeder.SAMPLE_SQUARE_ID }

        assertNotNull(tall)
        assertNotNull(wide)
        assertNotNull(square)

        assertTrue("Tall sample should be portrait", tall!!.height > tall.width)
        assertTrue("Wide sample should be landscape", wide!!.width > wide.height)
        assertEquals("Square sample should be 1:1", square!!.width, square.height)

        listOf(tall, wide, square).forEach { media ->
            val file = File(context.filesDir, "originals/${media.fileName}")
            assertTrue("Expected sample file on disk: ${file.name}", file.exists() && file.length() > 0L)
        }

        val state = repository.appState.value
        assertEquals(1, state.calibration.homePageIndex)
        assertEquals(BundledSampleSeeder.SAMPLE_TALL_ID, state.pages[0].originalId)
        assertEquals(BundledSampleSeeder.SAMPLE_WIDE_ID, state.pages[1].originalId)
        assertEquals(BundledSampleSeeder.SAMPLE_SQUARE_ID, state.pages[2].originalId)
        assertFalse(state.pages[1].isBlank)
    }

    @Test
    fun studioPagesOpenEditPageSettingsImmediatelyAfterStartup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = WallpaperRepository.getInstance(app)
        repository.awaitStartupForTesting()

        val viewModel = WallpaperViewModel(app)
        var editedPage: Int? = null
        var chosenPage: Int? = null

        composeRule.setContent {
            MyApplicationTheme {
                StudioScreen(
                    viewModel = viewModel,
                    onChooseMediaForPage = { chosenPage = it },
                    onEditPage = { editedPage = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("edit_page_1_preview_action").assertIsDisplayed()
        composeRule.onNodeWithTag("page_preview_1").performClick()
        composeRule.waitForIdle()

        assertEquals(1, editedPage)
        assertNull(chosenPage)
    }
}
