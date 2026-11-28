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
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.example.data.model.BlurRainbowStyle
import com.example.data.model.BlurStyle
import com.example.data.model.BlurTexturePattern
import com.example.data.model.BlurTintMode
import com.example.data.model.CropFillMode
import com.example.data.model.PageItem
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
        blurRainbowStyle: BlurRainbowStyle = BlurRainbowStyle.DIAGONAL_PRISM,
        blurTexturePattern: BlurTexturePattern = BlurTexturePattern.NONE,
        blurTextureIntensity: Float = PageItem.DEFAULT_BLUR_TEXTURE_INTENSITY,
        blurTextureScale: Float = PageItem.DEFAULT_BLUR_TEXTURE_SCALE,
        blurTextureMaskBlurAmount: Float = PageItem.DEFAULT_BLUR_TEXTURE_MASK_BLUR,
        blurTextureMaskTintColor: Int = PageItem.DEFAULT_BLUR_TEXTURE_MASK_TINT_COLOR,
        blurTextureMaskTintIntensity: Float = PageItem.DEFAULT_BLUR_TEXTURE_MASK_TINT_INTENSITY
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
                    blurRainbowStyle = blurRainbowStyle,
                    blurTexturePattern = blurTexturePattern,
                    blurTextureIntensity = blurTextureIntensity,
                    blurTextureScale = blurTextureScale,
                    blurTextureMaskBlurAmount = blurTextureMaskBlurAmount,
                    blurTextureMaskTintColor = blurTextureMaskTintColor,
                    blurTextureMaskTintIntensity = blurTextureMaskTintIntensity
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
        blurRainbowStyle: BlurRainbowStyle,
        blurTexturePattern: BlurTexturePattern = BlurTexturePattern.NONE,
        blurTextureIntensity: Float = PageItem.DEFAULT_BLUR_TEXTURE_INTENSITY,
        blurTextureScale: Float = PageItem.DEFAULT_BLUR_TEXTURE_SCALE,
        blurTextureMaskBlurAmount: Float = PageItem.DEFAULT_BLUR_TEXTURE_MASK_BLUR,
        blurTextureMaskTintColor: Int = PageItem.DEFAULT_BLUR_TEXTURE_MASK_TINT_COLOR,
        blurTextureMaskTintIntensity: Float = PageItem.DEFAULT_BLUR_TEXTURE_MASK_TINT_INTENSITY
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
            blurRainbowStyle = blurRainbowStyle,
            blurTexturePattern = blurTexturePattern,
            blurTextureIntensity = blurTextureIntensity,
            blurTextureScale = blurTextureScale,
            blurTextureMaskBlurAmount = blurTextureMaskBlurAmount,
            blurTextureMaskTintColor = blurTextureMaskTintColor,
            blurTextureMaskTintIntensity = blurTextureMaskTintIntensity
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
     * Creates a normalized darkened copy of [srcBmp].
     * Like audio loudness & dynamic-range normalization, bright and mid-tone wallpapers of varying
     * original brightness/contrast are brought down to a consistent target luminance level and
     * bounded dynamic range controlled solely by [dimFactor], without ever brightening pixels
     * already darker than the target.
     */
    fun createDarkenedCopy(
        srcBmp: Bitmap,
        dimFactor: Float
    ): Bitmap {
        val w = srcBmp.width.coerceAtLeast(1)
        val h = srcBmp.height.coerceAtLeast(1)
        val normalizedSrc = if (srcBmp.config == Bitmap.Config.ARGB_8888) {
            srcBmp
        } else {
            srcBmp.copy(Bitmap.Config.ARGB_8888, false) ?: srcBmp
        }
        try {
            val rowPixels = IntArray(w)
            val histogram = IntArray(256)
            val sampleStepX = max(1, w / 240)
            val sampleStepY = max(1, h / 240)
            var totalSamples = 0

            var y = 0
            while (y < h) {
                normalizedSrc.getPixels(rowPixels, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = rowPixels[x]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    val lumIdx = ((r * 54 + g * 183 + b * 19) ushr 8).coerceIn(0, 255)
                    histogram[lumIdx]++
                    totalSamples++
                    x += sampleStepX
                }
                y += sampleStepY
            }

            val scaleLut = buildNormalizedScaleLut(
                histogram = histogram,
                totalSamples = totalSamples,
                dimFactor = dimFactor
            )

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (row in 0 until h) {
                normalizedSrc.getPixels(rowPixels, 0, w, 0, row, w, 1)
                for (col in 0 until w) {
                    val c = rowPixels[col]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    val lumIdx = ((r * 54 + g * 183 + b * 19) ushr 8).coerceIn(0, 255)
                    val s = scaleLut[lumIdx]
                    val nr = ((r * s + 128) ushr 8).coerceIn(0, 255)
                    val ng = ((g * s + 128) ushr 8).coerceIn(0, 255)
                    val nb = ((b * s + 128) ushr 8).coerceIn(0, 255)
                    rowPixels[col] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
                }
                out.setPixels(rowPixels, 0, w, 0, row, w, 1)
            }
            return out
        } finally {
            if (normalizedSrc !== srcBmp) {
                normalizedSrc.recycle()
            }
        }
    }

    /**
     * Builds a 256-entry 8-bit fixed-point multiplier LUT (`0..256`) that normalizes an image's
     * luminance distribution `[pLow..pHigh]` & mean toward a consistent target level and range
     * derived from [dimFactor], guaranteeing `scale <= 1.0`.
     */
    internal fun buildNormalizedScaleLut(
        histogram: IntArray,
        totalSamples: Int,
        dimFactor: Float
    ): IntArray {
        val lut = IntArray(256)
        if (totalSamples <= 0) {
            val fallback = ((1f - dimFactor.coerceIn(0f, 0.95f)) * 256f).roundToInt().coerceIn(0, 256)
            lut.fill(fallback)
            return lut
        }

        val lowTarget = (totalSamples * 0.05f).roundToInt().coerceAtLeast(1)
        val highTarget = (totalSamples * 0.95f).roundToInt().coerceAtLeast(lowTarget)
        var cumulative = 0
        var pLowIdx = -1
        var pHighIdx = 255
        var weightedSum = 0L
        for (i in 0..255) {
            val count = histogram[i]
            cumulative += count
            weightedSum += i.toLong() * count
            if (pLowIdx < 0 && cumulative >= lowTarget) {
                pLowIdx = i
            }
            if (cumulative >= highTarget) {
                pHighIdx = i
                break
            }
        }
        // Finish sum in case loop broke early
        for (i in (pHighIdx + 1)..255) {
            weightedSum += i.toLong() * histogram[i]
        }

        val pLow = ((pLowIdx.coerceAtLeast(0)) / 255f).coerceIn(0f, 1f)
        val pHigh = (pHighIdx.coerceIn(pLowIdx.coerceAtLeast(0), 255) / 255f).coerceIn(pLow, 1f)
        val meanLum = ((weightedSum.toDouble() / totalSamples) / 255.0).toFloat().coerceIn(0f, 1f)
        val effectiveRange = (pHigh - pLow).coerceAtLeast(0.001f)

        val strength = dimFactor.coerceIn(0f, 0.95f)
        val targetPeakLum = (0.80f * (1f - strength) + 0.05f).coerceIn(0.06f, 0.85f)
        val targetBlackLum = (targetPeakLum * 0.05f).coerceIn(0.003f, 0.035f)
        val targetMeanLum = targetBlackLum + (targetPeakLum - targetBlackLum) * 0.42f
        val flatTargetLum = targetMeanLum * 0.65f + targetPeakLum * 0.35f

        val meanNorm = ((meanLum - pLow) / effectiveRange).coerceIn(0.06f, 0.94f)
        val gamma = (ln(0.42) / ln(meanNorm.toDouble())).toFloat().coerceIn(0.45f, 2.2f)
        val rangeMix = ((effectiveRange - 0.04f) / 0.12f).coerceIn(0f, 1f)

        lut[0] = 0
        for (i in 1..255) {
            val inL = i / 255f
            val rangeMappedLum = when {
                inL <= pLow -> {
                    val t = if (pLow > 0.001f) (inL / pLow).coerceIn(0f, 1f) else 0f
                    targetBlackLum * t
                }
                inL >= pHigh -> {
                    val over = if (1f - pHigh > 0.001f) {
                        ((inL - pHigh) / (1f - pHigh)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    (targetPeakLum + targetPeakLum * 0.06f * over).coerceAtMost(0.90f)
                }
                else -> {
                    val u = ((inL - pLow) / effectiveRange).coerceIn(0f, 1f)
                    val shaped = u.toDouble().pow(gamma.toDouble()).toFloat()
                    targetBlackLum + (targetPeakLum - targetBlackLum) * shaped
                }
            }
            val desiredLum = rangeMix * rangeMappedLum + (1f - rangeMix) * min(inL, flatTargetLum)
            val scale = (desiredLum / inL).coerceIn(0f, 1f)
            lut[i] = (scale * 256f).roundToInt().coerceIn(0, 256)
        }
        return lut
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
        blurRainbowStyle: BlurRainbowStyle,
        blurTexturePattern: BlurTexturePattern = BlurTexturePattern.NONE,
        blurTextureIntensity: Float = PageItem.DEFAULT_BLUR_TEXTURE_INTENSITY,
        blurTextureScale: Float = PageItem.DEFAULT_BLUR_TEXTURE_SCALE,
        blurTextureMaskBlurAmount: Float = PageItem.DEFAULT_BLUR_TEXTURE_MASK_BLUR,
        blurTextureMaskTintColor: Int = PageItem.DEFAULT_BLUR_TEXTURE_MASK_TINT_COLOR,
        blurTextureMaskTintIntensity: Float = PageItem.DEFAULT_BLUR_TEXTURE_MASK_TINT_INTENSITY
    ) {
        val safeW = targetWidth.coerceAtLeast(2)
        val safeH = targetHeight.coerceAtLeast(2)
        val clampedBlur = blurAmount.coerceIn(0f, 1f)

        // Keep enough detail for glass highlights and fine repeating textures.
        // The backdrop is still pre-rendered, but no longer collapses to a tiny working image.
        val targetLongEdge = max(safeW, safeH).coerceAtLeast(1)
        val desiredMaxWorkEdge = min(targetLongEdge.toFloat(), 960f - clampedBlur * 240f)
        val workScale = (desiredMaxWorkEdge / targetLongEdge.toFloat()).coerceIn(0.15f, 1f)
        val workW = (safeW * workScale).roundToInt().coerceAtLeast(8)
        val workH = (safeH * workScale).roundToInt().coerceAtLeast(8)

        val workBmp = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        var patternSourceBitmap: Bitmap? = null
        var bloomBmp: Bitmap? = null
        try {
            val workCanvas = Canvas(workBmp)
            val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            workCanvas.drawBitmap(srcBmp, srcRect, Rect(0, 0, workW, workH), samplePaint)
            if (blurTexturePattern != BlurTexturePattern.NONE) {
                patternSourceBitmap = workBmp.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Could not preserve the sharp pattern source")
            }

            // Apply genuine multi-pass Stack Blur (2 passes approximate a smooth Gaussian curve).
            if (clampedBlur > 0.01f) {
                val maxSafeRadius = (min(workW, workH) / 2 - 1).coerceAtLeast(1)
                val primaryRadius = (2f + clampedBlur * 50f).roundToInt().coerceIn(1, maxSafeRadius)
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
                    // Cold blue body, broad diagonal reflection, narrow specular streak and bevel.
                    val aeroPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.16f) })
                    }
                    canvas.drawBitmap(workBmp, null, fullDst, aeroPaint)
                    canvas.drawColor(Color.argb(26, 7, 20, 39))
                    canvas.drawColor(Color.argb(48, 190, 222, 249))
                    val broadReflection = LinearGradient(
                        -wF * 0.18f, 0f, wF * 0.88f, hF * 0.72f,
                        intArrayOf(Color.TRANSPARENT, Color.argb(26, 235, 248, 255), Color.argb(72, 245, 252, 255), Color.argb(28, 220, 241, 255), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.28f, 0.39f, 0.49f, 0.68f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = broadReflection })
                    val specularStreak = LinearGradient(
                        wF * 0.08f, 0f, wF * 0.96f, hF * 0.82f,
                        intArrayOf(Color.TRANSPARENT, Color.argb(18, 255, 255, 255), Color.argb(92, 255, 255, 255), Color.argb(24, 225, 244, 255), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.40f, 0.47f, 0.52f, 0.64f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = specularStreak })
                    val topBevel = LinearGradient(
                        0f, 0f, 0f, hF * 0.22f,
                        intArrayOf(Color.argb(92, 255, 255, 255), Color.argb(18, 235, 247, 255), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.28f, 1f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF * 0.22f, Paint().apply { shader = topBevel })
                    val lowerShade = LinearGradient(0f, hF * 0.60f, 0f, hF, Color.TRANSPARENT, Color.argb(42, 8, 18, 34), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, hF * 0.60f, wF, hF, Paint().apply { shader = lowerShade })
                }

                BlurStyle.SMOKED_GLASS -> {
                    val smokedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.58f) })
                    }
                    canvas.drawBitmap(workBmp, null, fullDst, smokedPaint)
                    canvas.drawColor(Color.argb(68, 19, 25, 32))
                    val smokedBody = LinearGradient(
                        0f, 0f, wF * 0.72f, hF,
                        intArrayOf(Color.argb(30, 207, 219, 229), Color.argb(16, 115, 129, 142), Color.argb(88, 5, 9, 14)),
                        floatArrayOf(0f, 0.46f, 1f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = smokedBody })
                    val smokedReflection = LinearGradient(
                        -wF * 0.12f, 0f, wF * 0.86f, hF * 0.42f,
                        intArrayOf(Color.TRANSPARENT, Color.argb(42, 242, 247, 251), Color.argb(12, 235, 244, 250), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.34f, 0.43f, 0.63f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = smokedReflection })
                    val edgeShade = LinearGradient(
                        0f, 0f, wF, 0f,
                        intArrayOf(Color.argb(42, 2, 6, 10), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(30, 2, 6, 10)),
                        floatArrayOf(0f, 0.18f, 0.82f, 1f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = edgeShade })
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

            // Tint and repeating texture are independent material layers.
            applyTintLayer(
                canvas = canvas, width = safeW, height = safeH,
                tintMode = blurTintMode, tintColor = blurTintColor,
                tintIntensity = blurTintIntensity, rainbowStyle = blurRainbowStyle
            )
            drawBlurTexturePattern(
                canvas = canvas,
                width = safeW,
                height = safeH,
                sourceUnblurredBitmap = patternSourceBitmap ?: workBmp,
                pattern = blurTexturePattern,
                strength = blurTextureIntensity,
                scale = blurTextureScale,
                maskBlurAmount = blurTextureMaskBlurAmount,
                maskTintColor = blurTextureMaskTintColor,
                maskTintIntensity = blurTextureMaskTintIntensity
            )
        } finally {
            patternSourceBitmap?.recycle()
            bloomBmp?.recycle()
            workBmp.recycle()
        }
    }

    /**
     * Draws a sharp etched pattern after the normal blur/tint pipeline.
     * Its filled geometry also acts as an alpha mask for a separately blurred/tinted
     * region underneath it. The visible pattern is never passed through a blur operation.
     */
    private fun drawBlurTexturePattern(
        canvas: Canvas,
        width: Int,
        height: Int,
        sourceUnblurredBitmap: Bitmap,
        pattern: BlurTexturePattern,
        strength: Float,
        scale: Float,
        maskBlurAmount: Float,
        maskTintColor: Int,
        maskTintIntensity: Float
    ) {
        val clampedStrength = strength.coerceIn(0f, 1f)
        if (pattern == BlurTexturePattern.NONE || clampedStrength <= 0.005f) return

        val workTileSize = textureTileSize(sourceUnblurredBitmap.width, sourceUnblurredBitmap.height, scale)
        val displayTileSize = textureTileSize(width, height, scale)

        // Build the pattern region from the original, unblurred source. A value of 0%
        // therefore really is sharp; higher values apply blur independently of the backdrop.
        run {
            val effectBitmap = sourceUnblurredBitmap.copy(Bitmap.Config.ARGB_8888, true)
            try {
                val maxRadius = (min(effectBitmap.width, effectBitmap.height) / 2 - 1).coerceAtLeast(1)
                val radius = (maskBlurAmount.coerceIn(0f, 1f) * 32f).roundToInt().coerceIn(0, maxRadius)
                if (radius > 0) fastBlur(effectBitmap, radius)

                val effectCanvas = Canvas(effectBitmap)
                val tintStrength = maskTintIntensity.coerceIn(0f, 1f)
                if (tintStrength > 0.005f) {
                    val alpha = (tintStrength * 150f).roundToInt().coerceIn(0, 150)
                    effectCanvas.drawColor(
                        Color.argb(
                            alpha,
                            Color.red(maskTintColor),
                            Color.green(maskTintColor),
                            Color.blue(maskTintColor)
                        )
                    )
                }

                val maskShader = createTexturePatternShader(pattern, workTileSize, maskOnly = true)
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    shader = maskShader
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                effectCanvas.drawRect(
                    0f, 0f,
                    effectBitmap.width.toFloat(),
                    effectBitmap.height.toFloat(),
                    maskPaint
                )

                // The masked source is an actual independent layer, not a translucent
                // overlay of the already-blurred backdrop. Strength controls the etched finish.
                val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(effectBitmap, null, Rect(0, 0, width, height), effectPaint)
            } finally {
                effectBitmap.recycle()
            }
        }

        // The etching/highlight layer is drawn last at output resolution, so it stays crisp.
        val overlayShader = createTexturePatternShader(pattern, displayTileSize, maskOnly = false)
        val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = overlayShader
            alpha = (clampedStrength * 255f).roundToInt().coerceIn(0, 255)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
    }

    private fun textureTileSize(width: Int, height: Int, scale: Float): Int =
        (min(width, height) / 4f * scale.coerceIn(0.5f, 3f))
            .roundToInt()
            .coerceIn(24, 512)

    private fun createTexturePatternShader(
        pattern: BlurTexturePattern,
        tileSize: Int,
        maskOnly: Boolean
    ): BitmapShader {
        val tile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        val cell = tileSize / 4f
        val maskFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(112, 5, 12, 20)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = cell * 0.10f
        }
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 244, 249, 253)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = cell * 0.07f
        }
        val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(62, 225, 236, 245) }
        val dotRadius = cell * 0.29f
        val diamondRadius = cell * 0.43f

        fun drawGridOrRibLines(diagonal: Boolean, paint: Paint) {
            val count = if (diagonal) -4..8 else 0..4
            for (index in count) {
                if (diagonal) {
                    val x = index * cell
                    c.drawLine(x, 0f, x - tileSize, tileSize.toFloat(), paint)
                } else {
                    val position = index * cell
                    c.drawLine(position, 0f, position, tileSize.toFloat(), paint)
                    c.drawLine(0f, position, tileSize.toFloat(), position, paint)
                }
            }
        }

        fun diamondPath(cx: Float, cy: Float): Path = Path().apply {
            moveTo(cx, cy - diamondRadius)
            lineTo(cx + diamondRadius, cy)
            lineTo(cx, cy + diamondRadius)
            lineTo(cx - diamondRadius, cy)
            close()
        }

        when (pattern) {
            BlurTexturePattern.NONE -> Unit

            BlurTexturePattern.FINE_GRID -> {
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = if (maskOnly) cell * 0.12f else cell * 0.085f
                    strokeCap = Paint.Cap.SQUARE
                }
                if (maskOnly) {
                    drawGridOrRibLines(diagonal = false, paint = linePaint)
                } else {
                    drawGridOrRibLines(diagonal = false, paint = shadow)
                    c.save()
                    c.translate(-cell * 0.035f, -cell * 0.035f)
                    drawGridOrRibLines(diagonal = false, paint = highlight)
                    c.restore()
                }
            }

            BlurTexturePattern.DIAGONAL_RIBS -> {
                if (maskOnly) {
                    val ribMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = cell * 0.22f
                        strokeCap = Paint.Cap.SQUARE
                    }
                    drawGridOrRibLines(diagonal = true, paint = ribMask)
                } else {
                    val ribs = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(78, 235, 243, 250)
                        style = Paint.Style.STROKE
                        strokeWidth = cell * 0.22f
                        strokeCap = Paint.Cap.SQUARE
                    }
                    drawGridOrRibLines(diagonal = true, paint = shadow)
                    drawGridOrRibLines(diagonal = true, paint = ribs)
                    c.save()
                    c.translate(-cell * 0.035f, -cell * 0.035f)
                    drawGridOrRibLines(diagonal = true, paint = highlight)
                    c.restore()
                }
            }

            BlurTexturePattern.DOT_MATRIX -> {
                val dotShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(112, 5, 12, 20) }
                val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(178, 246, 250, 253)
                    style = Paint.Style.STROKE
                    strokeWidth = (cell * 0.045f).coerceAtLeast(1f)
                }
                for (row in 0..3) for (column in 0..3) {
                    val x = (column + 0.5f) * cell
                    val y = (row + 0.5f) * cell
                    if (maskOnly) {
                        c.drawCircle(x, y, dotRadius, maskFill)
                    } else {
                        c.drawCircle(x + cell * 0.035f, y + cell * 0.035f, dotRadius, dotShadow)
                        c.drawCircle(x, y, dotRadius * 0.93f, bodyFill)
                        c.drawCircle(x - cell * 0.015f, y - cell * 0.015f, dotRadius * 0.93f, dotRing)
                    }
                }
            }

            BlurTexturePattern.DIAMOND -> {
                for (row in 0..3) for (column in 0..3) {
                    val x = (column + 0.5f) * cell
                    val y = (row + 0.5f) * cell
                    val path = diamondPath(x, y)
                    if (maskOnly) {
                        c.drawPath(path, maskFill)
                    } else {
                        c.save()
                        c.translate(cell * 0.035f, cell * 0.035f)
                        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(112, 5, 12, 20) })
                        c.restore()
                        c.drawPath(path, bodyFill)
                        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(184, 246, 250, 253)
                            style = Paint.Style.STROKE
                            strokeWidth = (cell * 0.045f).coerceAtLeast(1f)
                        }
                        c.drawPath(path, edge)
                    }
                }
            }
        }
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
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
                // Older pages may still have a highly saturated saved tint. Desaturate at the
                // renderer boundary so every saved preset gets the same smoked-glass treatment.
                val hsv = FloatArray(3)
                Color.colorToHSV(tintColor, hsv)
                hsv[1] = hsv[1].coerceAtMost(0.46f)
                hsv[2] = hsv[2].coerceAtMost(0.85f)
                val mutedTint = Color.HSVToColor(hsv)
                val r = Color.red(mutedTint); val g = Color.green(mutedTint); val b = Color.blue(mutedTint)
                // A translucent tinted pane, not a high-saturation color wash.
                val alpha = (intensity * 104f).roundToInt().coerceIn(0, 112)
                canvas.drawColor(Color.argb(alpha, r, g, b))
                val reflection = LinearGradient(
                    0f, 0f, wF * 0.82f, hF * 0.48f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((intensity * 34f).roundToInt(), 250, 253, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.42f, 0.72f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, wF, hF, Paint().apply { shader = reflection })
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
