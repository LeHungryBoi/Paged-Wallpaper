package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.darken.ExtraDimDetector
import com.example.data.model.BatterySaverConfig
import com.example.data.model.BatterySaverTrigger
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTriggerMode
import com.example.data.model.OffsetEasingStyle
import com.example.data.model.PageItem
import com.example.data.model.WallpaperAppState
import com.example.power.BatterySaverPolicy
import com.example.power.PowerSnapshot
import com.example.ui.WallpaperViewModel
import com.example.ui.batterySaverTriggerDescRes
import com.example.ui.batterySaverTriggerLabelRes
import com.example.ui.components.DashboardBadge
import com.example.ui.components.DashboardBadgeTone
import com.example.ui.components.DashboardMetric
import com.example.ui.components.EditorRadioOptionRow
import com.example.ui.components.EditorSliderControl
import com.example.ui.components.EditorSwitchRow
import com.example.ui.components.SettingDashboardCard
import com.example.ui.components.SettingEditorDialog
import com.example.ui.darkenTriggerLabelRes
import com.example.ui.offsetEasingStyleLabelRes
import com.example.util.ImageProcessingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val powerSnapshot by viewModel.powerSnapshot.collectAsState()
    val isInSystemDarkTheme = isSystemInDarkTheme()
    LaunchedEffect(isInSystemDarkTheme) {
        viewModel.refreshSystemDarkMode(isInSystemDarkTheme)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        MotionBlurDashboardCard(
            enabled = appState.motionBlurEnabled,
            strengthPercent = appState.motionBlurStrengthPercent,
            onCommit = { enabled, strength ->
                viewModel.toggleMotionBlur(enabled)
                viewModel.setMotionBlurStrengthPercent(strength)
            }
        )

        OffsetEasingDashboardCard(
            enabled = appState.offsetEasingEnabled,
            style = appState.offsetEasingStyle,
            linearZonePercent = appState.offsetEasingLinearZonePercent,
            strength = appState.offsetEasingStrength,
            onCommit = { enabled, style, linearZone, strength ->
                viewModel.toggleOffsetEasing(enabled)
                viewModel.setOffsetEasingStyle(style)
                viewModel.setOffsetEasingLinearZonePercent(linearZone)
                viewModel.setOffsetEasingStrength(strength)
            }
        )

        AnimationFrameRateDashboardCard(
            selectedFrameRate = appState.animationFrameRate,
            onCommit = viewModel::setAnimationFrameRate
        )

        DisplayEntryAnimationDashboardCard(
            appState = appState,
            onCommit = { enabled, durationMs, zoomEnabled, zoomScale, fadeEnabled, fadeAlpha, blurEnabled, blurStrength ->
                viewModel.toggleResumeFade(enabled)
                viewModel.setEntryDurationMs(durationMs)
                viewModel.toggleEntryZoom(zoomEnabled)
                viewModel.setEntryZoomStartScale(zoomScale)
                viewModel.toggleEntryBlackFade(fadeEnabled)
                viewModel.setEntryBlackFadeMaxAlpha(fadeAlpha)
                viewModel.toggleEntryBlur(blurEnabled)
                viewModel.setEntryBlurStrength(blurStrength)
            }
        )

        DarkenConditionsDashboardCard(
            darken = appState.darken,
            batterySaver = appState.batterySaver,
            normalFrameRate = appState.animationFrameRate,
            isSystemDarkMode = isSystemDarkMode,
            powerSnapshot = powerSnapshot,
            pages = appState.pages,
            loadPagePreview = { pageIndex ->
                viewModel.loadPagePreviewBitmap(pageIndex, 240, 426, darkened = false)
            },
            onCommit = { nextDarken, nextBattery ->
                viewModel.updateDarkenConfig(nextDarken)
                viewModel.updateBatterySaverConfig(nextBattery)
            }
        )
    }
}

