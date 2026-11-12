package com.example.transition

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect

interface PageTransitionRenderer {
    val id: String
    val displayName: String
    val description: String

    fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float, // 0.0f (fully A) to 1.0f (fully B)
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    )

    fun drawWithParallax(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint,
        pageALeftTravelPx: Int,
        pageARightTravelPx: Int,
        pageBLeftTravelPx: Int,
        pageBRightTravelPx: Int
    ) {
        draw(canvas, pageABitmap, pageBBitmap, progress, screenWidth, screenHeight, paint)
    }
}

/**
 * Classic smooth horizontal parallax scroll
 */
class ClassicScrollTransition : PageTransitionRenderer {
    override val id: String = "scroll"
    override val displayName: String = "Classic Scroll"
    override val description: String = "Standard horizontal parallax sliding between screens"

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        val p = progress.coerceIn(0f, 1f)
        val dstRectA = Rect()
        val dstRectB = Rect()

        // Page A moves from 0 to -screenWidth
        if (pageABitmap != null && p < 1f) {
            val leftA = (-p * screenWidth).toInt()
            dstRectA.set(leftA, 0, leftA + screenWidth, screenHeight)
            canvas.drawBitmap(pageABitmap, null, dstRectA, paint)
        }

        // Page B moves from +screenWidth to 0
        if (pageBBitmap != null && p > 0f) {
            val leftB = ((1f - p) * screenWidth).toInt()
            dstRectB.set(leftB, 0, leftB + screenWidth, screenHeight)
            canvas.drawBitmap(pageBBitmap, null, dstRectB, paint)
        }

        // Fallback if neither bitmap exists - avoid flashing gray
        if (pageABitmap == null && pageBBitmap == null) {
            canvas.drawColor(Color.rgb(18, 22, 34))
        }
    }
}

class ParallaxScrollTransition : PageTransitionRenderer {
    override val id: String = "parallax"
    override val displayName: String = "Parallax Scroll"
    override val description: String = "Scrolls each page while its image pans at a different rate"

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        drawWithParallax(
            canvas = canvas,
            pageABitmap = pageABitmap,
            pageBBitmap = pageBBitmap,
            progress = progress,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            paint = paint,
            pageALeftTravelPx = pageABitmap?.let { (it.width - screenWidth).coerceAtLeast(0) / 2 } ?: 0,
            pageARightTravelPx = pageABitmap?.let { (it.width - screenWidth).coerceAtLeast(0) / 2 } ?: 0,
            pageBLeftTravelPx = pageBBitmap?.let { (it.width - screenWidth).coerceAtLeast(0) / 2 } ?: 0,
            pageBRightTravelPx = pageBBitmap?.let { (it.width - screenWidth).coerceAtLeast(0) / 2 } ?: 0
        )
    }

    override fun drawWithParallax(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint,
        pageALeftTravelPx: Int,
        pageARightTravelPx: Int,
        pageBLeftTravelPx: Int,
        pageBRightTravelPx: Int
    ) {
        val p = progress.coerceIn(0f, 1f)
        val destination = Rect()
        val source = Rect()

        fun drawPage(
            bitmap: Bitmap?,
            left: Int,
            sourceLeft: Float
        ) {
            if (bitmap == null) return
            destination.set(left, 0, left + screenWidth, screenHeight)
            if (bitmap.width <= screenWidth) {
                canvas.drawBitmap(bitmap, null, destination, paint)
                return
            }
            val boundedSourceLeft = sourceLeft
                .toInt()
                .coerceIn(0, (bitmap.width - screenWidth).coerceAtLeast(0))
            source.set(boundedSourceLeft, 0, boundedSourceLeft + screenWidth, bitmap.height)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }

        drawPage(
            pageABitmap,
            (-p * screenWidth).toInt(),
            pageALeftTravelPx + pageARightTravelPx * p
        )
        drawPage(
            pageBBitmap,
            ((1f - p) * screenWidth).toInt(),
            pageBLeftTravelPx * p
        )
        if (pageABitmap == null && pageBBitmap == null) {
            canvas.drawColor(Color.rgb(18, 22, 34))
        }
    }
}

/**
 * Cross Fade Dissolve transition
 */
