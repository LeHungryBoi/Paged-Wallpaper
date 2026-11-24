package com.example.darken

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTriggerMode
import java.util.Calendar

object ExtraDimDetector {

    fun isSystemDarkThemeActive(configuration: Configuration): Boolean {
        val currentNightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun isSystemDarkThemeActive(context: Context): Boolean =
        isSystemDarkThemeActive(context.resources.configuration)

    fun isScheduledActive(
        config: DarkenConfig,
        hourOfDay: Int,
        minute: Int
    ): Boolean {
        val safe = config.normalized()
        val currentMinutes = hourOfDay.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        val startMinutes = safe.scheduleStartHour * 60 + safe.scheduleStartMinute
        val endMinutes = safe.scheduleEndHour * 60 + safe.scheduleEndMinute

        if (startMinutes == endMinutes) return false

        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            // Overnight window (e.g. 20:00 to 07:00)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun isScheduledActive(config: DarkenConfig): Boolean {
        val now = Calendar.getInstance()
        return isScheduledActive(
            config = config,
            hourOfDay = now.get(Calendar.HOUR_OF_DAY),
            minute = now.get(Calendar.MINUTE)
        )
    }

    fun isDimConditionActive(
        context: Context,
        config: DarkenConfig,
        isDarkModeOverride: Boolean? = null,
        hourOfDay: Int? = null,
        minute: Int? = null
    ): Boolean {
        if (!config.enabled) return false
        val darkModeActive = config.triggerMode.usesDarkMode &&
            (isDarkModeOverride ?: isSystemDarkThemeActive(context))
        val scheduleActive = config.triggerMode.usesSchedule &&
            if (hourOfDay != null && minute != null) {
                isScheduledActive(config, hourOfDay, minute)
            } else {
                isScheduledActive(config)
            }
        return when (config.triggerMode) {
            DarkenTriggerMode.SCHEDULE_ONLY -> scheduleActive
            DarkenTriggerMode.DARK_MODE_ONLY -> darkModeActive
            DarkenTriggerMode.BOTH_OR -> darkModeActive || scheduleActive
        }
    }

    /**
     * Samples the bitmap to calculate its perceived relative luminance (0.0f = pure black, 1.0f = pure white)
     */
    fun calculateLuminance(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0.5f

        val samplePoints = 20
        var totalLuminance = 0.0
        var sampleCount = 0

        val stepX = (width / (samplePoints + 1)).coerceAtLeast(1)
        val stepY = (height / (samplePoints + 1)).coerceAtLeast(1)

        for (x in stepX until width step stepX) {
            for (y in stepY until height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel) / 255.0
                val g = Color.green(pixel) / 255.0
                val b = Color.blue(pixel) / 255.0
                // ITU-R BT.709 relative luminance formula
                val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
                totalLuminance += lum
                sampleCount++
            }
        }

        return if (sampleCount > 0) (totalLuminance / sampleCount).toFloat() else 0.5f
    }
}