@Composable
private fun MotionBlurDashboardCard(
    enabled: Boolean,
    strengthPercent: Int,
    onCommit: (Boolean, Int) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val title = stringResource(R.string.behavior_motion_blur_title)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.BlurOn,
        cardTestTag = "motion_blur_setting",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        badges = listOf(
            DashboardBadge(
                text = if (enabled) stringResource(R.string.state_on) else stringResource(R.string.state_off),
                tone = if (enabled) DashboardBadgeTone.PRIMARY else DashboardBadgeTone.NEUTRAL
            )
        ),
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.behavior_strength_label),
                value = if (enabled) {
                    stringResource(R.string.percent_value, strengthPercent)
                } else {
                    stringResource(R.string.state_off)
                }
            )
        )
    )

    if (showEditor) {
        var draftEnabled by remember(enabled) { mutableStateOf(enabled) }
        var draftStrength by remember(strengthPercent) {
            mutableFloatStateOf(strengthPercent.toFloat())
        }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.BlurOn,
            onDismiss = { showEditor = false },
            onConfirm = {
                onCommit(draftEnabled, draftStrength.roundToInt())
                showEditor = false
            },
            dialogTestTag = "motion_blur_editor_dialog"
        ) {
            EditorSwitchRow(
                title = title,
                description = stringResource(R.string.behavior_motion_blur_desc),
                checked = draftEnabled,
                onCheckedChange = { draftEnabled = it },
                switchTestTag = "toggle_motion_blur"
            )
            EditorSliderControl(
                label = stringResource(R.string.behavior_strength_label),
                valueText = stringResource(R.string.percent_value, draftStrength.roundToInt()),
                value = draftStrength,
                onValueChange = { draftStrength = it },
                valueRange = 0f..WallpaperAppState.MAX_MOTION_BLUR_STRENGTH_PERCENT.toFloat(),
                sliderTestTag = "motion_blur_strength"
            )
        }
    }
}

@Composable
private fun OffsetEasingDashboardCard(
    enabled: Boolean,
    style: OffsetEasingStyle,
    linearZonePercent: Int,
    strength: Int,
    onCommit: (Boolean, OffsetEasingStyle, Int, Int) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val title = stringResource(R.string.behavior_offset_easing_title)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.Tune,
        cardTestTag = "offset_easing_setting",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        badges = listOf(
            DashboardBadge(
                text = if (enabled) stringResource(R.string.state_on) else stringResource(R.string.state_off),
                tone = if (enabled) DashboardBadgeTone.PRIMARY else DashboardBadgeTone.NEUTRAL
            )
        ),
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.behavior_easing_style_label),
                value = stringResource(offsetEasingStyleLabelRes(style))
            ),
            DashboardMetric(
                label = if (style == OffsetEasingStyle.SYMMETRIC) {
                    stringResource(R.string.behavior_linear_zone_label)
                } else {
                    stringResource(R.string.behavior_linear_portion_label)
                },
                value = stringResource(R.string.percent_value, linearZonePercent)
            ),
            DashboardMetric(
                label = stringResource(R.string.behavior_strength_label),
                value = stringResource(R.string.percent_value, strength)
            )
        )
    )

    if (showEditor) {
        var draftEnabled by remember(enabled) { mutableStateOf(enabled) }
        var draftStyle by remember(style) { mutableStateOf(style) }
        var draftLinearZone by remember(linearZonePercent) {
            mutableFloatStateOf(linearZonePercent.toFloat())
        }
        var draftStrength by remember(strength) { mutableFloatStateOf(strength.toFloat()) }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.Tune,
            onDismiss = { showEditor = false },
            onConfirm = {
                onCommit(
                    draftEnabled,
                    draftStyle,
                    draftLinearZone.roundToInt(),
                    draftStrength.roundToInt()
                )
                showEditor = false
            },
            dialogTestTag = "offset_easing_editor_dialog"
        ) {
            EditorSwitchRow(
                title = title,
                description = stringResource(R.string.behavior_offset_easing_desc),
                checked = draftEnabled,
                onCheckedChange = { draftEnabled = it },
                switchTestTag = "toggle_offset_easing"
            )
            Text(
                text = stringResource(R.string.behavior_easing_style_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OffsetEasingStyle.values().forEach { easingStyle ->
                    FilterChip(
                        selected = draftStyle == easingStyle,
                        onClick = { draftStyle = easingStyle },
                        label = { Text(stringResource(offsetEasingStyleLabelRes(easingStyle))) },
                        modifier = Modifier.testTag("offset_easing_style_${easingStyle.name}")
                    )
                }
            }
            EditorSliderControl(
                label = if (draftStyle == OffsetEasingStyle.SYMMETRIC) {
                    stringResource(R.string.behavior_linear_zone_label)
                } else {
                    stringResource(R.string.behavior_linear_portion_label)
                },
                valueText = stringResource(R.string.percent_value, draftLinearZone.roundToInt()),
                value = draftLinearZone,
                onValueChange = { draftLinearZone = it },
                valueRange = 0f..100f,
                sliderTestTag = "offset_easing_linear_zone"
            )
            EditorSliderControl(
                label = stringResource(R.string.behavior_strength_label),
                valueText = stringResource(R.string.percent_value, draftStrength.roundToInt()),
                value = draftStrength,
                onValueChange = { draftStrength = it },
                valueRange = 0f..100f,
                sliderTestTag = "offset_easing_strength"
            )
        }
    }
}

