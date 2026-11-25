package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.diagnostics.WallpaperDiagnosticsResult
import com.example.diagnostics.WallpaperDiagnosticsState
import com.example.diagnostics.WallpaperSignalOverlayWindow
import com.example.ui.AppLanguage
import com.example.ui.WallpaperViewModel
import com.example.ui.components.DashboardBadge
import com.example.ui.components.DashboardBadgeTone
import com.example.ui.components.DashboardMetric
import com.example.ui.components.EditorRadioOptionRow
import com.example.ui.components.EditorSwitchRow
import com.example.ui.components.SettingDashboardCard
import com.example.ui.components.SettingEditorDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: WallpaperViewModel,
    debugScrollTrigger: Int = 0
) {
    val diagnostics by WallpaperDiagnosticsRecorder.state.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val isWallpaperActive by viewModel.isWallpaperActive.collectAsState()
    val scrollState = rememberScrollState()
    var debugSectionOffsetY by remember { mutableIntStateOf(0) }

    LaunchedEffect(debugScrollTrigger) {
        if (debugScrollTrigger > 0) {
            scrollState.animateScrollTo(debugSectionOffsetY)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_general)) {
            LanguageDashboardCard(
                currentLanguageTag = appState.appLanguage,
                onSelectLanguage = viewModel::setAppLanguage
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_calibration)) {
            CalibrationScreen(viewModel = viewModel)
        }

        SettingsSection(title = stringResource(R.string.settings_section_backup)) {
            BackupScreen(viewModel = viewModel)
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_debug),
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    debugSectionOffsetY = coordinates.positionInParent().y.roundToInt()
                }
                .testTag("settings_debug_section")
        ) {
            DebugOverlaysDashboardCard(
                showOffsetOverlay = appState.showOffsetDebugOverlay,
                showFrameIndicator = appState.showFrameDebugIndicator,
                showSignalOverlay = appState.showSignalDebugOverlay,
                onCommit = { offsetOverlay, frameIndicator, signalOverlay ->
                    viewModel.toggleOffsetDebugOverlay(offsetOverlay)
                    viewModel.toggleFrameDebugIndicator(frameIndicator)
                    viewModel.toggleSignalDebugOverlay(signalOverlay)
                }
            )
            WallpaperRecordingDashboardCard(
                diagnostics = diagnostics,
                isWallpaperActive = isWallpaperActive,
                onClearHistory = viewModel::clearDiagnosticsHistory
            )
        }
    }
}

