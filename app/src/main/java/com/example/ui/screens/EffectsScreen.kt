package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.darken.ExtraDimDetector
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTriggerMode
import com.example.data.model.OffsetEasingStyle
import com.example.data.model.WallpaperAppState
import com.example.ui.WallpaperViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private enum class ScheduleTimeField {
    START,
    END
}

@Composable
fun EffectsScreen(
    viewModel: WallpaperViewModel
) {
    val appState by viewModel.appState.collectAsState()
    val isSystemDarkMode by viewModel.isSystemDarkMode.collectAsState()
    val isInSystemDarkTheme = isSystemInDarkTheme()
    LaunchedEffect(isInSystemDarkTheme) {
        viewModel.refreshSystemDarkMode(isInSystemDarkTheme)
    }
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

        // Section: Darken Controls
        DarkenControlsCard(
            darken = darken,
            isSystemDarkMode = isSystemDarkMode,
            onUpdateDarkenConfig = viewModel::updateDarkenConfig
        )
    }
}

@Composable
private fun DarkenControlsCard(
    darken: DarkenConfig,
    isSystemDarkMode: Boolean,
    onUpdateDarkenConfig: (DarkenConfig) -> Unit
) {
    var scheduleTimeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(darken.useSchedule) {
        if (!darken.useSchedule) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            scheduleTimeTick++
        }
    }
    val isScheduleActiveNow = remember(darken, scheduleTimeTick) {
        darken.useSchedule && ExtraDimDetector.isScheduledActive(darken)
    }
    val isDarkenActiveNow = darken.enabled && when (darken.triggerMode) {
        DarkenTriggerMode.SCHEDULE_ONLY -> isScheduleActiveNow
        DarkenTriggerMode.DARK_MODE_ONLY -> isSystemDarkMode
        DarkenTriggerMode.BOTH_OR -> isSystemDarkMode || isScheduleActiveNow
    }

    var editingTimeField by remember { mutableStateOf<ScheduleTimeField?>(null) }

    val statusText = when {
        isDarkenActiveNow -> "Darkened"
        darken.enabled -> "Standby"
        else -> "Off"
    }
    val statusBadgeColor = when {
        isDarkenActiveNow -> Color(0xFF10B981).copy(alpha = 0.2f)
        darken.enabled -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusDotColor = when {
        isDarkenActiveNow -> Color(0xFF10B981)
        darken.enabled -> MaterialTheme.colorScheme.primary
        else -> Color.Gray
    }
    val statusTextColor = when {
        isDarkenActiveNow -> Color(0xFF047857)
        darken.enabled -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

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
                    Icon(
                        Icons.Default.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Darken Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = statusBadgeColor,
                    modifier = Modifier.testTag("darken_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusDotColor, CircleShape)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusTextColor
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automatic Darkening",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Automatically switch to darkened wallpapers by schedule, System Dark Mode, or both, with a smooth 550 ms fade on your home screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = darken.enabled,
                    onCheckedChange = { enabled ->
                        onUpdateDarkenConfig(darken.copy(enabled = enabled))
                    },
                    modifier = Modifier.testTag("toggle_darken_enabled")
                )
            }

            AnimatedVisibility(visible = darken.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Trigger Condition",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        DarkenTriggerMode.values().forEach { mode ->
                            val selected = darken.triggerMode == mode
                            val modeTag = when (mode) {
                                DarkenTriggerMode.SCHEDULE_ONLY -> "darken_mode_schedule_only"
                                DarkenTriggerMode.DARK_MODE_ONLY -> "darken_mode_dark_mode_only"
                                DarkenTriggerMode.BOTH_OR -> "darken_mode_both_or"
                            }
                            val modeIcon = when (mode) {
                                DarkenTriggerMode.SCHEDULE_ONLY -> Icons.Default.Schedule
                                DarkenTriggerMode.DARK_MODE_ONLY -> Icons.Default.Brightness4
                                DarkenTriggerMode.BOTH_OR -> Icons.Default.AutoAwesome
                            }
                            val modeSubtitle = when (mode) {
                                DarkenTriggerMode.SCHEDULE_ONLY ->
                                    "Daily ${formatScheduleTime(darken.scheduleStartHour, darken.scheduleStartMinute)} – ${formatScheduleTime(darken.scheduleEndHour, darken.scheduleEndMinute)}"
                                DarkenTriggerMode.DARK_MODE_ONLY ->
                                    if (isSystemDarkMode) {
                                        "Dark Mode is currently on"
                                    } else {
                                        mode.description
                                    }
                                DarkenTriggerMode.BOTH_OR ->
                                    "Triggers if Dark Mode is on or inside ${formatScheduleTime(darken.scheduleStartHour, darken.scheduleStartMinute)} – ${formatScheduleTime(darken.scheduleEndHour, darken.scheduleEndMinute)}"
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        onUpdateDarkenConfig(darken.copy(triggerMode = mode))
                                    }
                                    .testTag(modeTag),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = null
                                    )
                                    Icon(
                                        imageVector = modeIcon,
                                        contentDescription = null,
                                        tint = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = mode.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = modeSubtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Custom freely-changeable Schedule Time Picker
                    AnimatedVisibility(visible = darken.triggerMode.usesSchedule) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Schedule Time Window",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedCard(
                                    onClick = { editingTimeField = ScheduleTimeField.START },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 56.dp)
                                        .testTag("darken_schedule_start_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = "Start time",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatScheduleTime(
                                                darken.scheduleStartHour,
                                                darken.scheduleStartMinute
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                OutlinedCard(
                                    onClick = { editingTimeField = ScheduleTimeField.END },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 56.dp)
                                        .testTag("darken_schedule_end_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = "End time",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatScheduleTime(
                                                darken.scheduleEndHour,
                                                darken.scheduleEndMinute
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            val sameStartAndEnd = darken.scheduleStartHour == darken.scheduleEndHour &&
                                darken.scheduleStartMinute == darken.scheduleEndMinute
                            if (sameStartAndEnd) {
                                Text(
                                    text = "Start and end times are identical; choose different times to activate the schedule.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                val isOvernight = (darken.scheduleStartHour * 60 + darken.scheduleStartMinute) >
                                    (darken.scheduleEndHour * 60 + darken.scheduleEndMinute)
                                Text(
                                    text = if (isOvernight) {
                                        "Overnight schedule active until next morning. Tap either time to customize."
                                    } else {
                                        "Same-day schedule active between start and end. Tap either time to customize."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                                    text = "Wallpapers detected as dark or flagged as Already dark will not be over-darkened",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = darken.autoDetectDark,
                                onCheckedChange = { enabled ->
                                    onUpdateDarkenConfig(darken.copy(autoDetectDark = enabled))
                                },
                                modifier = Modifier.testTag("toggle_auto_detect_dark")
                            )
                        }
                    }

                    Text(
                        text = "Adjust each wallpaper's darken intensity and preview it directly inside Page Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("darken_page_settings_hint")
                    )
                }
            }
        }
    }

    editingTimeField?.let { field ->
        val initialHour = if (field == ScheduleTimeField.START) {
            darken.scheduleStartHour
        } else {
            darken.scheduleEndHour
        }
        val initialMinute = if (field == ScheduleTimeField.START) {
            darken.scheduleStartMinute
        } else {
            darken.scheduleEndMinute
        }
        ScheduleTimePickerDialog(
            title = if (field == ScheduleTimeField.START) {
                "Select Schedule Start Time"
            } else {
                "Select Schedule End Time"
            },
            initialHour = initialHour,
            initialMinute = initialMinute,
            onDismiss = { editingTimeField = null },
            onConfirm = { hour, minute ->
                val updated = if (field == ScheduleTimeField.START) {
                    darken.copy(scheduleStartHour = hour, scheduleStartMinute = minute)
                } else {
                    darken.copy(scheduleEndHour = hour, scheduleEndMinute = minute)
                }
                onUpdateDarkenConfig(updated)
                editingTimeField = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = initialMinute.coerceIn(0, 59),
        is24Hour = true
    )
    var useKeyboardInput by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { useKeyboardInput = !useKeyboardInput },
                    modifier = Modifier.testTag("darken_time_picker_mode_toggle")
                ) {
                    Icon(
                        imageVector = if (useKeyboardInput) {
                            Icons.Default.Schedule
                        } else {
                            Icons.Default.Keyboard
                        },
                        contentDescription = if (useKeyboardInput) {
                            "Switch to clock dial"
                        } else {
                            "Switch to text time input"
                        }
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (useKeyboardInput) {
                    TimeInput(state = timePickerState)
                } else {
                    TimePicker(state = timePickerState)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
                modifier = Modifier.testTag("darken_time_picker_confirm")
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("darken_time_picker_cancel")
            ) {
                Text("Cancel")
            }
        }
    )
}

private fun formatScheduleTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

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