@Composable
private fun AnimationFrameRateDashboardCard(
    selectedFrameRate: Int,
    onCommit: (Int) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val title = stringResource(R.string.behavior_frame_rate_title)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.Speed,
        cardTestTag = "animation_frame_rate_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        badges = listOf(
            DashboardBadge(
                text = stringResource(R.string.fps_value, selectedFrameRate),
                tone = DashboardBadgeTone.PRIMARY
            )
        ),
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.behavior_target_rate_label),
                value = stringResource(R.string.fps_value, selectedFrameRate)
            )
        )
    )

    if (showEditor) {
        var draftFrameRate by remember(selectedFrameRate) {
            mutableIntStateOf(selectedFrameRate)
        }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.Speed,
            onDismiss = { showEditor = false },
            onConfirm = {
                onCommit(draftFrameRate)
                showEditor = false
            },
            dialogTestTag = "frame_rate_editor_dialog"
        ) {
            Text(
                text = stringResource(R.string.behavior_frame_rate_desc),
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
                        selected = draftFrameRate == frameRate,
                        onClick = { draftFrameRate = frameRate },
                        label = { Text(stringResource(R.string.fps_value, frameRate)) },
                        modifier = Modifier.testTag("animation_frame_rate_$frameRate")
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayEntryAnimationDashboardCard(
    appState: WallpaperAppState,
    onCommit: (
        enabled: Boolean,
        durationMs: Int,
        zoomEnabled: Boolean,
        zoomStartScale: Float,
        blackFadeEnabled: Boolean,
        blackFadeMaxAlpha: Float,
        blurEnabled: Boolean,
        blurStrength: Float
    ) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val title = stringResource(R.string.behavior_entry_title)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.AutoAwesome,
        cardTestTag = "resume_transition_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        badges = listOf(
            DashboardBadge(
                text = if (appState.resumeFadeEnabled) {
                    stringResource(R.string.state_on)
                } else {
                    stringResource(R.string.state_off)
                },
                tone = if (appState.resumeFadeEnabled) {
                    DashboardBadgeTone.PRIMARY
                } else {
                    DashboardBadgeTone.NEUTRAL
                }
            )
        ),
        metrics = if (appState.resumeFadeEnabled) {
            listOf(
                DashboardMetric(
                    label = stringResource(R.string.behavior_entry_duration_label),
                    value = stringResource(R.string.ms_value, appState.entryDurationMs)
                ),
                DashboardMetric(
                    label = stringResource(R.string.behavior_entry_zoom_title),
                    value = if (appState.entryZoomEnabled) {
                        stringResource(
                            R.string.percent_value,
                            (appState.entryZoomStartScale * 100f).roundToInt()
                        )
                    } else {
                        stringResource(R.string.state_off)
                    }
                ),
                DashboardMetric(
                    label = stringResource(R.string.behavior_entry_fade_title),
                    value = if (appState.entryBlackFadeEnabled) {
                        stringResource(
                            R.string.percent_value,
                            (appState.entryBlackFadeMaxAlpha * 100f).roundToInt()
                        )
                    } else {
                        stringResource(R.string.state_off)
                    }
                ),
                DashboardMetric(
                    label = stringResource(R.string.behavior_entry_blur_title),
                    value = if (appState.entryBlurEnabled) {
                        stringResource(
                            R.string.percent_value,
                            (appState.entryBlurStrength * 100f).roundToInt()
                        )
                    } else {
                        stringResource(R.string.state_off)
                    }
                )
            )
        } else {
            listOf(
                DashboardMetric(
                    label = stringResource(R.string.behavior_entry_duration_label),
                    value = stringResource(R.string.state_off)
                )
            )
        }
    )

    if (showEditor) {
        var draftEnabled by remember(appState.resumeFadeEnabled) {
            mutableStateOf(appState.resumeFadeEnabled)
        }
        var draftDuration by remember(appState.entryDurationMs) {
            mutableFloatStateOf(appState.entryDurationMs.toFloat())
        }
        var draftZoomEnabled by remember(appState.entryZoomEnabled) {
            mutableStateOf(appState.entryZoomEnabled)
        }
        var draftZoomScale by remember(appState.entryZoomStartScale) {
            mutableFloatStateOf(appState.entryZoomStartScale)
        }
        var draftFadeEnabled by remember(appState.entryBlackFadeEnabled) {
            mutableStateOf(appState.entryBlackFadeEnabled)
        }
        var draftFadeAlpha by remember(appState.entryBlackFadeMaxAlpha) {
            mutableFloatStateOf(appState.entryBlackFadeMaxAlpha)
        }
        var draftBlurEnabled by remember(appState.entryBlurEnabled) {
            mutableStateOf(appState.entryBlurEnabled)
        }
        var draftBlurStrength by remember(appState.entryBlurStrength) {
            mutableFloatStateOf(appState.entryBlurStrength)
        }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.AutoAwesome,
            onDismiss = { showEditor = false },
            onConfirm = {
                onCommit(
                    draftEnabled,
                    draftDuration.roundToInt(),
                    draftZoomEnabled,
                    draftZoomScale,
                    draftFadeEnabled,
                    draftFadeAlpha,
                    draftBlurEnabled,
                    draftBlurStrength
                )
                showEditor = false
            },
            dialogTestTag = "entry_animation_editor_dialog"
        ) {
            EditorSwitchRow(
                title = title,
                description = stringResource(R.string.behavior_entry_desc),
                checked = draftEnabled,
                onCheckedChange = { draftEnabled = it },
                switchTestTag = "toggle_resume_fade"
            )

            AnimatedVisibility(visible = draftEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorSliderControl(
                        label = stringResource(R.string.behavior_entry_duration_label),
                        valueText = stringResource(R.string.ms_value, draftDuration.roundToInt()),
                        value = draftDuration,
                        onValueChange = { draftDuration = it },
                        valueRange = WallpaperAppState.MIN_ENTRY_DURATION_MS.toFloat()..
                            WallpaperAppState.MAX_ENTRY_DURATION_MS.toFloat(),
                        sliderTestTag = "entry_duration_slider"
                    )

                    EntryElementSection(
                        title = stringResource(R.string.behavior_entry_zoom_title),
                        description = stringResource(R.string.behavior_entry_zoom_desc),
                        checked = draftZoomEnabled,
                        onCheckedChange = { draftZoomEnabled = it },
                        switchTestTag = "toggle_entry_zoom"
                    ) {
                        EditorSliderControl(
                            label = stringResource(R.string.behavior_entry_zoom_title),
                            valueText = stringResource(
                                R.string.percent_value,
                                (draftZoomScale * 100).roundToInt()
                            ),
                            value = draftZoomScale,
                            onValueChange = { draftZoomScale = it },
                            valueRange = WallpaperAppState.MIN_ENTRY_ZOOM_START_SCALE..
                                WallpaperAppState.MAX_ENTRY_ZOOM_START_SCALE,
                            sliderTestTag = "entry_zoom_scale_slider"
                        )
                    }

                    EntryElementSection(
                        title = stringResource(R.string.behavior_entry_fade_title),
                        description = stringResource(R.string.behavior_entry_fade_desc),
                        checked = draftFadeEnabled,
                        onCheckedChange = { draftFadeEnabled = it },
                        switchTestTag = "toggle_entry_black_fade"
                    ) {
                        EditorSliderControl(
                            label = stringResource(R.string.behavior_entry_fade_title),
                            valueText = stringResource(
                                R.string.percent_value,
                                (draftFadeAlpha * 100).roundToInt()
                            ),
                            value = draftFadeAlpha,
                            onValueChange = { draftFadeAlpha = it },
                            valueRange = 0.1f..1f,
                            sliderTestTag = "entry_black_fade_slider"
                        )
                    }

                    EntryElementSection(
                        title = stringResource(R.string.behavior_entry_blur_title),
                        description = stringResource(R.string.behavior_entry_blur_desc),
                        checked = draftBlurEnabled,
                        onCheckedChange = { draftBlurEnabled = it },
                        switchTestTag = "toggle_entry_blur"
                    ) {
                        EditorSliderControl(
                            label = stringResource(R.string.behavior_entry_blur_title),
                            valueText = stringResource(
                                R.string.percent_value,
                                (draftBlurStrength * 100).roundToInt()
                            ),
                            value = draftBlurStrength,
                            onValueChange = { draftBlurStrength = it },
                            valueRange = 0.1f..1f,
                            sliderTestTag = "entry_blur_slider"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DarkenConditionsDashboardCard(
    darken: DarkenConfig,
    batterySaver: BatterySaverConfig,
    normalFrameRate: Int,
    isSystemDarkMode: Boolean,
    powerSnapshot: PowerSnapshot,
    pages: List<PageItem>,
    loadPagePreview: (Int) -> Bitmap?,
    onCommit: (DarkenConfig, BatterySaverConfig) -> Unit
) {
    var scheduleTimeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(darken.useSchedule) {
        if (!darken.useSchedule) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            scheduleTimeTick++
        }
    }

    val isSaverActive = BatterySaverPolicy.isBatterySaverActive(batterySaver, powerSnapshot)
    val isScheduleActiveNow = remember(darken, scheduleTimeTick) {
        darken.useSchedule && ExtraDimDetector.isScheduledActive(darken)
    }
    val modeDarkenActive = darken.enabled && when (darken.triggerMode) {
        DarkenTriggerMode.SCHEDULE_ONLY -> isScheduleActiveNow
        DarkenTriggerMode.DARK_MODE_ONLY -> isSystemDarkMode
        DarkenTriggerMode.BOTH_OR -> isSystemDarkMode || isScheduleActiveNow
        DarkenTriggerMode.BATTERY_SAVER_ONLY -> isSaverActive
    }
    val isDarkenActiveNow = modeDarkenActive ||
        (darken.enabled && darken.darkenOnBatterySaver && isSaverActive)

    val darkenBadge = when {
        isDarkenActiveNow -> DashboardBadge(
            text = stringResource(R.string.darken_status_darkened),
            tone = DashboardBadgeTone.SUCCESS,
            showDot = true,
            testTag = "darken_status_badge"
        )
        darken.enabled -> DashboardBadge(
            text = stringResource(R.string.darken_status_standby),
            tone = DashboardBadgeTone.PRIMARY,
            showDot = true,
            testTag = "darken_status_badge"
        )
        else -> DashboardBadge(
            text = stringResource(R.string.darken_status_off),
            tone = DashboardBadgeTone.NEUTRAL,
            showDot = true,
            testTag = "darken_status_badge"
        )
    }

    val effectiveFps = BatterySaverPolicy.effectiveFrameRate(normalFrameRate, batterySaver, powerSnapshot)
    val batteryLabel = powerSnapshot.batteryPercent?.let {
        stringResource(R.string.percent_value, it)
    } ?: "--%"
    val batteryBadgeText = when {
        isSaverActive -> stringResource(R.string.battery_saver_status_saving, batteryLabel, effectiveFps)
        batterySaver.enabled && batterySaver.pauseWhileCharging && powerSnapshot.isCharging ->
            stringResource(R.string.battery_saver_status_charging, batteryLabel, normalFrameRate)
        batterySaver.enabled ->
            stringResource(R.string.battery_saver_status_normal, batteryLabel, normalFrameRate)
        else -> stringResource(R.string.battery_saver_status_off, batteryLabel)
    }
    val batteryBadgeTone = when {
        isSaverActive -> DashboardBadgeTone.TERTIARY
        batterySaver.enabled && batterySaver.pauseWhileCharging && powerSnapshot.isCharging ->
            DashboardBadgeTone.PRIMARY
        else -> DashboardBadgeTone.NEUTRAL
    }

    val scheduleWindowText = "${
        formatScheduleTime(darken.scheduleStartHour, darken.scheduleStartMinute)
    } – ${
        formatScheduleTime(darken.scheduleEndHour, darken.scheduleEndMinute)
    }"

    val batteryMetricValue = if (!batterySaver.enabled) {
        stringResource(R.string.state_off)
    } else {
        when (batterySaver.triggerMode) {
            BatterySaverTrigger.BATTERY_PERCENTAGE -> stringResource(
                R.string.battery_summary_below,
                batterySaver.thresholdPercent,
                batterySaver.reducedFrameRate
            )
            BatterySaverTrigger.SYSTEM_BATTERY_SAVER -> stringResource(
                R.string.battery_summary_system,
                batterySaver.reducedFrameRate
            )
            BatterySaverTrigger.EITHER -> stringResource(
                R.string.battery_summary_either,
                batterySaver.thresholdPercent,
                batterySaver.reducedFrameRate
            )
        }
    }

    val metrics = buildList {
        add(
            DashboardMetric(
                label = stringResource(R.string.darken_summary_trigger),
                value = if (darken.enabled) {
                    stringResource(darkenTriggerLabelRes(darken.triggerMode))
                } else {
                    stringResource(R.string.state_off)
                }
            )
        )
        if (darken.enabled && darken.triggerMode.usesSchedule) {
            add(
                DashboardMetric(
                    label = stringResource(R.string.darken_schedule_window),
                    value = scheduleWindowText
                )
            )
        }
        add(
            DashboardMetric(
                label = stringResource(R.string.darken_summary_battery_saver),
                value = batteryMetricValue
            )
        )
        add(
            DashboardMetric(
                label = stringResource(R.string.darken_summary_smart_detect),
                value = if (darken.autoDetectDark) {
                    stringResource(R.string.state_on)
                } else {
                    stringResource(R.string.state_off)
                }
            )
        )
        add(
            DashboardMetric(
                label = stringResource(R.string.darken_unified_level_label),
                value = stringResource(R.string.percent_value, (darken.dimFactor * 100f).roundToInt())
            )
        )
        if (darken.enabled && darken.darkenOnBatterySaver && !darken.triggerMode.usesBatterySaver) {
            add(
                DashboardMetric(
                    label = stringResource(R.string.darken_also_on_battery_title),
                    value = stringResource(R.string.state_on)
                )
            )
        }
    }

    val title = stringResource(R.string.darken_conditions_title)
    var showEditor by remember { mutableStateOf(false) }

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.DarkMode,
        cardTestTag = "darken_settings_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        badges = listOf(
            darkenBadge,
            DashboardBadge(
                text = batteryBadgeText,
                tone = batteryBadgeTone,
                testTag = "battery_saver_status_badge"
            )
        ),
        metrics = metrics
    )

    if (showEditor) {
        var draftDarken by remember(darken) { mutableStateOf(darken) }
        var draftBattery by remember(batterySaver) { mutableStateOf(batterySaver) }
        var draftThresholdSlider by remember(batterySaver.thresholdPercent) {
            mutableFloatStateOf(batterySaver.thresholdPercent.toFloat())
        }
        var editingTimeField by remember { mutableStateOf<ScheduleTimeField?>(null) }
        val basePagePreviews = remember { mutableStateMapOf<Int, Bitmap>() }
        val normalizedDarkPreviews = remember { mutableStateMapOf<Int, Bitmap>() }

        LaunchedEffect(showEditor, pages) {
            val loaded = withContext(Dispatchers.IO) {
                pages.mapNotNull { page ->
                    loadPagePreview(page.pageIndex)?.let { page.pageIndex to it }
                }
            }
            basePagePreviews.clear()
            loaded.forEach { (idx, bmp) -> basePagePreviews[idx] = bmp }
        }

        LaunchedEffect(
            showEditor,
            basePagePreviews.size,
            draftDarken.dimFactor,
            draftDarken.autoDetectDark,
            pages
        ) {
            if (basePagePreviews.isEmpty()) return@LaunchedEffect
            val computed = withContext(Dispatchers.IO) {
                pages.mapNotNull { page ->
                    val base = basePagePreviews[page.pageIndex] ?: return@mapNotNull null
                    val preview = if (draftDarken.autoDetectDark && page.isAlreadyDark) {
                        base
                    } else {
                        ImageProcessingUtils.createDarkenedCopy(base, draftDarken.dimFactor)
                    }
                    page.pageIndex to preview
                }
            }
            normalizedDarkPreviews.clear()
            computed.forEach { (idx, bmp) -> normalizedDarkPreviews[idx] = bmp }
        }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.DarkMode,
            onDismiss = { showEditor = false },
            onConfirm = {
                onCommit(
                    draftDarken,
                    draftBattery.copy(thresholdPercent = draftThresholdSlider.roundToInt())
                )
                showEditor = false
            },
            dialogTestTag = "darken_conditions_editor_dialog"
        ) {
            Text(
                text = stringResource(R.string.darken_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            EditorSwitchRow(
                title = stringResource(R.string.darken_auto_title),
                description = stringResource(R.string.darken_auto_desc),
                checked = draftDarken.enabled,
                onCheckedChange = { draftDarken = draftDarken.copy(enabled = it) },
                switchTestTag = "toggle_darken_enabled"
            )

            AnimatedVisibility(visible = draftDarken.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.darken_trigger_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    DarkenTriggerMode.values().forEach { mode ->
                        val selected = draftDarken.triggerMode == mode
                        val modeTag = when (mode) {
                            DarkenTriggerMode.SCHEDULE_ONLY -> "darken_mode_schedule_only"
                            DarkenTriggerMode.DARK_MODE_ONLY -> "darken_mode_dark_mode_only"
                            DarkenTriggerMode.BOTH_OR -> "darken_mode_both_or"
                            DarkenTriggerMode.BATTERY_SAVER_ONLY -> "darken_mode_battery_saver_only"
                        }
                        val modeIcon = when (mode) {
                            DarkenTriggerMode.SCHEDULE_ONLY -> Icons.Default.Schedule
                            DarkenTriggerMode.DARK_MODE_ONLY -> Icons.Default.Brightness4
                            DarkenTriggerMode.BOTH_OR -> Icons.Default.AutoAwesome
                            DarkenTriggerMode.BATTERY_SAVER_ONLY -> Icons.Default.BatterySaver
                        }
                        val startFmt = formatScheduleTime(
                            draftDarken.scheduleStartHour,
                            draftDarken.scheduleStartMinute
                        )
                        val endFmt = formatScheduleTime(
                            draftDarken.scheduleEndHour,
                            draftDarken.scheduleEndMinute
                        )
                        val modeSubtitle = when (mode) {
                            DarkenTriggerMode.SCHEDULE_ONLY -> stringResource(
                                R.string.darken_mode_schedule_daily,
                                startFmt,
                                endFmt
                            )
                            DarkenTriggerMode.DARK_MODE_ONLY -> if (isSystemDarkMode) {
                                stringResource(R.string.darken_mode_dark_mode_active)
                            } else {
                                stringResource(R.string.darken_mode_dark_mode_only_desc)
                            }
                            DarkenTriggerMode.BOTH_OR -> stringResource(
                                R.string.darken_mode_both_or_desc,
                                startFmt,
                                endFmt
                            )
                            DarkenTriggerMode.BATTERY_SAVER_ONLY ->
                                stringResource(R.string.darken_mode_battery_only_desc)
                        }

                        EditorRadioOptionRow(
                            title = stringResource(darkenTriggerLabelRes(mode)),
                            description = modeSubtitle,
                            icon = modeIcon,
                            selected = selected,
                            onClick = { draftDarken = draftDarken.copy(triggerMode = mode) },
                            rowTestTag = modeTag
                        )
                    }

                    AnimatedVisibility(visible = draftDarken.triggerMode.usesSchedule) {
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
                                    text = stringResource(R.string.darken_schedule_window),
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
                                            text = stringResource(R.string.darken_start_time),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatScheduleTime(
                                                draftDarken.scheduleStartHour,
                                                draftDarken.scheduleStartMinute
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
                                            text = stringResource(R.string.darken_end_time),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatScheduleTime(
                                                draftDarken.scheduleEndHour,
                                                draftDarken.scheduleEndMinute
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            val sameStartAndEnd =
                                draftDarken.scheduleStartHour == draftDarken.scheduleEndHour &&
                                    draftDarken.scheduleStartMinute == draftDarken.scheduleEndMinute
                            if (sameStartAndEnd) {
                                Text(
                                    text = stringResource(R.string.darken_same_time_error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                val isOvernight =
                                    (draftDarken.scheduleStartHour * 60 + draftDarken.scheduleStartMinute) >
                                        (draftDarken.scheduleEndHour * 60 + draftDarken.scheduleEndMinute)
                                Text(
                                    text = if (isOvernight) {
                                        stringResource(R.string.darken_overnight_hint)
                                    } else {
                                        stringResource(R.string.darken_sameday_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!draftDarken.triggerMode.usesBatterySaver) {
                        EditorSwitchRow(
                            title = stringResource(R.string.darken_also_on_battery_title),
                            description = stringResource(R.string.darken_also_on_battery_desc),
                            checked = draftDarken.darkenOnBatterySaver,
                            onCheckedChange = {
                                draftDarken = draftDarken.copy(darkenOnBatterySaver = it)
                            },
                            switchTestTag = "toggle_darken_on_battery_saver"
                        )
                    }

                    EditorSwitchRow(
                        title = stringResource(R.string.darken_smart_detect_title),
                        description = stringResource(R.string.darken_smart_detect_desc),
                        checked = draftDarken.autoDetectDark,
                        onCheckedChange = { draftDarken = draftDarken.copy(autoDetectDark = it) },
                        switchTestTag = "toggle_auto_detect_dark"
                    )

                    EditorSliderControl(
                        label = stringResource(R.string.darken_unified_level_label),
                        valueText = stringResource(
                            R.string.percent_value,
                            (draftDarken.dimFactor * 100f).roundToInt()
                        ),
                        value = draftDarken.dimFactor,
                        onValueChange = { draftDarken = draftDarken.copy(dimFactor = it) },
                        valueRange = DarkenConfig.MIN_DIM_FACTOR..DarkenConfig.MAX_DIM_FACTOR,
                        sliderTestTag = "darken_unified_level_slider"
                    )

                    Text(
                        text = stringResource(R.string.darken_unified_level_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (pages.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("darken_dialog_all_pages_preview"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.darken_all_pages_preview_label),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                pages.forEach { page ->
                                    val bmp = normalizedDarkPreviews[page.pageIndex]
                                        ?: basePagePreviews[page.pageIndex]
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(9f / 16f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = stringResource(
                                                    R.string.studio_page_title,
                                                    page.pageIndex + 1
                                                ),
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(bottomEnd = 6.dp),
                                            color = Color.Black.copy(alpha = 0.65f),
                                            modifier = Modifier.align(Alignment.TopStart)
                                        ) {
                                            Text(
                                                text = "${page.pageIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.darken_page_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("darken_page_settings_hint")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = stringResource(R.string.battery_saver_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            EditorSwitchRow(
                title = stringResource(R.string.battery_saver_toggle_title),
                description = stringResource(R.string.battery_saver_toggle_desc),
                checked = draftBattery.enabled,
                onCheckedChange = { draftBattery = draftBattery.copy(enabled = it) },
                switchTestTag = "toggle_battery_saver"
            )

            AnimatedVisibility(visible = draftBattery.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.battery_saver_trigger_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    BatterySaverTrigger.values().forEach { mode ->
                        EditorRadioOptionRow(
                            title = stringResource(batterySaverTriggerLabelRes(mode)),
                            description = stringResource(batterySaverTriggerDescRes(mode)),
                            selected = draftBattery.triggerMode == mode,
                            onClick = { draftBattery = draftBattery.copy(triggerMode = mode) },
                            rowTestTag = "battery_saver_trigger_${mode.name}"
                        )
                    }

                    AnimatedVisibility(
                        visible = draftBattery.triggerMode != BatterySaverTrigger.SYSTEM_BATTERY_SAVER
                    ) {
                        EditorSliderControl(
                            label = stringResource(R.string.battery_threshold_label),
                            valueText = stringResource(
                                R.string.percent_value,
                                draftThresholdSlider.roundToInt()
                            ),
                            value = draftThresholdSlider,
                            onValueChange = { draftThresholdSlider = it },
                            valueRange = BatterySaverConfig.MIN_THRESHOLD_PERCENT.toFloat()..
                                BatterySaverConfig.MAX_THRESHOLD_PERCENT.toFloat(),
                            sliderTestTag = "battery_saver_threshold_slider"
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.battery_reduced_fps_label),
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
                                    selected = draftBattery.reducedFrameRate == fps,
                                    onClick = {
                                        draftBattery = draftBattery.copy(reducedFrameRate = fps)
                                    },
                                    label = { Text(stringResource(R.string.fps_value, fps)) },
                                    modifier = Modifier.testTag("battery_saver_fps_$fps")
                                )
                            }
                        }
                    }

                    EditorSwitchRow(
                        title = stringResource(R.string.battery_pause_charging_title),
                        description = stringResource(
                            R.string.battery_pause_charging_desc,
                            normalFrameRate
                        ),
                        checked = draftBattery.pauseWhileCharging,
                        onCheckedChange = {
                            draftBattery = draftBattery.copy(pauseWhileCharging = it)
                        },
                        switchTestTag = "toggle_battery_saver_pause_charging"
                    )
                }
            }
        }

        editingTimeField?.let { field ->
            val initialHour = if (field == ScheduleTimeField.START) {
                draftDarken.scheduleStartHour
            } else {
                draftDarken.scheduleEndHour
            }
            val initialMinute = if (field == ScheduleTimeField.START) {
                draftDarken.scheduleStartMinute
            } else {
                draftDarken.scheduleEndMinute
            }
            ScheduleTimePickerDialog(
                title = if (field == ScheduleTimeField.START) {
                    stringResource(R.string.darken_pick_start_title)
                } else {
                    stringResource(R.string.darken_pick_end_title)
                },
                initialHour = initialHour,
                initialMinute = initialMinute,
                onDismiss = { editingTimeField = null },
                onConfirm = { hour, minute ->
                    draftDarken = if (field == ScheduleTimeField.START) {
                        draftDarken.copy(scheduleStartHour = hour, scheduleStartMinute = minute)
                    } else {
                        draftDarken.copy(scheduleEndHour = hour, scheduleEndMinute = minute)
                    }
                    editingTimeField = null
                }
            )
        }
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
                            stringResource(R.string.darken_switch_clock_cd)
                        } else {
                            stringResource(R.string.darken_switch_keyboard_cd)
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
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("darken_time_picker_cancel")
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun formatScheduleTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

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
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