class CrossFadeTransition : PageTransitionRenderer {
    override val id: String = "fade"
    override val displayName: String = "Cross Fade"
    override val description: String = "Subtle dissolve blending between pages without motion jerk"

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        val p = progress.coerceIn(0f, 1f)
        val fullRect = Rect(0, 0, screenWidth, screenHeight)
        val originalAlpha = paint.alpha

        if (pageABitmap != null) {
            paint.alpha = ((1f - p) * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(pageABitmap, null, fullRect, paint)
        }

        if (pageBBitmap != null) {
            paint.alpha = (p * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(pageBBitmap, null, fullRect, paint)
        }

        paint.alpha = originalAlpha
    }
}

class SoftBlurTransition : PageTransitionRenderer {
    override val id: String = "soft_blur"
    override val displayName: String = "Soft Blur Fade"
    override val description: String = "A stationary crossfade with a gentle blur that peaks mid-swipe"

    private var sourceA: Bitmap? = null
    private var blurredA: Bitmap? = null
    private var sourceB: Bitmap? = null
    private var blurredB: Bitmap? = null

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        val p = progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        val fullRect = Rect(0, 0, screenWidth, screenHeight)
        val originalAlpha = paint.alpha
        canvas.drawColor(Color.rgb(18, 22, 34))

        if (pageABitmap != null) {
            paint.alpha = ((1f - eased) * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(pageABitmap, null, fullRect, paint)
        }
        if (pageBBitmap != null) {
            paint.alpha = (eased * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(pageBBitmap, null, fullRect, paint)
        }

        val blurStrength = (kotlin.math.sin(Math.PI * eased).toFloat() * 0.22f)
            .coerceIn(0f, 0.22f)
        if (blurStrength > 0.001f) {
            blurred(pageABitmap, true)?.let {
                paint.alpha = ((1f - eased) * blurStrength * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(it, null, fullRect, paint)
            }
            blurred(pageBBitmap, false)?.let {
                paint.alpha = (eased * blurStrength * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(it, null, fullRect, paint)
            }
        }
        paint.alpha = originalAlpha
    }

    private fun blurred(source: Bitmap?, firstPage: Boolean): Bitmap? {
        if (source == null || source.isRecycled) return null
        val currentSource = if (firstPage) sourceA else sourceB
        val currentBlur = if (firstPage) blurredA else blurredB
        if (currentSource === source && currentBlur?.isRecycled == false) return currentBlur

        if (currentBlur != null && !currentBlur.isRecycled) currentBlur.recycle()
        val result = Bitmap.createScaledBitmap(
            source,
            (source.width / 8).coerceAtLeast(1),
            (source.height / 8).coerceAtLeast(1),
            true
        )
        if (firstPage) {
            sourceA = source
            blurredA = result
        } else {
            sourceB = source
            blurredB = result
        }
        return result
    }
}

/**
 * Depth & Zoom 3D Layered transition
 */
class DepthZoomTransition : PageTransitionRenderer {
    override val id: String = "depth"
    override val displayName: String = "Depth & Scale"
    override val description: String = "Current page scales out into the background while next page scales in"

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        val p = progress.coerceIn(0f, 1f)
        val fullRect = Rect(0, 0, screenWidth, screenHeight)
        val originalAlpha = paint.alpha

        // Page A zooms out and fades
        if (pageABitmap != null && p < 1f) {
            val scaleA = 1f - (0.22f * p)
            val alphaA = ((1f - p) * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.scale(scaleA, scaleA, screenWidth / 2f, screenHeight / 2f)
            paint.alpha = alphaA
            canvas.drawBitmap(pageABitmap, null, fullRect, paint)
            canvas.restore()
        }

        // Page B zooms in from 1.15x to 1.0x and fades in
        if (pageBBitmap != null && p > 0f) {
            val scaleB = 1.15f - (0.15f * p)
            val alphaB = (p * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.scale(scaleB, scaleB, screenWidth / 2f, screenHeight / 2f)
            paint.alpha = alphaB
            canvas.drawBitmap(pageBBitmap, null, fullRect, paint)
            canvas.restore()
        }

        paint.alpha = originalAlpha
    }
}

/**
 * Slide Over with dynamic drop shadow
 */
class SlideOverTransition : PageTransitionRenderer {
    override val id: String = "slide_over"
    override val displayName: String = "Slide Over & Shadow"
    override val description: String = "Incoming page slides directly over the base page with depth shadow"

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        val p = progress.coerceIn(0f, 1f)
        val fullRect = Rect(0, 0, screenWidth, screenHeight)

        // Page A stays stationary underneath with a gentle darken as B covers it
        if (pageABitmap != null) {
            canvas.drawBitmap(pageABitmap, null, fullRect, paint)
            if (p > 0f) {
                val shadowAlpha = (p * 110).toInt().coerceIn(0, 255)
                val shadowPaint = Paint().apply {
                    color = Color.BLACK
                    alpha = shadowAlpha
                }
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), shadowPaint)
            }
        }

        // Page B slides in from right
        if (pageBBitmap != null && p > 0f) {
            val leftB = ((1f - p) * screenWidth).toInt()
            val dstB = Rect(leftB, 0, leftB + screenWidth, screenHeight)
            canvas.drawBitmap(pageBBitmap, null, dstB, paint)

            // Draw a vertical soft shadow stripe to the left edge of incoming Page B
            if (leftB > 0) {
                val stripeWidth = (screenWidth * 0.05f).toInt()
                val shadowStripe = Rect((leftB - stripeWidth).coerceAtLeast(0), 0, leftB, screenHeight)
                val edgeShadowPaint = Paint().apply {
                    color = Color.argb(120, 0, 0, 0)
                }
                canvas.drawRect(shadowStripe, edgeShadowPaint)
            }
        }
    }
}

/**
 * 3D Cube Rotation transition
 */
class CubeTransition : PageTransitionRenderer {
    override val id: String = "cube"
    override val displayName: String = "3D Cube Turn"
    override val description: String = "Rotates between pages like rotating facets of a 3D prism"

