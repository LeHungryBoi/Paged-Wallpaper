package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DarkenTrigger
import com.example.data.model.OffsetEasingStyle
import com.example.data.model.WallpaperAppState
import com.example.ui.WallpaperViewModel
import kotlin.math.roundToInt

@Composable
fun EffectsScreen(
    viewModel: WallpaperViewModel
) {
    val appState by viewModel.appState.collectAsState()
    val isExtraDimHardwareActive by viewModel.isExtraDimDetected.collectAsState()
    val darken = appState.darken

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        MotionBlurSetting(
            enabled = appState.motionBlurEnabled,
            strengthPercent = appState.motionBlurStrengthPercent,
            onEnabledChange = viewModel::toggleMotionBlur,
            onStrengthChange = viewModel::setMotionBlurStrengthPercent
        )
        OffsetEasingSetting(
            enabled = appState.offsetEasingEnabled,
            style = appState.offsetEasingStyle,
            linearZonePercent = appState.offsetEasingLinearZonePercent,
            strength = appState.offsetEasingStrength,
            onEnabledChange = viewModel::toggleOffsetEasing,
            onStyleChange = viewModel::setOffsetEasingStyle,
            onLinearZonePercentChange = viewModel::setOffsetEasingLinearZonePercent,
            onStrengthChange = viewModel::setOffsetEasingStrength
        )
        AnimationFrameRateSetting(
            selectedFrameRate = appState.animationFrameRate,
            onFrameRateSelected = viewModel::setAnimationFrameRate
        )

        // Section: Display Entry Animation
        DisplayEntryAnimationCard(
            appState = appState,
            onEnabledChange = viewModel::toggleResumeFade,
            onDurationChange = viewModel::setEntryDurationMs,
            onZoomEnabledChange = viewModel::toggleEntryZoom,
            onZoomStartScaleChange = viewModel::setEntryZoomStartScale,
            onBlackFadeEnabledChange = viewModel::toggleEntryBlackFade,
            onBlackFadeMaxAlphaChange = viewModel::setEntryBlackFadeMaxAlpha,
            onBlurEnabledChange = viewModel::toggleEntryBlur,
            onBlurStrengthChange = viewModel::setEntryBlurStrength
        )

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

@Composable
private fun DisplayEntryAnimationCard(
    appState: WallpaperAppState,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onZoomEnabledChange: (Boolean) -> Unit,
    onZoomStartScaleChange: (Float) -> Unit,
    onBlackFadeEnabledChange: (Boolean) -> Unit,
    onBlackFadeMaxAlphaChange: (Float) -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onBlurStrengthChange: (Float) -> Unit
) {
    val durationSlider = remember(appState.entryDurationMs) {
        mutableFloatStateOf(appState.entryDurationMs.toFloat())
    }
    val zoomScaleSlider = remember(appState.entryZoomStartScale) {
        mutableFloatStateOf(appState.entryZoomStartScale)
    }
    val fadeAlphaSlider = remember(appState.entryBlackFadeMaxAlpha) {
        mutableFloatStateOf(appState.entryBlackFadeMaxAlpha)
    }
    val blurStrengthSlider = remember(appState.entryBlurStrength) {
        mutableFloatStateOf(appState.entryBlurStrength)
    }

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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Display Entry Animation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Plays on visibility change when returning to the home screen and blends smoothly if interrupted by a swipe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = appState.resumeFadeEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("toggle_resume_fade")
                )
            }

            AnimatedVisibility(visible = appState.resumeFadeEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Duration: ${durationSlider.floatValue.roundToInt()} ms",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = durationSlider.floatValue,
                            onValueChange = { durationSlider.floatValue = it },
                            onValueChangeFinished = {
                                onDurationChange(durationSlider.floatValue.roundToInt())
                            },
                            valueRange = WallpaperAppState.MIN_ENTRY_DURATION_MS.toFloat()..
                                WallpaperAppState.MAX_ENTRY_DURATION_MS.toFloat(),
                            modifier = Modifier.testTag("entry_duration_slider")
                        )
                    }

                    EntryElementSection(
                        title = "Zoom-out settle",
                        description = "Starts zoomed in on the current page when becoming visible and eases back down to 100% scale.",
                        checked = appState.entryZoomEnabled,
                        onCheckedChange = onZoomEnabledChange,
                        switchTestTag = "toggle_entry_zoom"
                    ) {
                        Text(
                            text = "Starting scale: ${(zoomScaleSlider.floatValue * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = zoomScaleSlider.floatValue,
                            onValueChange = { zoomScaleSlider.floatValue = it },
                            onValueChangeFinished = {
                                onZoomStartScaleChange(zoomScaleSlider.floatValue)
                            },
                            valueRange = WallpaperAppState.MIN_ENTRY_ZOOM_START_SCALE..
                                WallpaperAppState.MAX_ENTRY_ZOOM_START_SCALE,
                            modifier = Modifier.testTag("entry_zoom_scale_slider")
                        )
                    }

                    EntryElementSection(
                        title = "Black fade",
                        description = "Fades up from darkness as the wallpaper becomes visible.",
                        checked = appState.entryBlackFadeEnabled,
                        onCheckedChange = onBlackFadeEnabledChange,
                        switchTestTag = "toggle_entry_black_fade"
                    ) {
                        Text(
                            text = "Starting darkness: ${(fadeAlphaSlider.floatValue * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = fadeAlphaSlider.floatValue,
                            onValueChange = { fadeAlphaSlider.floatValue = it },
                            onValueChangeFinished = {
                                onBlackFadeMaxAlphaChange(fadeAlphaSlider.floatValue)
                            },
                            valueRange = 0.1f..1f,
                            modifier = Modifier.testTag("entry_black_fade_slider")
                        )
                    }

                    EntryElementSection(
                        title = "Soft blur reveal",
                        description = "Starts softly out of focus and sharpens as the entry settles.",
                        checked = appState.entryBlurEnabled,
                        onCheckedChange = onBlurEnabledChange,
                        switchTestTag = "toggle_entry_blur"
                    ) {
                        Text(
                            text = "Starting blur mix: ${(blurStrengthSlider.floatValue * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = blurStrengthSlider.floatValue,
                            onValueChange = { blurStrengthSlider.floatValue = it },
                            onValueChangeFinished = {
                                onBlurStrengthChange(blurStrengthSlider.floatValue)
                            },
                            valueRange = 0.1f..1f,
                            modifier = Modifier.testTag("entry_blur_slider")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryElementSection(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String,
    controls: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.testTag(switchTestTag)
                )
            }
            AnimatedVisibility(visible = checked) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    controls()
                }
            }
        }
    }
}
