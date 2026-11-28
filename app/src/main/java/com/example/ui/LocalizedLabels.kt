package com.example.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.model.BatterySaverTrigger
import com.example.data.model.BlurRainbowStyle
import com.example.data.model.BlurStyle
import com.example.data.model.BlurTexturePattern
import com.example.data.model.BlurTintMode
import com.example.data.model.CropFillMode
import com.example.data.model.DarkenTriggerMode
import com.example.data.model.OffsetEasingStyle

@StringRes
fun transitionNameRes(transitionId: String): Int = when (transitionId.lowercase()) {
    "scroll" -> R.string.transition_scroll_name
    "parallax" -> R.string.transition_parallax_name
    "parallax_classic" -> R.string.transition_parallax_classic_name
    "swipe_reveal" -> R.string.transition_swipe_reveal_name
    "swipe_reveal_stacked" -> R.string.transition_swipe_reveal_stacked_name
    "fade" -> R.string.transition_fade_name
    "soft_blur" -> R.string.transition_soft_blur_name
    "depth" -> R.string.transition_depth_name
    "slide_over" -> R.string.transition_slide_over_name
    "cube" -> R.string.transition_cube_name
    else -> R.string.transition_scroll_name
}

@Composable
fun localizedTransitionName(transitionId: String): String =
    stringResource(transitionNameRes(transitionId))

@StringRes
fun offsetEasingStyleLabelRes(style: OffsetEasingStyle): Int = when (style) {
    OffsetEasingStyle.SYMMETRIC -> R.string.behavior_easing_symmetric
    OffsetEasingStyle.FAST_START_SLOW_END -> R.string.behavior_easing_fast_slow
}

@StringRes
fun darkenTriggerLabelRes(mode: DarkenTriggerMode): Int = when (mode) {
    DarkenTriggerMode.SCHEDULE_ONLY -> R.string.darken_mode_schedule_only
    DarkenTriggerMode.DARK_MODE_ONLY -> R.string.darken_mode_dark_mode_only
    DarkenTriggerMode.BOTH_OR -> R.string.darken_mode_both_or
    DarkenTriggerMode.BATTERY_SAVER_ONLY -> R.string.darken_mode_battery_only
}

@StringRes
fun batterySaverTriggerLabelRes(trigger: BatterySaverTrigger): Int = when (trigger) {
    BatterySaverTrigger.BATTERY_PERCENTAGE -> R.string.battery_trigger_percentage
    BatterySaverTrigger.SYSTEM_BATTERY_SAVER -> R.string.battery_trigger_system
    BatterySaverTrigger.EITHER -> R.string.battery_trigger_either
}

@StringRes
fun batterySaverTriggerDescRes(trigger: BatterySaverTrigger): Int = when (trigger) {
    BatterySaverTrigger.BATTERY_PERCENTAGE -> R.string.battery_trigger_percentage_desc
    BatterySaverTrigger.SYSTEM_BATTERY_SAVER -> R.string.battery_trigger_system_desc
    BatterySaverTrigger.EITHER -> R.string.battery_trigger_either_desc
}

@StringRes
fun cropFillModeLabelRes(mode: CropFillMode): Int = when (mode) {
    CropFillMode.CROP -> R.string.crop_fill_crop
    CropFillMode.BLUR -> R.string.crop_fill_blur
    CropFillMode.GLASS -> R.string.crop_fill_glass
}

@StringRes
fun blurStyleShortLabelRes(style: BlurStyle): Int = when (style) {
    BlurStyle.SMOOTH_GAUSSIAN -> R.string.crop_blur_gaussian
    BlurStyle.WIN7_AERO -> R.string.crop_blur_win7
    BlurStyle.MODERN_ACRYLIC -> R.string.crop_blur_acrylic
    BlurStyle.SMOKED_GLASS -> R.string.crop_blur_smoked
    BlurStyle.SILKY_VIGNETTE -> R.string.crop_blur_vignette
    BlurStyle.BOKEH_BLOOM -> R.string.crop_blur_bokeh
    BlurStyle.PRISM_REFRACTION -> R.string.crop_blur_prism
}

@StringRes
fun blurStyleDescRes(style: BlurStyle): Int = when (style) {
    BlurStyle.SMOOTH_GAUSSIAN -> R.string.crop_blur_gaussian_desc
    BlurStyle.WIN7_AERO -> R.string.crop_blur_win7_desc
    BlurStyle.MODERN_ACRYLIC -> R.string.crop_blur_acrylic_desc
    BlurStyle.SMOKED_GLASS -> R.string.crop_blur_smoked_desc
    BlurStyle.SILKY_VIGNETTE -> R.string.crop_blur_vignette_desc
    BlurStyle.BOKEH_BLOOM -> R.string.crop_blur_bokeh_desc
    BlurStyle.PRISM_REFRACTION -> R.string.crop_blur_prism_desc
}

@StringRes
fun blurTexturePatternLabelRes(pattern: BlurTexturePattern): Int = when (pattern) {
    BlurTexturePattern.NONE -> R.string.crop_texture_none
    BlurTexturePattern.FINE_GRID -> R.string.crop_texture_grid
    BlurTexturePattern.DIAGONAL_RIBS -> R.string.crop_texture_diagonal
    BlurTexturePattern.DOT_MATRIX -> R.string.crop_texture_dots
    BlurTexturePattern.DIAMOND -> R.string.crop_texture_diamond
}

@StringRes
fun blurTintModeLabelRes(mode: BlurTintMode): Int = when (mode) {
    BlurTintMode.NONE -> R.string.crop_tint_none
    BlurTintMode.SOLID -> R.string.crop_tint_solid
    BlurTintMode.RAINBOW -> R.string.crop_tint_rainbow
}

@StringRes
fun blurRainbowStyleLabelRes(style: BlurRainbowStyle): Int = when (style) {
    BlurRainbowStyle.DIAGONAL_PRISM -> R.string.crop_rainbow_diagonal
    BlurRainbowStyle.VERTICAL_AURA -> R.string.crop_rainbow_vertical
    BlurRainbowStyle.RADIAL_IRIDESCENCE -> R.string.crop_rainbow_radial
    BlurRainbowStyle.NEON_SUNSET -> R.string.crop_rainbow_aurora
}
