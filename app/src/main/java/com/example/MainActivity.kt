package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.OriginalMedia
import com.example.ui.WallpaperViewModel
import com.example.ui.screens.BackupScreen
import com.example.ui.screens.CalibrationScreen
import com.example.ui.screens.EffectsScreen
import com.example.ui.screens.MediaLibraryScreen
import com.example.ui.screens.MediaEditorScreen
import com.example.ui.screens.MediaPickerScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.MyApplicationTheme

enum class AppTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    STUDIO("Studio", Icons.Filled.Wallpaper, Icons.Outlined.Wallpaper, "tab_studio"),
    CALIBRATE("Calibrate", Icons.Filled.SwapHoriz, Icons.Outlined.SwapHoriz, "tab_calibrate"),
    EFFECTS("Effects", Icons.Filled.Layers, Icons.Outlined.Layers, "tab_effects"),
    MEDIA("Library", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary, "tab_media"),
    BACKUP("Backup", Icons.Filled.Archive, Icons.Outlined.Archive, "tab_backup")
}

class MainActivity : ComponentActivity() {

    private val viewModel: WallpaperViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(viewModel: WallpaperViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppTab.STUDIO) }
    var selectingMediaForPage by remember { mutableStateOf<Int?>(null) }
    var editingMedia by remember { mutableStateOf<OriginalMedia?>(null) }
    var isSavingMedia by remember { mutableStateOf(false) }
    var isAssigningMedia by remember { mutableStateOf(false) }

    val isWallpaperActive by viewModel.isWallpaperActive.collectAsState()
    if (editingMedia != null) {
        MediaEditorScreen(
            media = editingMedia!!,
            isSaving = isSavingMedia,
            onDismiss = { if (!isSavingMedia) editingMedia = null },
            onSave = { updatedMedia ->
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                isSavingMedia = true
                viewModel.updateOriginalMedia(updatedMedia, screenWidth, screenHeight) { success ->
                    isSavingMedia = false
                    if (success) {
                        editingMedia = null
                    } else {
                        Toast.makeText(context, "Could not save media changes.", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, "Could not assign this media.", Toast.LENGTH_SHORT).show()
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
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "PaperPages",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = if (isWallpaperActive) androidx.compose.ui.graphics.Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                text = if (isWallpaperActive) "Active" else "Live",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWallpaperActive) androidx.compose.ui.graphics.Color(0xFF047857) else MaterialTheme.colorScheme.primary
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
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                selectedTab = tab
                            },
                            icon = {
                                Icon(
                                    if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
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
                            onNavigateToCalibration = {
                                selectedTab = AppTab.CALIBRATE
                            }
                        )
                        AppTab.CALIBRATE -> CalibrationScreen(viewModel = viewModel)
                        AppTab.EFFECTS -> EffectsScreen(viewModel = viewModel)
                        AppTab.MEDIA -> MediaLibraryScreen(
                            viewModel = viewModel,
                            onEditMedia = { media ->
                                editingMedia = media
                            }
                        )
                        AppTab.BACKUP -> BackupScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
