package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.example.data.model.BlurRainbowStyle
import com.example.data.model.BlurStyle
import com.example.data.model.BlurTintMode
import com.example.data.model.CropFillMode
import com.example.data.model.PageItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageProcessingUtils {

    private val acrylicGrainShader: BitmapShader by lazy {
        val tileSize = 128
        val tile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(tileSize * tileSize)
        var seed = 0x5DEECE66DL
        for (i in pixels.indices) {
            seed = (seed * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1L)
            val v = ((seed ushr 16) and 0xFFL).toInt()
            // Subtle high-frequency monochrome grain centered around neutral transparent
            val alpha = if (v and 1 == 0) (v % 34) else 0
            val lum = if ((v and 2) == 0) 255 else 18
            pixels[i] = Color.argb(alpha, lum, lum, lum)
        }
        tile.setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)
        BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /**
     * Renders a complete high-resolution wallpaper bitmap with the user's chosen fit mode,
     * blur style, and color/rainbow tinting.
     */
    fun renderWallpaperBitmap(
        srcBmp: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        fitMode: CropFillMode,
        panXNormalized: Float, // -1f to 1f
        panYNormalized: Float, // -1f to 1f
        scale: Float,          // 1f to 3f
        blurRadius: Int,       // 0..40+
        blurStyle: BlurStyle = if (fitMode == CropFillMode.GLASS) BlurStyle.WIN7_AERO else BlurStyle.SMOOTH_GAUSSIAN,
        blurTintMode: BlurTintMode = BlurTintMode.NONE,
        blurTintColor: Int = PageItem.DEFAULT_BLUR_TINT_COLOR,
        blurTintIntensity: Float = 0.42f,
        blurRainbowStyle: BlurRainbowStyle = BlurRainbowStyle.DIAGONAL_PRISM
    ): Bitmap {
        val outBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val srcW = srcBmp.width.toFloat()
        val srcH = srcBmp.height.toFloat()
        val targetW = targetWidth.toFloat()
        val targetH = targetHeight.toFloat()

        when (fitMode) {
            CropFillMode.CROP -> {
                val targetRatio = targetW / targetH
                val srcRatio = srcW / srcH

                val baseW: Float
                val baseH: Float
                if (srcRatio > targetRatio) {
                    baseH = srcH
                    baseW = baseH * targetRatio
                } else {
                    baseW = srcW
                    baseH = baseW / targetRatio
                }

                val effectiveCropW = (baseW / scale.coerceAtLeast(1.0f)).coerceIn(10f, srcW)
                val effectiveCropH = (baseH / scale.coerceAtLeast(1.0f)).coerceIn(10f, srcH)

                val maxPanX = (srcW - effectiveCropW) / 2f
                val maxPanY = (srcH - effectiveCropH) / 2f

                val centerX = srcW / 2f + panXNormalized * maxPanX
                val centerY = srcH / 2f + panYNormalized * maxPanY

                val left = (centerX - effectiveCropW / 2f).toInt().coerceIn(0, (srcW - effectiveCropW).toInt().coerceAtLeast(0))
                val top = (centerY - effectiveCropH / 2f).toInt().coerceIn(0, (srcH - effectiveCropH).toInt().coerceAtLeast(0))
                val right = (left + effectiveCropW).toInt().coerceAtMost(srcBmp.width)
                val bottom = (top + effectiveCropH).toInt().coerceAtMost(srcBmp.height)

                val srcRect = Rect(left, top, right, bottom)
                val dstRect = Rect(0, 0, targetWidth, targetHeight)
                canvas.drawBitmap(srcBmp, srcRect, dstRect, paint)
            }

            CropFillMode.BLUR, CropFillMode.GLASS -> {
                val blurAmount = (blurRadius.toFloat() / 40f).coerceIn(0f, 1f)
                val centerCrop = computeCenterCropRect(srcBmp.width, srcBmp.height, targetWidth, targetHeight)
                drawStyledBlurredBackdrop(
                    canvas = canvas,
                    srcBmp = srcBmp,
                    srcRect = centerCrop,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    blurAmount = blurAmount,
                    blurStyle = blurStyle,
                    blurTintMode = blurTintMode,
                    blurTintColor = blurTintColor,
                    blurTintIntensity = blurTintIntensity,
                    blurRainbowStyle = blurRainbowStyle
                )

                val fitScale = minOf(targetW / srcW, targetH / srcH) * scale
                val fgW = srcW * fitScale
                val fgH = srcH * fitScale

                val maxPanX = max(0f, (fgW - targetW) / 2f)
                val maxPanY = max(0f, (fgH - targetH) / 2f)

                val centerX = targetW / 2f + panXNormalized * maxPanX
                val centerY = targetH / 2f + panYNormalized * maxPanY

                val fgDstRectF = RectF(
                    centerX - fgW / 2f,
                    centerY - fgH / 2f,
                    centerX + fgW / 2f,
                    centerY + fgH / 2f
                )

                drawForegroundShadow(canvas, fgDstRectF)
                canvas.drawBitmap(srcBmp, null, fgDstRectF, paint)
            }
        }

        return outBmp
    }

    /**
     * Renders only the stylized, blurred, and tinted backdrop into a new Bitmap.
     * Used by the live page editor preview so preview and saved wallpaper share the exact same pipeline.
     */
    fun renderBackdropPreviewBitmap(
        srcBmp: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        blurAmount: Float,
        blurStyle: BlurStyle,
        blurTintMode: BlurTintMode,
        blurTintColor: Int,
        blurTintIntensity: Float,
        blurRainbowStyle: BlurRainbowStyle
    ): Bitmap {
        val safeW = targetWidth.coerceAtLeast(16)
        val safeH = targetHeight.coerceAtLeast(16)
        val outBmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)
        val srcRect = computeCenterCropRect(srcBmp.width, srcBmp.height, safeW, safeH)
        drawStyledBlurredBackdrop(
            canvas = canvas,
            srcBmp = srcBmp,
            srcRect = srcRect,
            targetWidth = safeW,
            targetHeight = safeH,
            blurAmount = blurAmount,
            blurStyle = blurStyle,
            blurTintMode = blurTintMode,
            blurTintColor = blurTintColor,
            blurTintIntensity = blurTintIntensity,
            blurRainbowStyle = blurRainbowStyle
        )
        return outBmp
    }

    /**
     * Creates a genuinely smooth blurred copy of [srcBmp] without mosaic aliasing.
     */
    fun createSmoothBlurredCopy(
        srcBmp: Bitmap,
        maxDimension: Int = 320,
        blurRadius: Int = 18
    ): Bitmap {
        val longEdge = max(srcBmp.width, srcBmp.height).coerceAtLeast(1)
        val scale = min(1f, maxDimension.toFloat() / longEdge)
        val workW = (srcBmp.width * scale).roundToInt().coerceAtLeast(4)
        val workH = (srcBmp.height * scale).roundToInt().coerceAtLeast(4)
        val workBmp = Bitmap.createScaledBitmap(srcBmp, workW, workH, true)
        val mutable = if (workBmp.isMutable && workBmp.config == Bitmap.Config.ARGB_8888) {
            workBmp
        } else {
            workBmp.copy(Bitmap.Config.ARGB_8888, true).also {
                if (workBmp !== srcBmp) workBmp.recycle()
            }
        }
        val safeRadius = blurRadius.coerceIn(1, (min(workW, workH) / 2).coerceAtLeast(1))
        fastBlur(mutable, safeRadius)
        fastBlur(mutable, (safeRadius * 0.65f).roundToInt().coerceAtLeast(1))
        return mutable
    }

    /**
     * Draws a soft ambient shadow behind the sharp foreground image so it separates cleanly
     * from bright Aero, Acrylic, or Rainbow backdrops.
     */
    fun drawForegroundShadow(canvas: Canvas, dstRect: RectF, shadowAlpha: Int = 68) {
        if (dstRect.width() <= 4f || dstRect.height() <= 4f) return
        val blurRadius = (min(dstRect.width(), dstRect.height()) * 0.028f).coerceIn(6f, 28f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(shadowAlpha.coerceIn(0, 255), 0, 0, 0)
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        val offsetY = blurRadius * 0.25f
        val expanded = RectF(
            dstRect.left - blurRadius * 0.15f,
            dstRect.top + offsetY,
            dstRect.right + blurRadius * 0.15f,
            dstRect.bottom + offsetY * 1.6f
        )
        canvas.drawRect(expanded, shadowPaint)
    }

    /**
     * Draws an artifact-free, multi-pass blurred and stylized backdrop onto [canvas].
     * All effects are baked into the target canvas so zero per-frame blur occurs during live playback.
     */
    fun drawStyledBlurredBackdrop(
        canvas: Canvas,
        srcBmp: Bitmap,
        srcRect: Rect?,
        targetWidth: Int,
        targetHeight: Int,
        blurAmount: Float,
        blurStyle: BlurStyle,
        blurTintMode: BlurTintMode,
        blurTintColor: Int,
        blurTintIntensity: Float,
        blurRainbowStyle: BlurRainbowStyle
    ) {
        val safeW = targetWidth.coerceAtLeast(2)
        val safeH = targetHeight.coerceAtLeast(2)
        val clampedBlur = blurAmount.coerceIn(0f, 1f)

        // Use a high-detail working buffer (360px-512px long edge) instead of 6% mosaic downscaling.
        val targetLongEdge = max(safeW, safeH).coerceAtLeast(1)
        val desiredMaxWorkEdge = if (clampedBlur <= 0.01f) {
            min(targetLongEdge.toFloat(), 1080f)
        } else {
            min(targetLongEdge.toFloat(), 512f - clampedBlur * 152f)
        }
        val workScale = (desiredMaxWorkEdge / targetLongEdge.toFloat()).coerceIn(0.15f, 1f)
        val workW = (safeW * workScale).roundToInt().coerceAtLeast(8)
        val workH = (safeH * workScale).roundToInt().coerceAtLeast(8)

        val workBmp = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        var bloomBmp: Bitmap? = null
        try {
            val workCanvas = Canvas(workBmp)
            val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            workCanvas.drawBitmap(srcBmp, srcRect, Rect(0, 0, workW, workH), samplePaint)

            // Apply genuine multi-pass Stack Blur (2 passes approximate a smooth Gaussian curve).
            if (clampedBlur > 0.01f) {
                val maxSafeRadius = (min(workW, workH) / 2 - 1).coerceAtLeast(1)
                val primaryRadius = (2f + clampedBlur * 26f).roundToInt().coerceIn(1, maxSafeRadius)
                fastBlur(workBmp, primaryRadius)
                if (clampedBlur > 0.12f) {
                    val secondaryRadius = (primaryRadius * 0.65f).roundToInt().coerceIn(1, maxSafeRadius)
                    fastBlur(workBmp, secondaryRadius)
                }
            }

            val fullDst = Rect(0, 0, safeW, safeH)
            val wF = safeW.toFloat()
            val hF = safeH.toFloat()

            when (blurStyle) {
                BlurStyle.SMOOTH_GAUSSIAN -> {
                    canvas.drawBitmap(workBmp, null, fullDst, samplePaint)
                }

                BlurStyle.WIN7_AERO -> {
                    // 1. Vibrant glass color boost
                    val aeroPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(
                            ColorMatrix().apply { setSaturation(1.24f) }
                        )
                    }
                    canvas.drawBitmap(workBmp, null, fullDst, aeroPaint)

                    // 2. Subtle dark contrast + cool translucent Aero glass base wash
                    canvas.drawColor(Color.argb(24, 8, 18, 34))
                    canvas.drawColor(Color.argb(34, 198, 228, 255))

                    // 3. Classic Win7 diagonal glossy specular light streaks
                    val primaryStreak = LinearGradient(
                        0f, 0f, wF * 0.88f, hF * 0.72f,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb(18, 255, 255, 255),
                            Color.argb(58, 255, 255, 255),
                            Color.argb(20, 225, 244, 255),
                            Color.TRANSPARENT,
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.22f, 0.34f, 0.44f, 0.56f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = primaryStreak })

                    val secondaryStreak = LinearGradient(
                        wF * 0.25f, hF * 0.12f, wF, hF * 0.95f,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb(30, 255, 255, 255),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0.45f, 0.56f, 0.72f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = secondaryStreak })

                    // 4. Soft top-edge glass bevel sheen
                    val topSheen = LinearGradient(
                        0f, 0f, 0f, hF * 0.18f,
                        Color.argb(54, 255, 255, 255),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF * 0.18f, Paint().apply { shader = topSheen })
                }

                BlurStyle.MODERN_ACRYLIC -> {
                    // 1. Rich Fluent / macOS vibrancy boost
                    val acrylicPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(
                            ColorMatrix().apply { setSaturation(1.32f) }
                        )
                    }
                    canvas.drawBitmap(workBmp, null, fullDst, acrylicPaint)

                    // 2. Balanced frosted luminosity veil
                    canvas.drawColor(Color.argb(26, 245, 248, 255))
                    canvas.drawColor(Color.argb(28, 16, 20, 30))

                    // 3. Micro-fine tileable Acrylic frost noise grain
                    val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        shader = acrylicGrainShader
                        alpha = 180
                    }
                    canvas.drawRect(0f, 0f, wF, hF, grainPaint)
                }

                BlurStyle.SILKY_VIGNETTE -> {
                    canvas.drawBitmap(workBmp, null, fullDst, samplePaint)

                    val centerRadius = max(wF, hF) * 0.55f
                    val centerGlow = RadialGradient(
                        wF * 0.5f, hF * 0.48f, centerRadius,
                        Color.argb(30, 255, 255, 255),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = centerGlow })

                    val outerVignette = RadialGradient(
                        wF * 0.5f, hF * 0.5f, max(wF, hF) * 0.72f,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb(40, 6, 8, 14),
                            Color.argb(150, 4, 6, 12)
                        ),
                        floatArrayOf(0.35f, 0.72f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = outerVignette })
                }

                BlurStyle.BOKEH_BLOOM -> {
                    canvas.drawBitmap(workBmp, null, fullDst, samplePaint)

                    bloomBmp = createHighlightBloomBitmap(workBmp)
                    val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                        alpha = 155
                    }
                    canvas.drawBitmap(bloomBmp, null, fullDst, bloomPaint)
                }

                BlurStyle.PRISM_REFRACTION -> {
                    canvas.drawBitmap(workBmp, null, fullDst, samplePaint)

                    val shiftX = wF * 0.012f
                    val shiftY = hF * 0.006f
                    val redShiftMatrix = ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, 0f,
                            0f, 0.25f, 0f, 0f, 0f,
                            0f, 0f, 0.1f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    val cyanShiftMatrix = ColorMatrix(
                        floatArrayOf(
                            0.1f, 0f, 0f, 0f, 0f,
                            0f, 0.75f, 0f, 0f, 0f,
                            0f, 0f, 1f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )

                    val redPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(redShiftMatrix)
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                        alpha = 68
                    }
                    canvas.drawBitmap(
                        workBmp,
                        null,
                        RectF(-shiftX, -shiftY, wF - shiftX, hF - shiftY),
                        redPaint
                    )

                    val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(cyanShiftMatrix)
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                        alpha = 68
                    }
                    canvas.drawBitmap(
                        workBmp,
                        null,
                        RectF(shiftX, shiftY, wF + shiftX, hF + shiftY),
                        cyanPaint
                    )

                    // Faint spectral prism highlight across diagonal
                    val spectralSheen = LinearGradient(
                        0f, hF * 0.15f, wF, hF * 0.85f,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb(24, 255, 110, 180),
                            Color.argb(26, 90, 220, 255),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0.15f, 0.45f, 0.6f, 0.9f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = spectralSheen })
                }
            }

            // Apply single-color or rainbow tinting layer cleanly over the stylized blur
            applyTintLayer(
                canvas = canvas,
                width = safeW,
                height = safeH,
                tintMode = blurTintMode,
                tintColor = blurTintColor,
                tintIntensity = blurTintIntensity,
                rainbowStyle = blurRainbowStyle
            )
        } finally {
            bloomBmp?.recycle()
            workBmp.recycle()
        }
    }

    private fun applyTintLayer(
        canvas: Canvas,
        width: Int,
        height: Int,
        tintMode: BlurTintMode,
        tintColor: Int,
        tintIntensity: Float,
        rainbowStyle: BlurRainbowStyle
    ) {
        val intensity = tintIntensity.coerceIn(0f, 1f)
        if (tintMode == BlurTintMode.NONE || intensity <= 0.005f) return

        val wF = width.toFloat()
        val hF = height.toFloat()

        when (tintMode) {
            BlurTintMode.NONE -> Unit

            BlurTintMode.SOLID -> {
                val r = Color.red(tintColor)
                val g = Color.green(tintColor)
                val b = Color.blue(tintColor)
                val washAlpha = (intensity * 175f).roundToInt().coerceIn(0, 220)
                canvas.drawColor(Color.argb(washAlpha, r, g, b))

                // Soft luminous center glow so tinted glass stays rich rather than flat
                val glowAlpha = (intensity * 70f).roundToInt().coerceIn(0, 95)
                val glowGradient = RadialGradient(
                    wF * 0.5f, hF * 0.45f, max(wF, hF) * 0.65f,
                    Color.argb(glowAlpha, r, g, b),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                val glowPaint = Paint().apply {
                    shader = glowGradient
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                }
                canvas.drawRect(0f, 0f, wF, hF, glowPaint)
            }

            BlurTintMode.RAINBOW -> {
                val spectrumColors = when (rainbowStyle) {
                    BlurRainbowStyle.NEON_SUNSET -> intArrayOf(
                        0xFFFF6B6B.toInt(),
                        0xFFF59E0B.toInt(),
                        0xFF10B981.toInt(),
                        0xFF06B6D4.toInt(),
                        0xFF8B5CF6.toInt(),
                        0xFFEC4899.toInt()
                    )
                    else -> intArrayOf(
                        0xFFFF5E7E.toInt(),
                        0xFFFFB347.toInt(),
                        0xFF4ADE80.toInt(),
                        0xFF38BDF8.toInt(),
                        0xFF818CF8.toInt(),
                        0xFFF472B6.toInt()
                    )
                }

                val shader: Shader = when (rainbowStyle) {
                    BlurRainbowStyle.DIAGONAL_PRISM -> LinearGradient(
                        0f, 0f, wF, hF,
                        spectrumColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                    BlurRainbowStyle.VERTICAL_AURA -> LinearGradient(
                        wF * 0.15f, 0f, wF * 0.85f, hF,
                        spectrumColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                    BlurRainbowStyle.RADIAL_IRIDESCENCE -> RadialGradient(
                        wF * 0.5f, hF * 0.48f, max(wF, hF) * 0.78f,
                        spectrumColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                    BlurRainbowStyle.NEON_SUNSET -> LinearGradient(
                        wF, 0f, 0f, hF,
                        spectrumColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                }

                val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = shader
                    alpha = (intensity * 160f).roundToInt().coerceIn(0, 210)
                }
                canvas.drawRect(0f, 0f, wF, hF, washPaint)

                val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = shader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    alpha = (intensity * 62f).roundToInt().coerceIn(0, 90)
                }
                canvas.drawRect(0f, 0f, wF, hF, screenPaint)
            }
        }
    }

    private fun createHighlightBloomBitmap(blurredWork: Bitmap): Bitmap {
        val w = (blurredWork.width / 2).coerceAtLeast(4)
        val h = (blurredWork.height / 2).coerceAtLeast(4)
        val highlightBmp = Bitmap.createScaledBitmap(blurredWork, w, h, true)
        val pixels = IntArray(w * h)
        highlightBmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) ushr 8
            if (lum < 128) {
                pixels[i] = Color.argb(255, 0, 0, 0)
            } else {
                val boost = ((lum - 128) / 127f).coerceIn(0f, 1f)
                val factor = boost * boost * 1.35f
                pixels[i] = Color.argb(
                    255,
                    (r * factor).roundToInt().coerceIn(0, 255),
                    (g * factor).roundToInt().coerceIn(0, 255),
                    (b * factor).roundToInt().coerceIn(0, 255)
                )
            }
        }
        highlightBmp.setPixels(pixels, 0, w, 0, 0, w, h)
        val radius = (min(w, h) * 0.14f).roundToInt().coerceIn(2, 16)
        fastBlur(highlightBmp, radius)
        return highlightBmp
    }

    private fun computeCenterCropRect(
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int
    ): Rect {
        val srcWf = srcW.toFloat()
        val srcHf = srcH.toFloat()
        val targetRatio = targetW.toFloat() / targetH.coerceAtLeast(1)
        val srcRatio = srcWf / srcHf.coerceAtLeast(1f)

        val baseW: Float
        val baseH: Float
        if (srcRatio > targetRatio) {
            baseH = srcHf
            baseW = baseH * targetRatio
        } else {
            baseW = srcWf
            baseH = baseW / targetRatio
        }

        val left = ((srcWf - baseW) / 2f).toInt().coerceIn(0, (srcW - 1).coerceAtLeast(0))
        val top = ((srcHf - baseH) / 2f).toInt().coerceIn(0, (srcH - 1).coerceAtLeast(0))
        val right = (left + baseW).toInt().coerceIn(left + 1, srcW)
        val bottom = (top + baseH).toInt().coerceIn(top + 1, srcH)
        return Rect(left, top, right, bottom)
    }

    /**
     * In-place fast Stack Blur algorithm on Bitmap pixels.
     * High performance, smooth Gaussian-like kernel with clamped edges.
     */
    fun fastBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (radius < 1 || w < 2 || h < 2) return

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var yp: Int
        var yi: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yi = 0
        var yw = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (curY in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (step in -radius..radius) {
                p = pix[yi + min(wm, max(step, 0))]
                sir = stack[step + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - abs(step)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (step > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curY == 0) {
                    vmin[curX] = min(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (step in -radius..radius) {
                yi = max(0, yp) + curX
                sir = stack[step + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - abs(step)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (step > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (step < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curX == 0) {
                    vmin[curY] = min(curY + r1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
