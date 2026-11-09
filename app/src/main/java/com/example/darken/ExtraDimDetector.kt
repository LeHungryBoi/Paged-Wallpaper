package com.example.darken

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.data.model.DarkenConfig
import com.example.data.model.DarkenTrigger
import java.util.Calendar

object ExtraDimDetector {
    // Android standard setting key for Extra Dim / Reduce Bright Colors (Android 12+)
    private const val REDUCE_BRIGHT_COLORS_ACTIVATED = "reduce_bright_colors_activated"

    fun isExtraDimActive(context: Context): Boolean {
        return try {
            val activated = Settings.Secure.getInt(
                context.contentResolver,
                REDUCE_BRIGHT_COLORS_ACTIVATED,
                0
            )
            activated == 1
        } catch (e: Exception) {
            false
        }
    }

    fun isSystemDarkThemeActive(context: Context): Boolean {
        val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun isScheduledActive(config: DarkenConfig): Boolean {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = config.scheduleStartHour * 60 + config.scheduleStartMinute
        val endMinutes = config.scheduleEndHour * 60 + config.scheduleEndMinute

        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            // Overnights (e.g. 20:00 to 07:00)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun isDimConditionActive(context: Context, config: DarkenConfig): Boolean {
        return when (config.triggerMode) {
            DarkenTrigger.OFF -> false
            DarkenTrigger.EXTRA_DIM -> isExtraDimActive(context)
            DarkenTrigger.DARK_THEME -> isSystemDarkThemeActive(context)
            DarkenTrigger.SCHEDULED -> isScheduledActive(config)
            DarkenTrigger.ALWAYS -> true
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

    fun registerObserver(context: Context, onChange: () -> Unit): ContentObserver? {
        return try {
            val uri: Uri = Settings.Secure.getUriFor(REDUCE_BRIGHT_COLORS_ACTIVATED)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    onChange()
                }
            }
            context.contentResolver.registerContentObserver(uri, false, observer)
            observer
        } catch (e: Exception) {
            null
        }
    }

    fun unregisterObserver(context: Context, observer: ContentObserver?) {
        if (observer != null) {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
