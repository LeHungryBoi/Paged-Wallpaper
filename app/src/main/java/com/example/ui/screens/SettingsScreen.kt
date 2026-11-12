package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.WallpaperAppState
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.diagnostics.WallpaperDiagnosticsResult
import com.example.diagnostics.WallpaperDiagnosticsState
import com.example.ui.WallpaperViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: WallpaperViewModel) {
    val diagnostics by WallpaperDiagnosticsRecorder.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsSection(title = "Calibration") {
            CalibrationScreen(viewModel = viewModel)
        }

        SettingsSection(title = "Backup") {
            BackupScreen(viewModel = viewModel)
        }

        SettingsSection(title = "Debug") {
            DebugSettings(viewModel, diagnostics)
        }
    }
}

@Composable
private fun AnimationFrameRateSetting(
    selectedFrameRate: Int,
    onFrameRateSelected: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("animation_frame_rate_card"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Wallpaper animation frame rate",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The wallpaper smoothly follows launcher offsets at this target rate while moving. Higher rates may use more battery; the display may not support every option.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperAppState.supportedAnimationFrameRates.forEach { frameRate ->
                    FilterChip(
                        selected = selectedFrameRate == frameRate,
                        onClick = { onFrameRateSelected(frameRate) },
                        label = { Text("$frameRate FPS") },
                        modifier = Modifier.testTag("animation_frame_rate_$frameRate")
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun DebugSettings(
    viewModel: WallpaperViewModel,
    diagnostics: WallpaperDiagnosticsState
) {
    val appState by viewModel.appState.collectAsState()
    val isWallpaperActive by viewModel.isWallpaperActive.collectAsState()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AnimationFrameRateSetting(
            selectedFrameRate = appState.animationFrameRate,
            onFrameRateSelected = viewModel::setAnimationFrameRate
        )
        MotionBlurSetting(
            enabled = appState.motionBlurEnabled,
            onEnabledChange = viewModel::toggleMotionBlur
        )
        OffsetEasingSetting(
            enabled = appState.offsetEasingEnabled,
            linearZonePercent = appState.offsetEasingLinearZonePercent,
            strength = appState.offsetEasingStrength,
            onEnabledChange = viewModel::toggleOffsetEasing,
            onLinearZonePercentChange = viewModel::setOffsetEasingLinearZonePercent,
            onStrengthChange = viewModel::setOffsetEasingStrength
        )
        OffsetOverlaySetting(
            enabled = appState.showOffsetDebugOverlay,
            onEnabledChange = viewModel::toggleOffsetDebugOverlay
        )
        FrameSequenceIndicatorSetting(
            enabled = appState.showFrameDebugIndicator,
            onEnabledChange = viewModel::toggleFrameDebugIndicator
        )

        Card(
            modifier = Modifier.fillMaxWidth().testTag("wallpaper_diagnostics_card"),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Wallpaper update recording",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Start recording, go to the home screen and swipe between pages, then return here and stop. The last 10 completed recordings are saved on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "This records launcher offset callbacks and successful wallpaper frame submissions. It does not measure frames actually presented by the display. While moving, the wallpaper follows the latest offset at the selected target cadence, then stops drawing when settled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "The selected rate is a target, not a guarantee. Actual presentation is limited by the display refresh rate and rendering performance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isWallpaperActive && !diagnostics.isRecording) {
                    Text(
                        text = "Apply PaperPages as the active wallpaper before starting a recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = {
                        if (diagnostics.isRecording) {
                            WallpaperDiagnosticsRecorder.stopRecording()
                        } else {
                            WallpaperDiagnosticsRecorder.startRecording()
                        }
                    },
                    enabled = isWallpaperActive || diagnostics.isRecording,
                    modifier = Modifier.fillMaxWidth().testTag("toggle_wallpaper_recording")
                ) {
                    Icon(
                        imageVector = if (diagnostics.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (diagnostics.isRecording) "Stop and save recording" else "Start recording")
                }

                if (diagnostics.isRecording) {
                    Text(
                        text = "Recording…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("wallpaper_recording_status")
                    )
                }

                diagnostics.current?.let { result ->
                    RecordingMetrics(result)
                }

                if (diagnostics.history.isNotEmpty()) {
                    Text(
                        text = "Recent recordings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    RecentAverage(diagnostics.history)
                    diagnostics.history.forEachIndexed { index, result ->
                        RecordingHistoryItem(index, result)
                    }
                }
            }
        }
    }
}

@Composable
private fun OffsetEasingSetting(
    enabled: Boolean,
    linearZonePercent: Int,
    strength: Int,
    onEnabledChange: (Boolean) -> Unit,
    onLinearZonePercentChange: (Int) -> Unit,
    onStrengthChange: (Int) -> Unit
) {
    val linearZoneSlider = remember(linearZonePercent) {
        mutableFloatStateOf(linearZonePercent.toFloat())
    }
    val strengthSlider = remember(strength) {
        mutableFloatStateOf(strength.toFloat())
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("offset_easing_setting"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Wallpaper offset easing",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Adjust the wallpaper's linear middle range and edge easing strength. Launcher icons are unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("toggle_offset_easing")
                )
            }
            if (enabled) {
                Text(
                    text = "Middle linear zone: ${linearZoneSlider.floatValue.roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = linearZoneSlider.floatValue,
                    onValueChange = { linearZoneSlider.floatValue = it },
                    onValueChangeFinished = {
                        onLinearZonePercentChange(linearZoneSlider.floatValue.roundToInt())
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.testTag("offset_easing_linear_zone")
                )
                Text(
                    text = "Easing strength: ${strengthSlider.floatValue.roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = strengthSlider.floatValue,
                    onValueChange = { strengthSlider.floatValue = it },
                    onValueChangeFinished = {
                        onStrengthChange(strengthSlider.floatValue.roundToInt())
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.testTag("offset_easing_strength")
                )
            }
        }
    }
}

@Composable
private fun MotionBlurSetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("motion_blur_setting"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Motion blur",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Smear the wallpaper in the direction of scrolling. Blur length adapts to movement and only runs while the wallpaper is moving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("toggle_motion_blur")
            )
        }
    }
}

