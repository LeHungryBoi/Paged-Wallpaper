package com.example.service

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.data.model.CalibrationConfig
import com.example.data.model.PageItem
import kotlin.math.abs

/**
 * Pure policy & WallpaperColors builder that keeps status bar and launcher text color
 * consistent with PaperPages' per-page Already dark flag and unified darkening state.
 */
object WallpaperColorHelper {

    data class ColorStateKey(
        val pageIndex: Int,
        val isAlreadyDark: Boolean,
        val isDarkened: Boolean,
        val bitmapGeneration: Long,
        val darkenedGeneration: Long
    )

    data class ColorHintDecision(
        val allowDarkTextHint: Boolean,
        val suggestDarkThemeHint: Boolean
    )

    fun nearestPageIndex(offset: Float, calibration: CalibrationConfig): Int {
        val count = calibration.pageCount.coerceAtLeast(1)
        val anchors = if (calibration.customOffsets.size >= count) {
            calibration.customOffsets.take(count)
        } else {
            List(count) { index ->
                if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()
            }
        }
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        anchors.forEachIndexed { index, anchor ->
            val distance = abs(offset.coerceIn(0f, 1f) - anchor)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    /**
     * Computes OS text/theme decoration hints for a settled page.
     * Any page flagged [PageItem.isAlreadyDark] or actively darkened must NEVER emit
     * [WallpaperColors.HINT_SUPPORTS_DARK_TEXT], ensuring status bar & launcher labels remain white.
     */
    fun decideHints(
        page: PageItem?,
        isDarkened: Boolean,
        topBandLuminance: Float,
        overallLuminance: Float
    ): ColorHintDecision {
        if (page?.isAlreadyDark == true || isDarkened) {
            return ColorHintDecision(
                allowDarkTextHint = false,
                suggestDarkThemeHint = true
            )
        }
        val isBrightTop = topBandLuminance >= 0.72f && overallLuminance >= 0.60f
        return ColorHintDecision(
            allowDarkTextHint = isBrightTop,
            suggestDarkThemeHint = overallLuminance < 0.40f
        )
    }

    /**
     * Samples the top status-bar band (top 14%) and overall bitmap luminance.
     * Returns Pair(topBandLuminance, overallLuminance) in 0f..1f.
     */
    fun sampleTopAndOverallLuminance(bitmap: Bitmap): Pair<Float, Float> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0.2f to 0.2f

        val topBandHeight = (h * 0.14f).toInt().coerceIn(1, h)
        val stepX = (w / 16).coerceAtLeast(1)
        val stepTopY = (topBandHeight / 6).coerceAtLeast(1)
        val stepAllY = (h / 16).coerceAtLeast(1)

        var topSum = 0.0
        var topCount = 0
        var y = 0
        while (y < topBandHeight) {
            var x = 0
            while (x < w) {
                topSum += pixelLuminance(bitmap.getPixel(x, y))
                topCount++
                x += stepX
            }
            y += stepTopY
        }

        var allSum = 0.0
        var allCount = 0
        y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                allSum += pixelLuminance(bitmap.getPixel(x, y))
                allCount++
                x += stepX
            }
            y += stepAllY
        }

        val topLum = if (topCount > 0) (topSum / topCount).toFloat() else 0.2f
        val allLum = if (allCount > 0) (allSum / allCount).toFloat() else 0.2f
        return topLum to allLum
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    fun computeWallpaperColors(
        bitmap: Bitmap?,
        page: PageItem?,
        isDarkened: Boolean
    ): WallpaperColors {
        val (topLum, overallLum) = bitmap?.let(::sampleTopAndOverallLuminance) ?: (0.15f to 0.15f)
        val hints = decideHints(
            page = page,
            isDarkened = isDarkened,
            topBandLuminance = topLum,
            overallLuminance = overallLum
        )

        val baseColors = if (bitmap != null && !bitmap.isRecycled) {
            WallpaperColors.fromBitmap(bitmap)
        } else {
            WallpaperColors(
                Color.valueOf(Color.rgb(18, 22, 34)),
                null,
                null
            )
        }

        // Force dark primary color when dark decoration is required so Android 8.1-11 (no hints ctor)
        // never infers a light background and flips status bar text to black.
        val rawPrimary = baseColors.primaryColor
        val primaryColor = if (!hints.allowDarkTextHint && rawPrimary.luminance() > 0.35f) {
            Color.valueOf(Color.rgb(18, 22, 34))
        } else if (hints.allowDarkTextHint && rawPrimary.luminance() < 0.75f) {
            Color.valueOf(Color.rgb(245, 247, 250))
        } else {
            rawPrimary
        }
        val secondaryColor = baseColors.secondaryColor?.let { c ->
            if (!hints.allowDarkTextHint && c.luminance() > 0.45f) {
                Color.valueOf(Color.rgb(30, 36, 52))
            } else {
                c
            }
        }
        val tertiaryColor = baseColors.tertiaryColor?.let { c ->
            if (!hints.allowDarkTextHint && c.luminance() > 0.45f) {
                Color.valueOf(Color.rgb(42, 48, 66))
            } else {
                c
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var colorHints = 0
            if (hints.allowDarkTextHint) {
                colorHints = colorHints or WallpaperColors.HINT_SUPPORTS_DARK_TEXT
            }
            if (hints.suggestDarkThemeHint) {
                colorHints = colorHints or WallpaperColors.HINT_SUPPORTS_DARK_THEME
            }
            WallpaperColors(primaryColor, secondaryColor, tertiaryColor, colorHints)
        } else {
            WallpaperColors(primaryColor, secondaryColor, tertiaryColor)
        }
    }

    private fun pixelLuminance(pixel: Int): Double {
        val r = Color.red(pixel) / 255.0
        val g = Color.green(pixel) / 255.0
        val b = Color.blue(pixel) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
