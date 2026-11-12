package com.example.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.diagnostics.WallpaperDiagnosticsResult
import com.example.diagnostics.WallpaperDiagnosticsState
import com.example.ui.WallpaperViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        OffsetOverlaySetting(
            enabled = appState.showOffsetDebugOverlay,
            onEnabledChange = viewModel::toggleOffsetDebugOverlay
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
                    text = "This records launcher offset callbacks and successful wallpaper frame submissions. It does not measure frames actually presented by the display. The wallpaper is event-driven and does not continuously redraw while idle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "There is no app-side FPS cap: launcher offset callbacks drive redraws, while Android controls display presentation.",
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
