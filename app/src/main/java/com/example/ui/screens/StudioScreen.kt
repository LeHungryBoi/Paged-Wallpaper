package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.PageTransitionSetting
import com.example.data.model.ParallaxMotionSettings
import com.example.data.model.RevealMotionRates
import com.example.transition.TransitionRegistry
import com.example.ui.WallpaperViewModel
import com.example.ui.localizedTransitionName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private enum class PresetAction {
    CREATE,
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
                        localizedTransitionName(selectedId),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )
                }
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.studio_choose_animation_cd)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TransitionRegistry.transitions.forEach { transition ->
                DropdownMenuItem(
                    text = { Text(localizedTransitionName(transition.id)) },
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
                stringResource(R.string.studio_slow_image_motion),
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
        Text(
            stringResource(
                R.string.studio_outgoing_motion_rate,
                (settings.outgoingMotionRate * 100).roundToInt()
            ),
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = settings.outgoingMotionRate,
            onValueChange = { onUpdate(settings.copy(outgoingMotionRate = it)) },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("parallax_outgoing_motion_rate_${boundaryIndex}_$itemName")
        )
        Text(
            stringResource(
                R.string.studio_incoming_motion_rate,
                (settings.incomingMotionRate * 100).roundToInt()
            ),
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = settings.incomingMotionRate,
            onValueChange = { onUpdate(settings.copy(incomingMotionRate = it)) },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("parallax_incoming_motion_rate_${boundaryIndex}_$itemName")
        )
        Text(
            stringResource(R.string.studio_parallax_reach_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RevealMotionRateControls(
    boundaryIndex: Int,
    itemName: String,
    rates: RevealMotionRates,
    onUpdate: (RevealMotionRates) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            stringResource(
                R.string.studio_reveal_outgoing_rate,
                (rates.outgoingRate * 100).roundToInt()
            ),
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = rates.outgoingRate,
            onValueChange = { onUpdate(rates.copy(outgoingRate = it)) },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("reveal_outgoing_motion_rate_${boundaryIndex}_$itemName")
        )
        Text(
            stringResource(
                R.string.studio_reveal_incoming_rate,
                (rates.incomingRate * 100).roundToInt()
            ),
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = rates.incomingRate,
            onValueChange = { onUpdate(rates.copy(incomingRate = it)) },
            valueRange = 0f..1f,
            modifier = Modifier.testTag("reveal_incoming_motion_rate_${boundaryIndex}_$itemName")
        )
        Text(
            stringResource(R.string.studio_parallax_reach_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                text = stringResource(
                    R.string.studio_boundary_title,
                    boundaryIndex + 1,
                    boundaryIndex + 2
                ),
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
                    text = stringResource(R.string.studio_separate_directions),
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
                                    rightParallaxMotion = setting.universalParallaxMotion,
                                    leftRevealMotionRates = setting.universalRevealMotionRates,
                                    rightRevealMotionRates = setting.universalRevealMotionRates
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
                    label = stringResource(R.string.studio_next_page),
                    selectedId = setting.rightTransitionId,
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onSelect = { onUpdate(setting.copy(rightTransitionId = it)) }
                )
                if (TransitionRegistry.get(setting.rightTransitionId).supportsParallax &&
                    !TransitionRegistry.get(setting.rightTransitionId).supportsRevealImageMotion
                ) {
                    ParallaxMotionControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "right",
                        settings = setting.rightParallaxMotion,
                        onUpdate = { onUpdate(setting.copy(rightParallaxMotion = it)) }
                    )
                }
                if (TransitionRegistry.get(setting.rightTransitionId).supportsRevealImageMotion) {
                    RevealMotionRateControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "right",
                        rates = setting.rightRevealMotionRates,
                        onUpdate = { onUpdate(setting.copy(rightRevealMotionRates = it)) }
                    )
                }
                TransitionSelector(
                    boundaryIndex = boundaryIndex,
                    itemName = "left",
                    label = stringResource(R.string.studio_previous_page),
                    selectedId = setting.leftTransitionId,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onSelect = { onUpdate(setting.copy(leftTransitionId = it)) }
                )
                if (TransitionRegistry.get(setting.leftTransitionId).supportsParallax &&
                    !TransitionRegistry.get(setting.leftTransitionId).supportsRevealImageMotion
                ) {
                    ParallaxMotionControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "left",
                        settings = setting.leftParallaxMotion,
                        onUpdate = { onUpdate(setting.copy(leftParallaxMotion = it)) }
                    )
                }
                if (TransitionRegistry.get(setting.leftTransitionId).supportsRevealImageMotion) {
                    RevealMotionRateControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "left",
                        rates = setting.leftRevealMotionRates,
                        onUpdate = { onUpdate(setting.copy(leftRevealMotionRates = it)) }
                    )
                }
            } else {
                TransitionSelector(
                    boundaryIndex = boundaryIndex,
                    itemName = "universal",
                    label = stringResource(R.string.studio_both_directions),
                    selectedId = setting.universalTransitionId,
                    icon = Icons.Default.SwapHoriz,
                    onSelect = { onUpdate(setting.copy(universalTransitionId = it)) }
                )
                if (TransitionRegistry.get(setting.universalTransitionId).supportsParallax &&
                    !TransitionRegistry.get(setting.universalTransitionId).supportsRevealImageMotion
                ) {
                    ParallaxMotionControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "universal",
                        settings = setting.universalParallaxMotion,
                        onUpdate = { onUpdate(setting.copy(universalParallaxMotion = it)) }
                    )
                }
                if (TransitionRegistry.get(setting.universalTransitionId).supportsRevealImageMotion) {
                    RevealMotionRateControls(
                        boundaryIndex = boundaryIndex,
                        itemName = "universal",
                        rates = setting.universalRevealMotionRates,
                        onUpdate = {
                            onUpdate(setting.copy(universalRevealMotionRates = it))
                        }
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
    onEditPage: (Int) -> Unit
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.studio_presets_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val defaultNewPresetName = stringResource(
                        R.string.studio_preset_default_name,
                        appState.presets.size + 1
                    )
                    FilledTonalButton(
                        onClick = {
                            presetActionId = null
                            presetName = defaultNewPresetName.take(40)
                            presetAction = PresetAction.CREATE
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("create_preset_button")
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.studio_new_preset),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    appState.presets.forEach { preset ->
                        var menuExpanded by remember(preset.id) { mutableStateOf(false) }
                        val copySuffix = stringResource(R.string.studio_preset_copy_suffix, preset.name)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
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
                                        .size(48.dp)
                                        .testTag("preset_menu_${preset.id}")
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(
                                            R.string.studio_preset_actions_cd,
                                            preset.name
                                        )
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.studio_preset_duplicate)) },
                                        leadingIcon = {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                                        },
                                        onClick = {
                                            presetActionId = preset.id
                                            presetAction = PresetAction.DUPLICATE
                                            presetName = copySuffix.take(40)
                                            menuExpanded = false
                                        },
                                        modifier = Modifier.testTag("duplicate_preset_${preset.id}")
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.studio_preset_rename)) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                        },
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
                                            text = { Text(stringResource(R.string.action_delete)) },
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
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
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
                                        contentDescription = stringResource(
                                            R.string.studio_page_title,
                                            index + 1
                                        ),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = if (page.isBlank) {
                                            stringResource(R.string.studio_source_empty)
                                        } else if (page.originalId != null) {
                                            stringResource(R.string.studio_preview_unavailable)
                                        } else {
                                            stringResource(R.string.studio_source_builtin)
                                        },
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

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
                                            Icon(
                                                Icons.Filled.Home,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                stringResource(R.string.studio_home_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
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
                                                stringResource(R.string.studio_make_home),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

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
                                            contentDescription = stringResource(R.string.studio_already_dark_cd),
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
                                                stringResource(R.string.studio_edit_page_settings),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = stringResource(R.string.studio_screen_title, index + 1),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = if (originals.any { it.id == page.originalId }) {
                                    stringResource(R.string.studio_source_library)
                                } else if (page.isBlank) {
                                    stringResource(R.string.studio_source_empty)
                                } else {
                                    stringResource(R.string.studio_source_builtin)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(
                                text = if (isHome) {
                                    stringResource(R.string.studio_home_center)
                                } else {
                                    stringResource(
                                        R.string.studio_offset_value,
                                        String.format(
                                            Locale.US,
                                            "%.2f",
                                            appState.calibration.customOffsets.getOrNull(index) ?: 0f
                                        )
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Button(
                                onClick = { onChooseMediaForPage(index) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("replace_page_${index}_button"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.studio_choose_image),
                                    style = MaterialTheme.typography.labelLarge
                                )
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

        presetAction?.let { action ->
            AlertDialog(
                onDismissRequest = { presetAction = null },
                title = {
                    Text(
                        when (action) {
                            PresetAction.CREATE -> stringResource(R.string.studio_preset_new_title)
                            PresetAction.DUPLICATE -> stringResource(R.string.studio_preset_duplicate_title)
                            PresetAction.RENAME -> stringResource(R.string.studio_preset_rename_title)
                        }
                    )
                },
                text = {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it.take(40) },
                        label = { Text(stringResource(R.string.studio_preset_name_label)) },
                        singleLine = true,
                        modifier = Modifier.testTag("preset_name_field")
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val succeeded = when (action) {
                                PresetAction.CREATE -> viewModel.createDefaultPreset(presetName) != null
                                PresetAction.DUPLICATE -> presetActionId?.let { id ->
                                    viewModel.duplicatePreset(id, presetName) != null
                                } ?: false
                                PresetAction.RENAME -> presetActionId?.let { id ->
                                    viewModel.renamePreset(id, presetName)
                                } ?: false
                            }
                            if (succeeded) presetAction = null
                        },
                        enabled = presetName.isNotBlank(),
                        modifier = Modifier.testTag("confirm_preset_action")
                    ) {
                        Text(
                            when (action) {
                                PresetAction.CREATE -> stringResource(R.string.studio_preset_create)
                                PresetAction.DUPLICATE -> stringResource(R.string.studio_preset_duplicate)
                                PresetAction.RENAME -> stringResource(R.string.studio_preset_rename)
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { presetAction = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        presetToDelete?.let { (presetId, presetNameToDelete) ->
            AlertDialog(
                onDismissRequest = { presetToDelete = null },
                title = { Text(stringResource(R.string.studio_preset_delete_title)) },
                text = {
                    Text(
                        if (presetId == appState.activePresetId) {
                            stringResource(
                                R.string.studio_preset_delete_active_message,
                                presetNameToDelete
                            )
                        } else {
                            stringResource(
                                R.string.studio_preset_delete_message,
                                presetNameToDelete
                            )
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deletePreset(presetId)
                            presetToDelete = null
                        }
                    ) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { presetToDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}
