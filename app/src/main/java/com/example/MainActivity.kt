package com.example

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.repository.WallpaperRepository
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.ui.AppLocaleHelper
import com.example.ui.ProvideAppLocale
import com.example.ui.WallpaperViewModel
import com.example.ui.screens.AdvancedFlagsScreen
import com.example.ui.screens.EffectsScreen
import com.example.ui.screens.MediaEditorScreen
import com.example.ui.screens.MediaLibraryScreen
import com.example.ui.screens.MediaPickerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.MyApplicationTheme
import kotlin.math.abs

enum class AppTab(
    @StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    STUDIO(R.string.tab_studio, Icons.Filled.Wallpaper, Icons.Outlined.Wallpaper, "tab_studio"),
    BEHAVIOR(R.string.tab_behavior, Icons.Filled.Tune, Icons.Outlined.Tune, "tab_behavior"),
    MEDIA(R.string.tab_library, Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary, "tab_media"),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings")
}

class MainActivity : ComponentActivity() {

    private val viewModel: WallpaperViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WallpaperDiagnosticsRecorder.initialize(applicationContext)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainAppScaffold(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkStatus()
    }
}

@Composable
fun MainAppScaffold(viewModel: WallpaperViewModel) {
    val appState by viewModel.appState.collectAsState()
    ProvideAppLocale(languageTag = appState.appLanguage) {
        MainAppScaffoldContent(viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppScaffoldContent(viewModel: WallpaperViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppTab.STUDIO) }
    var selectingMediaForPage by remember { mutableStateOf<Int?>(null) }
    var editingPageIndex by remember { mutableStateOf<Int?>(null) }
    var isSavingMedia by remember { mutableStateOf(false) }
    var isAssigningMedia by remember { mutableStateOf(false) }
    var studioDarkenPreview by remember { mutableStateOf(false) }
    var settingsDebugScrollTrigger by remember { mutableIntStateOf(0) }
    var showFlagsScreen by remember { mutableStateOf(false) }

    val isWallpaperActive by viewModel.isWallpaperActive.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val originals by viewModel.originals.collectAsState()
    val diagnostics by WallpaperDiagnosticsRecorder.state.collectAsState()
    val pageToEdit = editingPageIndex?.let { index ->
        appState.pages.find { it.pageIndex == index }?.let { page ->
            originals.find { it.id == page.originalId }?.let { media -> index to (page to media) }
        }
    }
    if (showFlagsScreen) {
        AdvancedFlagsScreen(
            initialSplitFraction = viewModel.getDefaultEditorPreviewSplitFraction(),
            initialFullPreview = viewModel.getDefaultEditorFullPreview(),
            onDismiss = { showFlagsScreen = false },
            onConfirm = { newSplitFraction, newFullPreview ->
                if (abs(newSplitFraction - WallpaperRepository.DEFAULT_EDITOR_PREVIEW_SPLIT_FRACTION) < 0.005f) {
                    viewModel.resetDefaultEditorPreviewSplitFraction()
                } else {
                    viewModel.setDefaultEditorPreviewSplitFraction(newSplitFraction)
                }
                if (newFullPreview == WallpaperRepository.DEFAULT_EDITOR_FULL_PREVIEW) {
                    viewModel.resetDefaultEditorFullPreview()
                } else {
                    viewModel.setDefaultEditorFullPreview(newFullPreview)
                }
                showFlagsScreen = false
            }
        )
    } else if (pageToEdit != null) {
        val (pageIndex, pageAndMedia) = pageToEdit
        val (page, media) = pageAndMedia
        MediaEditorScreen(
            media = media,
            page = page,
            pageCount = appState.calibration.pageCount,
            isSaving = isSavingMedia,
            onDismiss = { if (!isSavingMedia) editingPageIndex = null },
            onSave = { updatedPage ->
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                isSavingMedia = true
                viewModel.updatePageSettings(pageIndex, updatedPage, screenWidth, screenHeight) { success ->
                    isSavingMedia = false
                    if (success) {
                        editingPageIndex = null
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_save_page_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    } else if (selectingMediaForPage != null) {
        MediaPickerScreen(
            pageIndex = selectingMediaForPage!!,
            viewModel = viewModel,
            isAssigning = isAssigningMedia,
            onBack = { if (!isAssigningMedia) selectingMediaForPage = null },
            onSelectMedia = { media ->
                selectingMediaForPage?.let { pageIndex ->
                    val screenWidth = context.resources.displayMetrics.widthPixels
                    val screenHeight = context.resources.displayMetrics.heightPixels
                    isAssigningMedia = true
                    viewModel.assignOriginalToPage(media.id, pageIndex, screenWidth, screenHeight) { success ->
                        isAssigningMedia = false
                        if (success) {
                            selectingMediaForPage = null
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_assign_media_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            onOpenLibrary = {
                selectingMediaForPage = null
                selectedTab = AppTab.MEDIA
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            when (selectedTab) {
                                AppTab.STUDIO -> {
                                    val active = studioDarkenPreview
                                    Surface(
                                        onClick = { studioDarkenPreview = !studioDarkenPreview },
                                        shape = CircleShape,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                                        },
                                        border = if (active) {
                                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                        } else {
                                            null
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("top_menu_studio_darken_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.LightMode,
                                                contentDescription = stringResource(R.string.top_menu_darken_cd),
                                                tint = if (active) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    Color(0xFFD97706)
                                                },
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Icon(
                                                imageVector = Icons.Filled.DarkMode,
                                                contentDescription = null,
                                                tint = if (active) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                },
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.top_menu_darken_short),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (active) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                },
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                AppTab.SETTINGS -> {
                                    val anyDebugActive = appState.showOffsetDebugOverlay ||
                                        appState.showFrameDebugIndicator ||
                                        appState.showSignalDebugOverlay ||
                                        diagnostics.isRecording
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            onClick = { settingsDebugScrollTrigger++ },
                                            shape = CircleShape,
                                            color = if (anyDebugActive) {
                                                MaterialTheme.colorScheme.tertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("top_menu_settings_debug_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.BugReport,
                                                    contentDescription = stringResource(R.string.top_menu_debug_cd),
                                                    tint = if (anyDebugActive) {
                                                        MaterialTheme.colorScheme.onTertiaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.top_menu_debug_short),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (anyDebugActive) {
                                                        MaterialTheme.colorScheme.onTertiaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSecondaryContainer
                                                    },
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = { showFlagsScreen = true },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("top_menu_settings_flags_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Flag,
                                                    contentDescription = stringResource(R.string.top_menu_flags_cd),
                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.top_menu_flags_short),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                                AppTab.BEHAVIOR, AppTab.MEDIA -> Unit
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        val actionLabel = if (isWallpaperActive) {
                            stringResource(R.string.cd_preview_wallpaper_settings)
                        } else {
                            stringResource(R.string.cd_apply_live_wallpaper)
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (isWallpaperActive) {
                                Color(0xFF10B981).copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable(
                                    onClickLabel = actionLabel,
                                    role = Role.Button
                                ) {
                                    viewModel.applyWallpaperIntent(context)
                                }
                                .testTag("apply_wallpaper_button")
                        ) {
                            Text(
                                text = if (isWallpaperActive) {
                                    stringResource(R.string.status_active)
                                } else {
                                    stringResource(R.string.action_apply)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWallpaperActive) {
                                    Color(0xFF047857)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    AppTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val tabTitle = stringResource(tab.titleRes)
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                selectedTab = tab
                            },
                            icon = {
                                Icon(
                                    if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tabTitle,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tabTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ScreenTransition"
                ) { currentTab ->
                    when (currentTab) {
                        AppTab.STUDIO -> StudioScreen(
                            viewModel = viewModel,
                            onChooseMediaForPage = { pageIdx ->
                                selectingMediaForPage = pageIdx
                            },
                            onEditPage = { pageIndex ->
                                editingPageIndex = pageIndex
                            },
                            showDarkenPreview = studioDarkenPreview
                        )
                        AppTab.BEHAVIOR -> EffectsScreen(viewModel = viewModel)
                        AppTab.MEDIA -> MediaLibraryScreen(viewModel = viewModel)
                        AppTab.SETTINGS -> SettingsScreen(
                            viewModel = viewModel,
                            debugScrollTrigger = settingsDebugScrollTrigger
                        )
                    }
                }
            }
        }
    }
}
