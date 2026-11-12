package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.PageTransitionSetting
import com.example.data.model.ParallaxMotionSettings
import com.example.transition.TransitionRegistry
import com.example.ui.WallpaperViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class PresetAction {
    DUPLICATE,
    RENAME
}

@Composable
private fun TransitionSelector(
    boundaryIndex: Int,
    itemName: String,
    label: String,
    selectedId: String,
    icon: ImageVector,
    onSelect: (String) -> Unit
) {
    var expanded by remember(boundaryIndex, itemName) { mutableStateOf(false) }
    val selectedTransition = TransitionRegistry.transitions.firstOrNull { it.id == selectedId }

    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("transition_selector_${boundaryIndex}_$itemName")
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(
                        selectedTransition?.displayName ?: selectedId,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )
                }
                Icon(Icons.Default.ExpandMore, contentDescription = "Choose animation")
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TransitionRegistry.transitions.forEach { transition ->
                DropdownMenuItem(
                    text = { Text(transition.displayName) },
                    onClick = {
                        onSelect(transition.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(
                        "transition_option_${boundaryIndex}_${itemName}_${transition.id}"
                    )
                )
            }
        }
    }
}

@Composable
private fun ParallaxMotionControls(
    boundaryIndex: Int,
    itemName: String,
    settings: ParallaxMotionSettings,
    onUpdate: (ParallaxMotionSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Slow image motion",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.slowImageMotionEnabled,
                onCheckedChange = {
                    onUpdate(settings.copy(slowImageMotionEnabled = it))
                },
                modifier = Modifier.testTag("slow_image_motion_${boundaryIndex}_$itemName")
            )
        }
        if (settings.slowImageMotionEnabled) {
            Text(
                "Image speed: ${(settings.imageSpeed * 100).roundToInt()}% of page speed",
                style = MaterialTheme.typography.labelSmall
            )
            Slider(
                value = settings.imageSpeed,
                onValueChange = { onUpdate(settings.copy(imageSpeed = it)) },
                valueRange = 0f..1f,
                modifier = Modifier.testTag("parallax_image_speed_${boundaryIndex}_$itemName")
            )
            Text(
                "Limited by available image reach.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PageTransitionControls(
    boundaryIndex: Int,
    setting: PageTransitionSetting,
    onUpdate: (PageTransitionSetting) -> Unit
) {
    Card(
        modifier = Modifier.width(180.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Page ${boundaryIndex + 1} / ${boundaryIndex + 2}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Separate directions",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = setting.separateDirections,
                    onCheckedChange = { separateDirections ->
                        onUpdate(
                            if (separateDirections) {
                                setting.copy(
                                    separateDirections = true,
                                    leftTransitionId = setting.universalTransitionId,
                                    rightTransitionId = setting.universalTransitionId,
                                    leftParallaxMotion = setting.universalParallaxMotion,
                                    rightParallaxMotion = setting.universalParallaxMotion
                                )
                            } else {
                                setting.copy(separateDirections = false)
                            }
                        )
                    },
                    modifier = Modifier.testTag("separate_transition_directions_$boundaryIndex")
                )
            }

            if (setting.separateDirections) {
                TransitionSelector(
                    boundaryIndex = boundaryIndex,
                    itemName = "right",
                    label = "Next page",
                    selectedId = setting.rightTransitionId,
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onSelect = { onUpdate(setting.copy(rightTransitionId = it)) }
                )
                if (TransitionRegistry.get(setting.rightTransitionId).supportsParallax) {
                    ParallaxMotionControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "right",
                        settings = setting.rightParallaxMotion,
                        onUpdate = { onUpdate(setting.copy(rightParallaxMotion = it)) }
                    )
                }
                TransitionSelector(
                    boundaryIndex = boundaryIndex,
                    itemName = "left",
                    label = "Previous page",
                    selectedId = setting.leftTransitionId,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onSelect = { onUpdate(setting.copy(leftTransitionId = it)) }
                )
                if (TransitionRegistry.get(setting.leftTransitionId).supportsParallax) {
                    ParallaxMotionControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "left",
                        settings = setting.leftParallaxMotion,
                        onUpdate = { onUpdate(setting.copy(leftParallaxMotion = it)) }
                    )
                }
            } else {
                TransitionSelector(
                    boundaryIndex = boundaryIndex,
                    itemName = "universal",
                    label = "Both directions",
                    selectedId = setting.universalTransitionId,
                    icon = Icons.Default.SwapHoriz,
                    onSelect = { onUpdate(setting.copy(universalTransitionId = it)) }
                )
                if (TransitionRegistry.get(setting.universalTransitionId).supportsParallax) {
                    ParallaxMotionControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "universal",
                        settings = setting.universalParallaxMotion,
                        onUpdate = { onUpdate(setting.copy(universalParallaxMotion = it)) }
                    )
                }
            }
        }
    }
}

