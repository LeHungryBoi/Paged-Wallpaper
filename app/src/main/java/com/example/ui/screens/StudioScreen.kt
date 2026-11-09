package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.transition.TransitionRegistry
import com.example.ui.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StudioScreen(
    viewModel: WallpaperViewModel,
    onChooseMediaForPage: (Int) -> Unit,
    onEditPage: (Int) -> Unit,
    onNavigateToCalibration: () -> Unit
) {
    val context = LocalContext.current
    val appState by viewModel.appState.collectAsState()
    val originals by viewModel.originals.collectAsState()
    val isWallpaperActive by viewModel.isWallpaperActive.collectAsState()

    var testSliderOffset by remember { mutableFloatStateOf(0.5f) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetToDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    val pageBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }

    // Reload page thumbnails
    LaunchedEffect(appState.pages, appState.activePresetId) {
        val loadedBitmaps = withContext(Dispatchers.IO) {
            appState.pages.mapNotNull { page ->
                viewModel.loadPagePreviewBitmap(page.pageIndex, 540, 960)?.let {
                    page.pageIndex to it
                }
            }
        }
        pageBitmaps.clear()
        loadedBitmaps.forEach { (pageIndex, bitmap) ->
            pageBitmaps[pageIndex] = bitmap
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    appState.presets.forEach { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.FilterChip(
                                selected = preset.id == appState.activePresetId,
                                onClick = {
                                    if (preset.id != appState.activePresetId) {
                                        viewModel.selectPreset(preset.id)
                                    }
                                },
                                label = { Text(preset.name) },
                                modifier = Modifier.testTag("preset_${preset.id}")
                            )
                            if (appState.presets.size > 1) {
                                IconButton(
                                    onClick = { presetToDelete = preset.id to preset.name },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("delete_preset_${preset.id}")
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete ${preset.name} preset",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "Changes to pages and effects are saved automatically to the selected preset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        presetName = ""
                        showSavePresetDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().testTag("save_preset_button")
                ) {
                    Text("Save a copy as a new preset")
                }
            }
        }

        // Hero Card: Live Wallpaper System Status & 1-Click Set
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wallpaper_status_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (isWallpaperActive) Icons.Filled.CheckCircle else Icons.Filled.Wallpaper,
                                contentDescription = null,
                                tint = if (isWallpaperActive) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = if (isWallpaperActive) "Active Live Wallpaper" else "Wallpaper Not Applied",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        // Status pill
                        Surface(
                            shape = CircleShape,
                            color = if (isWallpaperActive) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isWallpaperActive) "RUNNING" else "STANDBY",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWallpaperActive) Color(0xFF047857) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        text = if (isWallpaperActive) {
                            "PaperPages is rendering your multi-page wallpapers seamlessly in the background as you swipe."
                        } else {
                            "Tap below to apply PaperPages as your Android dynamic wallpaper."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )

                    Button(
                        onClick = { viewModel.applyWallpaperIntent(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apply_wallpaper_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Filled.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isWallpaperActive) "Re-Apply / Preview Settings" else "Apply as Live Wallpaper",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Section: Multi-Screen Carousel
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Launcher Pages (${appState.calibration.pageCount} Screens)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap a screen to choose its wallpaper",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = onNavigateToCalibration,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("quick_calibrate_button")
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Calibrate", style = MaterialTheme.typography.labelMedium)
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(appState.pages) { index, page ->
                    val isHome = index == appState.calibration.homePageIndex
                    val bmp = pageBitmaps[index]

                    Card(
                        modifier = Modifier
                            .width(180.dp)
                            .testTag("page_card_$index"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        border = if (isHome) {
                            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else null
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Phone Screen Frame Thumbnail
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(9f / 17f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.Black)
                                    .clickable { onChooseMediaForPage(index) }
                                    .testTag("choose_page_preview_$index"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Page ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = if (page.isBlank) {
                                            "Empty page"
                                        } else if (page.originalId != null) {
                                            "Preview unavailable"
                                        } else {
                                            "Built-in wallpaper"
                                        },
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Home badge
                                if (isHome) {
                                    Surface(
                                        shape = RoundedCornerShape(bottomEnd = 10.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                                            Text("HOME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // Dark icon badge
                                if (page.isAlreadyDark) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.DarkMode,
                                            contentDescription = "Already Dark",
                                            tint = Color.Yellow,
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(14.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Screen ${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(
                                    onClick = { onChooseMediaForPage(index) },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.testTag("replace_page_${index}_button")
                                ) {
                                    Text("Choose image", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Text(
                                text = if (originals.any { it.id == page.originalId }) {
                                    "Assigned from media library"
                                } else if (page.isBlank) {
                                    "Empty page"
                                } else {
                                    "Built-in wallpaper"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(
                                text = if (isHome) "Home Screen (Center)" else "Offset: ${String.format("%.2f", appState.calibration.customOffsets.getOrNull(index) ?: 0f)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (page.originalId != null) {
                                Button(
                                    onClick = { onEditPage(index) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("edit_page_${index}_button"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit page settings", style = MaterialTheme.typography.labelLarge)
                                }
                            }

                            if (!isHome) {
                                OutlinedButton(
                                    onClick = { viewModel.setHomePage(index) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.align(Alignment.End).testTag("set_home_${index}_button")
                                ) {
                                    Icon(Icons.Outlined.Home, contentDescription = "Set as Home", modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                    }
                }
            }
        }

        // Section: In-App Offset Transition Simulator
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_offset_simulator_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Interactive Transition Simulator",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Simulate launcher swipe (${TransitionRegistry.get(appState.transitionType).displayName})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Offset: ${String.format("%.2f", testSliderOffset)}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Drag slider
                Slider(
                    value = testSliderOffset,
                    onValueChange = { testSliderOffset = it },
                    valueRange = 0.0f..1.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offset_simulator_slider")
                )

                // Quick jump chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in 0 until appState.calibration.pageCount) {
                        val anchor = appState.calibration.customOffsets.getOrNull(i) ?: 0f
                        SuggestionChip(
                            onClick = { testSliderOffset = anchor },
                            label = { Text("P${i + 1} (${String.format("%.2f", anchor)})", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        if (showSavePresetDialog) {
            AlertDialog(
                onDismissRequest = { showSavePresetDialog = false },
                title = { Text("Create a new preset") },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it.take(40) },
                        label = { Text("Preset name") },
                        singleLine = true,
                        modifier = Modifier.testTag("preset_name_field")
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (viewModel.saveCurrentPreset(presetName) != null) {
                                showSavePresetDialog = false
                            }
                        },
                        enabled = presetName.isNotBlank(),
                        modifier = Modifier.testTag("confirm_save_preset")
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") }
                }
            )
        }
        presetToDelete?.let { (presetId, presetNameToDelete) ->
            AlertDialog(
                onDismissRequest = { presetToDelete = null },
                title = { Text("Delete preset?") },
                text = {
                    Text(
                        if (presetId == appState.activePresetId) {
                            "Delete \"$presetNameToDelete\"? Another preset will become active. This cannot be undone."
                        } else {
                            "Delete \"$presetNameToDelete\"? This cannot be undone."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deletePreset(presetId)
                            presetToDelete = null
                        }
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { presetToDelete = null }) { Text("Cancel") }
                }
            )
        }
    }
}