@Composable
private fun OffsetOverlaySetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("offset_debug_overlay_card"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Show live offset overlay",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Display the raw X/Y offsets received from the launcher on the wallpaper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("toggle_offset_debug_overlay")
            )
        }
    }
}

@Composable
private fun FrameSequenceIndicatorSetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("frame_debug_indicator_card"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Show frame sequence indicator",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Draw a small block that cycles red, green, blue, yellow after each successfully submitted visible frame. A skipped color in a recording can mean a frame was not captured or presented.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("toggle_frame_debug_indicator")
            )
        }
    }
}

@Composable
private fun RecordingMetrics(result: WallpaperDiagnosticsResult) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("wallpaper_recording_metrics"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Current recording",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            MetricRow("Duration", formatDuration(result.durationMillis))
            MetricRow("Wallpaper visible", formatDuration(result.visibleDurationNanos / 1_000_000L))
            MetricRow("Visible offset callbacks", result.visibleOffsetCallbacks.toString())
            MetricRow("Average callbacks while visible", formatRate(result.averageOffsetCallbackHz))
            MetricRow("Changed X offsets", result.changedOffsetCallbacks.toString())
            MetricRow("Moving offset update rate", formatRate(result.movingOffsetHz))
            MetricRow("Visible frames submitted", result.visibleFramesSubmitted.toString())
            MetricRow("Frames from offset callbacks", result.offsetDrivenFramesSubmitted.toString())
            MetricRow("Moving frame submission rate", formatRate(result.movingFrameSubmissionHz))
            MetricRow("Average submissions while visible", formatRate(result.averageFrameSubmissionHz))
        }
    }
}

@Composable
private fun RecentAverage(history: List<WallpaperDiagnosticsResult>) {
    val offsetRate = combinedRate(
        history.sumOf { it.movingOffsetIntervalCount },
        history.sumOf { it.movingOffsetDurationNanos }
    )
    val frameRate = combinedRate(
        history.sumOf { it.movingFrameIntervalCount },
        history.sumOf { it.movingFrameDurationNanos }
    )
    Text(
        text = "Combined moving average (${history.size} sessions): " +
            "offset ${formatRate(offsetRate)}, frame submissions ${formatRate(frameRate)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("wallpaper_recording_history_average")
    )
}

@Composable
private fun RecordingHistoryItem(index: Int, result: WallpaperDiagnosticsResult) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("wallpaper_recording_history_item_$index"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    .format(Date(result.recordedAtMillis)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${formatDuration(result.durationMillis)} · " +
                    "offset ${formatRate(result.movingOffsetHz)} · " +
                    "frame submissions ${formatRate(result.movingFrameSubmissionHz)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatRate(rate: Float?): String =
    rate?.let { String.format(Locale.US, "%.1f Hz", it) } ?: "Not enough movement"

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000L).coerceAtLeast(0L)
    return if (totalSeconds < 60L) {
        "${totalSeconds}s"
    } else {
        "${totalSeconds / 60L}m ${totalSeconds % 60L}s"
    }
}

private fun combinedRate(intervalCount: Int, durationNanos: Long): Float? =
    if (intervalCount > 0 && durationNanos > 0L) {
        intervalCount * 1_000_000_000f / durationNanos
    } else {
        null
    }