@Composable
private fun LanguageDashboardCard(
    currentLanguageTag: String,
    onSelectLanguage: (String) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val title = stringResource(R.string.language_title)
    val selectedLanguage = AppLanguage.fromTag(currentLanguageTag)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.Language,
        cardTestTag = "language_settings_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.language_current_label),
                value = stringResource(selectedLanguage.labelRes)
            )
        )
    )

    if (showEditor) {
        var draftLanguage by remember(currentLanguageTag) {
            mutableStateOf(AppLanguage.fromTag(currentLanguageTag).tag)
        }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.Language,
            onDismiss = { showEditor = false },
            onConfirm = {
                onSelectLanguage(draftLanguage)
                showEditor = false
            },
            dialogTestTag = "language_editor_dialog"
        ) {
            AppLanguage.values().forEach { language ->
                EditorRadioOptionRow(
                    title = stringResource(language.labelRes),
                    selected = draftLanguage.equals(language.tag, ignoreCase = true),
                    onClick = { draftLanguage = language.tag },
                    rowTestTag = "language_option_${language.tag}"
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
private fun DebugOverlaysDashboardCard(
    showOffsetOverlay: Boolean,
    showFrameIndicator: Boolean,
    showSignalOverlay: Boolean,
    onCommit: (Boolean, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canDrawOverlays by remember {
        mutableStateOf(WallpaperSignalOverlayWindow.canDrawOverlays(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = WallpaperSignalOverlayWindow.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showEditor by remember { mutableStateOf(false) }
    val title = stringResource(R.string.debug_overlays_title)
    val stateOn = stringResource(R.string.state_on)
    val stateOff = stringResource(R.string.state_off)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.BugReport,
        cardTestTag = "debug_overlays_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.debug_offset_overlay_label),
                value = if (showOffsetOverlay) stateOn else stateOff
            ),
            DashboardMetric(
                label = stringResource(R.string.debug_frame_indicator_label),
                value = if (showFrameIndicator) stateOn else stateOff
            ),
            DashboardMetric(
                label = stringResource(R.string.debug_signal_overlay_label),
                value = if (showSignalOverlay) stateOn else stateOff
            )
        )
    )

    if (showEditor) {
        var draftOffset by remember(showOffsetOverlay) { mutableStateOf(showOffsetOverlay) }
        var draftFrame by remember(showFrameIndicator) { mutableStateOf(showFrameIndicator) }
        var draftSignal by remember(showSignalOverlay) { mutableStateOf(showSignalOverlay) }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.BugReport,
            onDismiss = { showEditor = false },
            onConfirm = {
                onCommit(draftOffset, draftFrame, draftSignal)
                showEditor = false
            },
            dialogTestTag = "debug_overlays_editor_dialog"
        ) {
            EditorSwitchRow(
                title = stringResource(R.string.debug_offset_overlay_title),
                description = stringResource(R.string.debug_offset_overlay_desc),
                checked = draftOffset,
                onCheckedChange = { draftOffset = it },
                switchTestTag = "toggle_offset_debug_overlay"
            )

            EditorSwitchRow(
                title = stringResource(R.string.debug_frame_indicator_title),
                description = stringResource(R.string.debug_frame_indicator_desc),
                checked = draftFrame,
                onCheckedChange = { draftFrame = it },
                switchTestTag = "toggle_frame_debug_indicator"
            )

            EditorSwitchRow(
                title = stringResource(R.string.debug_signal_overlay_title),
                description = stringResource(R.string.debug_signal_overlay_desc),
                checked = draftSignal,
                onCheckedChange = { draftSignal = it },
                switchTestTag = "toggle_signal_debug_overlay"
            )

            AnimatedVisibility(visible = draftSignal) {
                if (canDrawOverlays) {
                    Text(
                        text = stringResource(R.string.debug_overlay_granted),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.debug_overlay_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("grant_signal_overlay_permission")
                        ) {
                            Text(stringResource(R.string.debug_grant_overlay_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperRecordingDashboardCard(
    diagnostics: WallpaperDiagnosticsState,
    isWallpaperActive: Boolean,
    onClearHistory: () -> Unit
) {
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val title = stringResource(R.string.recording_title)
    val offsetRate = combinedRate(
        diagnostics.history.sumOf { it.movingOffsetIntervalCount },
        diagnostics.history.sumOf { it.movingOffsetDurationNanos }
    )
    val frameRate = combinedRate(
        diagnostics.history.sumOf { it.movingFrameIntervalCount },
        diagnostics.history.sumOf { it.movingFrameDurationNanos }
    )
    val avgSummary = if (diagnostics.history.isEmpty()) {
        "--"
    } else {
        "${formatRate(offsetRate)} · ${formatRate(frameRate)}"
    }

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.Speed,
        cardTestTag = "wallpaper_diagnostics_card",
        editContentDescription = title,
        onEditClick = null,
        badges = listOf(
            DashboardBadge(
                text = if (diagnostics.isRecording) {
                    stringResource(R.string.recording_status_recording)
                } else {
                    stringResource(R.string.recording_status_idle)
                },
                tone = if (diagnostics.isRecording) {
                    DashboardBadgeTone.PRIMARY
                } else {
                    DashboardBadgeTone.NEUTRAL
                },
                showDot = true,
                testTag = if (diagnostics.isRecording) "wallpaper_recording_status" else null
            )
        ),
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.recording_saved_count_label),
                value = diagnostics.history.size.toString()
            ),
            DashboardMetric(
                label = stringResource(R.string.recording_average_label),
                value = avgSummary
            )
        ),
        supportingText = if (!isWallpaperActive && !diagnostics.isRecording) {
            stringResource(R.string.recording_apply_required)
        } else {
            null
        },
        footer = {
            diagnostics.current?.let { result ->
                RecordingMetrics(result)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("toggle_wallpaper_recording")
            ) {
                Icon(
                    imageVector = if (diagnostics.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    if (diagnostics.isRecording) {
                        stringResource(R.string.recording_stop)
                    } else {
                        stringResource(R.string.recording_start)
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showHistoryDialog = true },
                    enabled = diagnostics.history.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("view_recording_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.recording_view_history))
                }

                OutlinedButton(
                    onClick = { showClearConfirmDialog = true },
                    enabled = diagnostics.history.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("clear_recording_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.recording_clear_history))
                }
            }
        }
    )

    if (showHistoryDialog) {
        RecordingHistoryDialog(
            history = diagnostics.history,
            onDismiss = { showHistoryDialog = false }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(stringResource(R.string.recording_clear_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.recording_clear_message,
                        diagnostics.history.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        showClearConfirmDialog = false
                    },
                    modifier = Modifier.testTag("confirm_clear_recording_history")
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun RecordingHistoryDialog(
    history: List<WallpaperDiagnosticsResult>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 660.dp)
                .testTag("recording_history_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.recording_history_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (history.isEmpty()) {
                        Text(
                            text = stringResource(R.string.recording_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        RecentAverage(history)
                        history.forEachIndexed { index, result ->
                            RecordingHistoryItem(index, result)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("close_recording_history_button")
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingMetrics(result: WallpaperDiagnosticsResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wallpaper_recording_metrics"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.recording_current_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            MetricRow(
                stringResource(R.string.recording_metric_duration),
                formatDuration(result.durationMillis)
            )
            MetricRow(
                stringResource(R.string.recording_metric_visible),
                formatDuration(result.visibleDurationNanos / 1_000_000L)
            )
            MetricRow(
                stringResource(R.string.recording_metric_visible_callbacks),
                result.visibleOffsetCallbacks.toString()
            )
            MetricRow(
                stringResource(R.string.recording_metric_avg_callbacks),
                formatRate(result.averageOffsetCallbackHz)
            )
            MetricRow(
                stringResource(R.string.recording_metric_changed_x),
                result.changedOffsetCallbacks.toString()
            )
            MetricRow(
                stringResource(R.string.recording_metric_moving_offset),
                formatRate(result.movingOffsetHz)
            )
            MetricRow(
                stringResource(R.string.recording_metric_visible_frames),
                result.visibleFramesSubmitted.toString()
            )
            MetricRow(
                stringResource(R.string.recording_metric_offset_frames),
                result.offsetDrivenFramesSubmitted.toString()
            )
            MetricRow(
                stringResource(R.string.recording_metric_moving_frames),
                formatRate(result.movingFrameSubmissionHz)
            )
            MetricRow(
                stringResource(R.string.recording_metric_avg_frames),
                formatRate(result.averageFrameSubmissionHz)
            )
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
        text = stringResource(
            R.string.recording_combined_average,
            history.size,
            formatRate(offsetRate),
            formatRate(frameRate)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("wallpaper_recording_history_average")
    )
}

@Composable
private fun RecordingHistoryItem(index: Int, result: WallpaperDiagnosticsResult) {
    val currentLocale = LocalConfiguration.current.locales.takeIf { !it.isEmpty }?.get(0)
        ?: Locale.getDefault()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wallpaper_recording_history_item_$index"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = SimpleDateFormat("MMM d, HH:mm", currentLocale)
                    .format(Date(result.recordedAtMillis)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.recording_item_summary,
                    formatDuration(result.durationMillis),
                    formatRate(result.movingOffsetHz),
                    formatRate(result.movingFrameSubmissionHz)
                ),
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

@Composable
private fun formatRate(rate: Float?): String =
    rate?.let { stringResource(R.string.recording_rate_hz, it) }
        ?: stringResource(R.string.recording_not_enough_movement)

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