@Composable
fun StudioScreen(
    viewModel: WallpaperViewModel,
    onChooseMediaForPage: (Int) -> Unit,
    onEditPage: (Int) -> Unit,
    onNavigateToCalibration: () -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    val originals by viewModel.originals.collectAsState()

    var presetAction by remember { mutableStateOf<PresetAction?>(null) }
    var presetActionId by remember { mutableStateOf<String?>(null) }
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
                        var menuExpanded by remember(preset.id) { mutableStateOf(false) }
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
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("preset_menu_${preset.id}")
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "${preset.name} actions")
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Duplicate") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            presetActionId = preset.id
                                            presetAction = PresetAction.DUPLICATE
                                            presetName = "${preset.name} copy".take(40)
                                            menuExpanded = false
                                        },
                                        modifier = Modifier.testTag("duplicate_preset_${preset.id}")
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            presetActionId = preset.id
                                            presetAction = PresetAction.RENAME
                                            presetName = preset.name
                                            menuExpanded = false
                                        },
                                        modifier = Modifier.testTag("rename_preset_${preset.id}")
                                    )
                                    if (appState.presets.size > 1) {
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                presetToDelete = preset.id to preset.name
                                                menuExpanded = false
                                            },
                                            modifier = Modifier.testTag("delete_preset_${preset.id}")
                                        )
                                    }
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

            Column(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    appState.pages.forEachIndexed { index, page ->
                        val isHome = index == appState.calibration.homePageIndex
                        val bmp = pageBitmaps[index]
                        val hasEditableOriginal = originals.any { it.id == page.originalId }

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
                                        .clickable {
                                            if (hasEditableOriginal) onEditPage(index)
                                            else onChooseMediaForPage(index)
                                        }
                                        .testTag("page_preview_$index"),
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
                                    } else {
                                        Surface(
                                            onClick = { viewModel.setHomePage(index) },
                                            shape = RoundedCornerShape(bottomEnd = 10.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .testTag("set_home_${index}_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Home,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    "MAKE HOME",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
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

                                    if (hasEditableOriginal) {
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .clickable { onEditPage(index) }
                                                .testTag("edit_page_${index}_preview_action"),
                                            color = Color.Black.copy(alpha = 0.72f),
                                            shape = RoundedCornerShape(
                                                bottomStart = 14.dp,
                                                bottomEnd = 14.dp
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = Color.White
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "Edit page settings",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White,
                                                    maxLines = 1
                                                )
                                            }
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

                                Button(
                                    onClick = { onChooseMediaForPage(index) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("replace_page_${index}_button"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Choose image", style = MaterialTheme.typography.labelLarge)
                                }

                            }

                        }
                    }
                }

                if (appState.transitions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Spacer(modifier = Modifier.width(83.dp))
                        appState.transitions.forEachIndexed { boundaryIndex, setting ->
                            PageTransitionControls(
                                boundaryIndex = boundaryIndex,
                                setting = setting,
                                onUpdate = { viewModel.updatePageTransition(boundaryIndex, it) }
                            )
                        }
                    }
                }
            }
        }

        if (presetAction != null && presetActionId != null) {
            AlertDialog(
                onDismissRequest = { presetAction = null },
                title = {
                    Text(
                        if (presetAction == PresetAction.DUPLICATE) {
                            "Duplicate preset"
                        } else {
                            "Rename preset"
                        }
                    )
                },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it.take(40) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.testTag("preset_name_field")
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val succeeded = presetActionId?.let { id ->
                                when (presetAction) {
                                    PresetAction.DUPLICATE ->
                                        viewModel.duplicatePreset(id, presetName) != null
                                    PresetAction.RENAME -> viewModel.renamePreset(id, presetName)
                                    null -> false
                                }
                            } ?: false
                            if (succeeded) presetAction = null
                        },
                        enabled = presetName.isNotBlank(),
                        modifier = Modifier.testTag("confirm_preset_action")
                    ) {
                        Text(if (presetAction == PresetAction.DUPLICATE) "Duplicate" else "Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { presetAction = null }) { Text("Cancel") }
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