    private val camera = Camera()
    private val matrix = Matrix()

    override fun draw(
        canvas: Canvas,
        pageABitmap: Bitmap?,
        pageBBitmap: Bitmap?,
        progress: Float,
        screenWidth: Int,
        screenHeight: Int,
        paint: Paint
    ) {
        val p = progress.coerceIn(0f, 1f)
        val fullRect = Rect(0, 0, screenWidth, screenHeight)

        // Page A rotates out: 0 deg to -90 deg around right pivot
        if (pageABitmap != null && p < 1f) {
            val rotA = -90f * p
            canvas.save()
            camera.save()
            camera.rotateY(rotA)
            matrix.reset()
            camera.getMatrix(matrix)
            camera.restore()

            matrix.preTranslate(-screenWidth.toFloat(), -screenHeight / 2f)
            matrix.postTranslate(screenWidth.toFloat(), screenHeight / 2f)

            canvas.concat(matrix)
            canvas.drawBitmap(pageABitmap, null, fullRect, paint)

            // Dynamic dark shading as it rotates away
            val shade = (p * 180).toInt().coerceIn(0, 255)
            canvas.drawColor(Color.argb(shade, 0, 0, 0))
            canvas.restore()
        }

        // Page B rotates in: 90 deg down to 0 deg around left pivot
        if (pageBBitmap != null && p > 0f) {
            val rotB = 90f * (1f - p)
            canvas.save()
            camera.save()
            camera.rotateY(rotB)
            matrix.reset()
            camera.getMatrix(matrix)
            camera.restore()

            matrix.preTranslate(0f, -screenHeight / 2f)
            matrix.postTranslate(0f, screenHeight / 2f)

            canvas.concat(matrix)
            canvas.drawBitmap(pageBBitmap, null, fullRect, paint)

            val shadeB = ((1f - p) * 180).toInt().coerceIn(0, 255)
            canvas.drawColor(Color.argb(shadeB, 0, 0, 0))
            canvas.restore()
        }
    }
}

object TransitionRegistry {
    val transitions: List<PageTransitionRenderer> = listOf(
        ClassicScrollTransition(),
        ParallaxScrollTransition(),
        CrossFadeTransition(),
        SoftBlurTransition(),
        DepthZoomTransition(),
        SlideOverTransition(),
        CubeTransition()
    )

    fun get(id: String): PageTransitionRenderer {
        return transitions.find { it.id.equals(id, ignoreCase = true) }
            ?: transitions.first()
    }
}
