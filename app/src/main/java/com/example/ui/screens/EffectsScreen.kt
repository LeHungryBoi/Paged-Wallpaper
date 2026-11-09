package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DarkenTrigger
import com.example.data.model.PageTransitionSetting
import com.example.transition.TransitionRegistry
import com.example.ui.WallpaperViewModel

@Composable
private fun TransitionChoiceList(
    title: String,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TransitionRegistry.transitions.forEach { renderer ->
            val isSelected = selectedId.equals(renderer.id, ignoreCase = true)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(renderer.id) }
                    .testTag("transition_option_${renderer.id}"),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = renderer.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = renderer.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RadioButton(selected = isSelected, onClick = { onSelect(renderer.id) })
                }
            }
        }
    }
}

@Composable
fun EffectsScreen(
    viewModel: WallpaperViewModel
) {
    val appState by viewModel.appState.collectAsState()
    val isExtraDimHardwareActive by viewModel.isExtraDimDetected.collectAsState()
    val darken = appState.darken
    var selectedBoundary by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Section: Transition Strategy
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Page Transition Effects",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Transitions are configured per adjacent page pair and saved in the current preset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val edgeCount = appState.pages.size - 1
                if (edgeCount > 0) {
                    val boundaryIndex = selectedBoundary.coerceIn(0, edgeCount - 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(edgeCount) { index ->
                            FilterChip(
                                selected = boundaryIndex == index,
                                onClick = { selectedBoundary = index },
                                label = { Text("${index + 1} ↔ ${index + 2}") },
                                modifier = Modifier.testTag("transition_boundary_$index")
                            )
                        }
                    }
                    val setting = appState.transitions.getOrNull(boundaryIndex)
                        ?: PageTransitionSetting(appState.transitionType, appState.transitionType)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Different animation per direction", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Off uses the same effect both ways.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = setting.separateDirections,
                            onCheckedChange = {
                                viewModel.updatePageTransition(
                                    boundaryIndex,
                                    setting.copy(separateDirections = it)
                                )
                            },
                            modifier = Modifier.testTag("separate_transition_directions")
                        )
                    }
                    TransitionChoiceList(
                        title = if (setting.separateDirections) "Forward: ${boundaryIndex + 1} → ${boundaryIndex + 2}" else "Animation",
                        selectedId = setting.forwardTransitionId,
                        onSelect = { id ->
                            val updated = setting.copy(
                                forwardTransitionId = id,
                                reverseTransitionId = if (setting.separateDirections) {
                                    setting.reverseTransitionId
                                } else {
                                    id
                                }
                            )
                            viewModel.updatePageTransition(boundaryIndex, updated)
                        }
                    )
                    if (setting.separateDirections) {
                        TransitionChoiceList(
                            title = "Reverse: ${boundaryIndex + 2} → ${boundaryIndex + 1}",
                            selectedId = setting.reverseTransitionId,
                            onSelect = { id ->
                                viewModel.updatePageTransition(
                                    boundaryIndex,
                                    setting.copy(reverseTransitionId = id)
                                )
                            }
                        )
                    }
                }
            }
        }

        // Section: Resume Animation Stability
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("resume_transition_card"),
            shape = RoundedCornerShape(20.dp)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smooth Resume Fade",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Gently cross-fades the wallpaper (250ms) when exiting full-screen apps like YouTube to eliminate abrupt visual pops.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = appState.resumeFadeEnabled,
                        onCheckedChange = { viewModel.toggleResumeFade(it) },
                        modifier = Modifier.testTag("toggle_resume_fade")
                    )
                }
            }

        }

        // Section: Darken & Android Extra Dim
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("darken_settings_card"),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Extra Dim & Darken Controls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // System Extra Dim indicator
                    Surface(
                        shape = CircleShape,
                        color = if (isExtraDimHardwareActive) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (isExtraDimHardwareActive) Color(0xFF10B981) else Color.Gray,
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (isExtraDimHardwareActive) "Extra Dim ON" else "Extra Dim OFF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isExtraDimHardwareActive) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "Trigger dynamic wallpaper darkening when conditions are met. Seamlessly integrates with Android's Quick Settings Extra Dim (Reduce Bright Colors) toggle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Trigger Mode Selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Darken Trigger Mode:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    DarkenTrigger.values().forEach { mode ->
                        val isSelected = darken.triggerMode == mode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.updateDarkenConfig(darken.copy(triggerMode = mode)) }
                                .testTag("darken_trigger_${mode.name}"),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.updateDarkenConfig(darken.copy(triggerMode = mode)) }
                                )
                            }
                        }
                    }
                }

                // Scheduled Time controls if SCHEDULED mode is selected
                AnimatedVisibility(visible = darken.triggerMode == DarkenTrigger.SCHEDULED) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = "Night Schedule Active Hours",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Starts at ${String.format("%02d:00", darken.scheduleStartHour)} • Ends at ${String.format("%02d:00", darken.scheduleEndHour)}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(18 to "6 PM", 20 to "8 PM", 22 to "10 PM").forEach { (hour, label) ->
                                FilterChip(
                                    selected = darken.scheduleStartHour == hour,
                                    onClick = { viewModel.updateDarkenConfig(darken.copy(scheduleStartHour = hour)) },
                                    label = { Text("From $label") }
                                )
                            }
                        }
                    }
                }

                // Dim Intensity Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dim Overlay Intensity",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(darken.dimFactor * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = darken.dimFactor,
                        onValueChange = { viewModel.updateDarkenConfig(darken.copy(dimFactor = it)) },
                        valueRange = 0.15f..0.85f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dim_factor_slider")
                    )
                }

                // Automatic Image Darkness Detection
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smart Dark Wallpaper Detection",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Wallpapers automatically analyzed as dark or manually flagged will not be over-darkened",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = darken.autoDetectDark,
                            onCheckedChange = { viewModel.updateDarkenConfig(darken.copy(autoDetectDark = it)) },
                            modifier = Modifier.testTag("toggle_auto_detect_dark")
                        )
                    }
                }
            }

        }
    }
}
