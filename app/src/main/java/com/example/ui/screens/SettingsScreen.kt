package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.BatterySaverConfig
import com.example.data.model.BatterySaverTrigger
import com.example.data.model.OffsetEasingStyle
import com.example.data.model.WallpaperAppState
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import com.example.diagnostics.WallpaperDiagnosticsResult
import com.example.diagnostics.WallpaperDiagnosticsState
import com.example.diagnostics.WallpaperSignalOverlayWindow
import com.example.power.BatterySaverPolicy
import com.example.power.PowerSnapshot
import com.example.ui.WallpaperViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: WallpaperViewModel) {
    val diagnostics by WallpaperDiagnosticsRecorder.state.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val powerSnapshot by viewModel.powerSnapshot.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SettingsSection(title = "Battery") {
            BatterySaverSetting(
                config = appState.batterySaver,
                normalFrameRate = appState.animationFrameRate,
                powerSnapshot = powerSnapshot,
                onConfigChange = viewModel::updateBatterySaverConfig
            )
        }

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
internal fun BatterySaverSetting(
    config: BatterySaverConfig,
    normalFrameRate: Int,
    powerSnapshot: PowerSnapshot,
    onConfigChange: (BatterySaverConfig) -> Unit
) {
    val thresholdSlider = remember(config.thresholdPercent) {
        mutableFloatStateOf(config.thresholdPercent.toFloat())
    }
    val isSaverActive = BatterySaverPolicy.isBatterySaverActive(config, powerSnapshot)
    val effectiveFps = BatterySaverPolicy.effectiveFrameRate(normalFrameRate, config, powerSnapshot)
    val batteryLabel = powerSnapshot.batteryPercent?.let { "$it%" } ?: "--%"
    val statusText = when {
        isSaverActive -> "$batteryLabel · Saving $effectiveFps FPS"
        config.enabled && config.pauseWhileCharging && powerSnapshot.isCharging ->
            "$batteryLabel · Charging ($normalFrameRate FPS)"
        config.enabled -> "$batteryLabel · Normal ($normalFrameRate FPS)"
        else -> "$batteryLabel · Off"
    }
    val statusContainerColor = when {
        isSaverActive -> MaterialTheme.colorScheme.tertiaryContainer
        config.enabled && config.pauseWhileCharging && powerSnapshot.isCharging ->
            MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusContentColor = when {
        isSaverActive -> MaterialTheme.colorScheme.onTertiaryContainer
        config.enabled && config.pauseWhileCharging && powerSnapshot.isCharging ->
            MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("battery_saver_card"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (powerSnapshot.isCharging) {
                            Icons.Default.BatteryChargingFull
                        } else {
                            Icons.Default.BatterySaver
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Battery-saving mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = statusContainerColor
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusContentColor,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("battery_saver_status_badge")
                    )
                }
            }

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
                        text = "Reduce wallpaper refresh rate on low power",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Passively reacts to Android battery and Battery Saver broadcast callbacks without polling.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                    modifier = Modifier.testTag("toggle_battery_saver")
                )
            }

            AnimatedVisibility(visible = config.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Activation trigger",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        BatterySaverTrigger.values().forEach { mode ->
                            val isSelected = config.triggerMode == mode
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onConfigChange(config.copy(triggerMode = mode))
                                    }
                                    .testTag("battery_saver_trigger_${mode.name}"),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                }
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
                                        onClick = {
                                            onConfigChange(config.copy(triggerMode = mode))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = config.triggerMode != BatterySaverTrigger.SYSTEM_BATTERY_SAVER
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Activate below battery level",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${thresholdSlider.floatValue.roundToInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = thresholdSlider.floatValue,
                                onValueChange = { thresholdSlider.floatValue = it },
                                onValueChangeFinished = {
                                    onConfigChange(
                                        config.copy(
                                            thresholdPercent = thresholdSlider.floatValue.roundToInt()
                                        )
                                    )
                                },
                                valueRange = BatterySaverConfig.MIN_THRESHOLD_PERCENT.toFloat()..
                                    BatterySaverConfig.MAX_THRESHOLD_PERCENT.toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("battery_saver_threshold_slider")
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Reduced refresh rate",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BatterySaverConfig.supportedReducedFrameRates.forEach { fps ->
                                FilterChip(
                                    selected = config.reducedFrameRate == fps,
                                    onClick = {
                                        onConfigChange(config.copy(reducedFrameRate = fps))
                                    },
                                    label = { Text("$fps FPS") },
                                    modifier = Modifier.testTag("battery_saver_fps_$fps")
                                )
                            }
                        }
                    }

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
                                    text = "Pause while charging",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Keep your normal $normalFrameRate FPS whenever the device is plugged in.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.pauseWhileCharging,
                                onCheckedChange = {
                                    onConfigChange(config.copy(pauseWhileCharging = it))
                                },
                                modifier = Modifier.testTag("toggle_battery_saver_pause_charging")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnimationFrameRateSetting(
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
        OffsetOverlaySetting(
            enabled = appState.showOffsetDebugOverlay,
            onEnabledChange = viewModel::toggleOffsetDebugOverlay
        )
        FrameSequenceIndicatorSetting(
            enabled = appState.showFrameDebugIndicator,
            onEnabledChange = viewModel::toggleFrameDebugIndicator
        )
        SignalDebugOverlaySetting(
            enabled = appState.showSignalDebugOverlay,
            onEnabledChange = viewModel::toggleSignalDebugOverlay
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
internal fun OffsetEasingSetting(
    enabled: Boolean,
    style: OffsetEasingStyle,
    linearZonePercent: Int,
    strength: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStyleChange: (OffsetEasingStyle) -> Unit,
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
                        text = "Remap wallpaper page offsets. Launcher icons are unaffected; settings remain available when easing is off.",
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
            Text(
                text = "Easing style",
                style = MaterialTheme.typography.labelMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OffsetEasingStyle.values().forEach { easingStyle ->
                    FilterChip(
                        selected = style == easingStyle,
                        onClick = { onStyleChange(easingStyle) },
                        label = { Text(easingStyle.displayName) },
                        modifier = Modifier.testTag("offset_easing_style_${easingStyle.name}")
                    )
                }
            }
            Text(
                text = if (style == OffsetEasingStyle.SYMMETRIC) {
                    "Middle linear zone: ${linearZoneSlider.floatValue.roundToInt()}%"
                } else {
                    "Linear portion: ${linearZoneSlider.floatValue.roundToInt()}%"
                },
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

@Composable
internal fun MotionBlurSetting(
    enabled: Boolean,
    strengthPercent: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStrengthChange: (Int) -> Unit
) {
    val strengthSlider = remember(strengthPercent) {
        mutableFloatStateOf(strengthPercent.toFloat())
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("motion_blur_setting"),
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
                        text = "Motion blur",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Smear the wallpaper in the direction of scrolling. Blur only runs while the wallpaper is moving.",
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
            Text(
                text = "Strength: ${strengthSlider.floatValue.roundToInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = strengthSlider.floatValue,
                onValueChange = { strengthSlider.floatValue = it },
                onValueChangeFinished = {
                    onStrengthChange(strengthSlider.floatValue.roundToInt())
                },
                valueRange = 0f..WallpaperAppState.MAX_MOTION_BLUR_STRENGTH_PERCENT.toFloat(),
                modifier = Modifier.testTag("motion_blur_strength")
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
private fun SignalDebugOverlaySetting(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
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

    Card(
        modifier = Modifier.fillMaxWidth().testTag("signal_debug_overlay_card"),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Show live signal debug overlay",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Displays live visibility, keyguard/unlock, screen power, launcher zoom, and system commands. Grant overlay permission to float above Recents and other apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("toggle_signal_debug_overlay")
                )
            }

            AnimatedVisibility(visible = enabled) {
                if (canDrawOverlays) {
                    Text(
                        text = "Floating overlay active: visible over Recents and other apps.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Overlay permission not granted — currently shown on desktop wallpaper only.",
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
                            modifier = Modifier.fillMaxWidth().testTag("grant_signal_overlay_permission")
                        ) {
                            Text("Allow display over other apps")
                        }
                    }
                }
            }
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
