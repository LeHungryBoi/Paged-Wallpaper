package com.example.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.ceil

class WallpaperSignalOverlayWindow(
    private val context: Context
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var overlayView: SignalOverlayView? = null
    private var attached = false
    private var lastLines: List<String> = emptyList()

    val isAttached: Boolean
        get() = attached

    fun canDrawOverlays(): Boolean = canDrawOverlays(context)

    fun update(enabled: Boolean, lines: List<String>) {
        if (!enabled || lines.isEmpty() || !canDrawOverlays()) {
            detach()
            return
        }
        val wm = windowManager ?: return
        if (!attached || overlayView == null) {
            val view = SignalOverlayView(context).also { overlayView = it }
            view.lines = lines
            lastLines = lines
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val density = context.resources.displayMetrics.density
            val safeTopPx = resolveSafeTopMarginPx(context, density)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = safeTopPx
                dimAmount = 0f
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            try {
                wm.addView(view, params)
                attached = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach floating signal overlay", e)
                overlayView = null
                attached = false
            }
            return
        }

        if (lines != lastLines) {
            lastLines = lines
            overlayView?.let { view ->
                val prevCount = view.lines.size
                view.lines = lines
                if (prevCount != lines.size) {
                    view.requestLayout()
                }
                view.invalidate()
            }
        }
    }

    fun detach() {
        val view = overlayView ?: return
        overlayView = null
        attached = false
        lastLines = emptyList()
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    private class SignalOverlayView(context: Context) : View(context) {
        var lines: List<String> = emptyList()
        private val density = context.resources.displayMetrics.density
        private val cardBounds = RectF()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(232, 15, 23, 42)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 56, 189, 248)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(125, 211, 252)
            textSize = 11.5f * density
            isFakeBoldText = true
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f * density
            typeface = Typeface.MONOSPACE
        }

        private fun wrappedLinesForWidth(cardWidthPx: Float, padPx: Float): List<Pair<String, Int>> {
            val maxTextWidth = (cardWidthPx - padPx * 2f).coerceAtLeast(60f)
            val result = ArrayList<Pair<String, Int>>(lines.size)
            lines.forEachIndexed { originalIndex, rawLine ->
                if (rawLine.isEmpty()) {
                    result += "" to originalIndex
                } else {
                    var start = 0
                    while (start < rawLine.length) {
                        val count = linePaint.breakText(
                            rawLine,
                            start,
                            rawLine.length,
                            true,
                            maxTextWidth,
                            null
                        ).coerceAtLeast(1)
                        val chunk = rawLine.substring(start, start + count)
                        result += (if (start == 0) chunk else "  $chunk") to originalIndex
                        start += count
                    }
                }
            }
            return result
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val displayWidth = context.resources.displayMetrics.widthPixels
            val cardWidth = minOf(
                356f * density,
                (displayWidth - 20f * density).coerceAtLeast(180f)
            )
            val padPx = 11f * density
            val headerHeightPx = 20f * density
            val lineHeightPx = 15f * density
            val displayLines = wrappedLinesForWidth(cardWidth, padPx)
            val cardHeight =
                padPx * 2f + headerHeightPx + displayLines.size.coerceAtLeast(1) * lineHeightPx
            setMeasuredDimension(
                ceil(cardWidth).toInt().coerceAtLeast(1),
                ceil(cardHeight).toInt().coerceAtLeast(1)
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (lines.isEmpty()) return
            val padPx = 11f * density
            val headerHeightPx = 20f * density
            val lineHeightPx = 15f * density
            val w = width.toFloat()
            val h = height.toFloat()
            cardBounds.set(0f, 0f, w, h)
            canvas.drawRoundRect(cardBounds, 14f * density, 14f * density, bgPaint)
            cardBounds.set(density, density, w - density, h - density)
            canvas.drawRoundRect(cardBounds, 14f * density, 14f * density, borderPaint)

            canvas.drawText(
                "Live Signal Diagnostics (Overlay)",
                padPx,
                padPx + 11f * density,
                titlePaint
            )

            var y = padPx + headerHeightPx + 10f * density
            val displayLines = wrappedLinesForWidth(w, padPx)
            displayLines.forEach { (lineText, originalIndex) ->
                val rawLine = lines.getOrNull(originalIndex).orEmpty()
                linePaint.color = when {
                    originalIndex < 2 -> Color.rgb(167, 243, 208)
                    originalIndex == 2 && rawLine.startsWith("CMD:") -> Color.rgb(253, 230, 138)
                    else -> Color.rgb(226, 232, 240)
                }
                canvas.drawText(lineText, padPx, y, linePaint)
                y += lineHeightPx
            }
        }
    }

    companion object {
        private const val TAG = "SignalOverlayWindow"

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        private fun resolveSafeTopMarginPx(context: Context, density: Float): Int {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarPx = if (resId > 0) {
                context.resources.getDimensionPixelSize(resId)
            } else {
                ceil(36f * density).toInt()
            }
            return statusBarPx + ceil(16f * density).toInt()
        }
    }
}
