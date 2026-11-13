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
    val supportsParallax: Boolean
        get() = false
    val supportsRevealImageMotion: Boolean
        get() = false

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
        pageBRightTravelPx: Int,
        pageASlowImageSpeed: Float? = null,
        pageBSlowImageSpeed: Float? = null,
        revealImageMotionPercent: Float = 0f,
        pageSwipeForward: Boolean = true
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
    private val dstRectA = Rect()
    private val dstRectB = Rect()

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

open class ParallaxScrollTransition(
    private val useClassicIncomingTravel: Boolean = false
) : PageTransitionRenderer {
    private val destination = Rect()
    private val source = Rect()

    override val supportsParallax: Boolean = true
    override val id: String = if (useClassicIncomingTravel) "parallax_classic" else "parallax"
    override val displayName: String =
        if (useClassicIncomingTravel) "Classic Parallax" else "Parallax Scroll"
    override val description: String =
        if (useClassicIncomingTravel) {
            "Restored parallax motion that pans incoming pages through the full reach"
        } else {
            "Scrolls each page while its image pans at a different rate"
        }

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
        pageBRightTravelPx: Int,
        pageASlowImageSpeed: Float?,
        pageBSlowImageSpeed: Float?,
        revealImageMotionPercent: Float,
        pageSwipeForward: Boolean
    ) {
        val p = progress.coerceIn(0f, 1f)

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

        val pageASourceLeft = pageASlowImageSpeed?.let { speed ->
            val counterTravel = minOf(
                ((1f - speed.coerceIn(0f, 1f)) * screenWidth).toInt(),
                pageALeftTravelPx
            )
            pageALeftTravelPx - counterTravel * p
        } ?: (pageALeftTravelPx + pageARightTravelPx * p)
        val pageBSourceLeft = pageBSlowImageSpeed?.let { speed ->
            val counterTravel = minOf(
                ((1f - speed.coerceIn(0f, 1f)) * screenWidth).toInt(),
                pageBRightTravelPx
            )
            pageBLeftTravelPx + counterTravel * (1f - p)
        } ?: if (useClassicIncomingTravel) {
            pageBLeftTravelPx + pageBRightTravelPx * (1f - p)
        } else {
            pageBLeftTravelPx * p
        }

        drawPage(
            pageABitmap,
            (-p * screenWidth).toInt(),
            pageASourceLeft
        )
        drawPage(
            pageBBitmap,
            ((1f - p) * screenWidth).toInt(),
            pageBSourceLeft
        )
        if (pageABitmap == null && pageBBitmap == null) {
            canvas.drawColor(Color.rgb(18, 22, 34))
        }
    }
}

class ClassicParallaxScrollTransition : ParallaxScrollTransition(useClassicIncomingTravel = true)

open class SwipeRevealTransition(
    private val followsSwipeDirection: Boolean
) : PageTransitionRenderer {
    override val id: String =
        if (followsSwipeDirection) "swipe_reveal" else "swipe_reveal_stacked"
    override val displayName: String =
        if (followsSwipeDirection) "Swipe to Reveal" else "Swipe to Reveal Stacked"
    override val description: String =
        if (followsSwipeDirection) {
            "The page being swiped away stays on top and reveals the incoming page"
        } else {
            "The left page stays on top of the right page while sliding away"
        }
    override val supportsParallax: Boolean = true
    override val supportsRevealImageMotion: Boolean = true

    private val sourceA = Rect()
    private val sourceB = Rect()
    private val destinationA = Rect()
    private val destinationB = Rect()

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
            pageALeftTravelPx = 0,
            pageARightTravelPx = 0,
            pageBLeftTravelPx = 0,
            pageBRightTravelPx = 0
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
        pageBRightTravelPx: Int,
        pageASlowImageSpeed: Float?,
        pageBSlowImageSpeed: Float?,
        revealImageMotionPercent: Float,
        pageSwipeForward: Boolean
    ) {
        val p = progress.coerceIn(0f, 1f)
        val imageMotion = (
            screenWidth * revealImageMotionPercent.coerceIn(0f, 1f) *
                (if (pageSwipeForward) 1f - p else p)
        ).toInt()

        fun drawPage(
            bitmap: Bitmap?,
            left: Int,
            sourceLeft: Int,
            destination: Rect,
            source: Rect
        ) {
            if (bitmap == null) return
            destination.set(left, 0, left + screenWidth, screenHeight)
            if (bitmap.width <= screenWidth) {
                canvas.drawBitmap(bitmap, null, destination, paint)
            } else {
                val boundedSourceLeft = sourceLeft.coerceIn(0, bitmap.width - screenWidth)
                source.set(
                    boundedSourceLeft,
                    0,
                    boundedSourceLeft + screenWidth,
                    bitmap.height
                )
                canvas.drawBitmap(bitmap, source, destination, paint)
            }
        }

        if (followsSwipeDirection && !pageSwipeForward) {
            drawPage(
                pageABitmap,
                -imageMotion,
                pageALeftTravelPx,
                destinationA,
                sourceA
            )
            drawPage(
                pageBBitmap,
                ((1f - p) * screenWidth).toInt(),
                pageBLeftTravelPx,
                destinationB,
                sourceB
            )
        } else {
            drawPage(
                pageBBitmap,
                imageMotion,
                pageBLeftTravelPx,
                destinationB,
                sourceB
            )
            drawPage(
                pageABitmap,
                (-p * screenWidth).toInt(),
                pageALeftTravelPx,
                destinationA,
                sourceA
            )
        }

        if (pageABitmap == null && pageBBitmap == null) {
            canvas.drawColor(Color.rgb(18, 22, 34))
        }
    }
}

class SwipeToRevealTransition : SwipeRevealTransition(followsSwipeDirection = true)

class SwipeToRevealStackedTransition : SwipeRevealTransition(followsSwipeDirection = false)

/**
 * Cross Fade Dissolve transition
 */
class CrossFadeTransition : PageTransitionRenderer {
    override val id: String = "fade"
    override val displayName: String = "Cross Fade"
    override val description: String = "Subtle dissolve blending between pages without motion jerk"
    private val fullRect = Rect()

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
        fullRect.set(0, 0, screenWidth, screenHeight)
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
    private val fullRect = Rect()

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
        fullRect.set(0, 0, screenWidth, screenHeight)
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
    private val fullRect = Rect()

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
        fullRect.set(0, 0, screenWidth, screenHeight)
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
    private val fullRect = Rect()
    private val dstRectB = Rect()
    private val shadowStripe = Rect()
    private val shadowPaint = Paint().apply { color = Color.BLACK }
    private val edgeShadowPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }

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
        fullRect.set(0, 0, screenWidth, screenHeight)

        // Page A stays stationary underneath with a gentle darken as B covers it
        if (pageABitmap != null) {
            canvas.drawBitmap(pageABitmap, null, fullRect, paint)
            if (p > 0f) {
                shadowPaint.alpha = (p * 110).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), shadowPaint)
            }
        }

        // Page B slides in from right
        if (pageBBitmap != null && p > 0f) {
            val leftB = ((1f - p) * screenWidth).toInt()
            dstRectB.set(leftB, 0, leftB + screenWidth, screenHeight)
            canvas.drawBitmap(pageBBitmap, null, dstRectB, paint)

            // Draw a vertical soft shadow stripe to the left edge of incoming Page B
            if (leftB > 0) {
                val stripeWidth = (screenWidth * 0.05f).toInt()
                shadowStripe.set(
                    (leftB - stripeWidth).coerceAtLeast(0),
                    0,
                    leftB,
                    screenHeight
                )
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
    private val fullRect = Rect()

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
        fullRect.set(0, 0, screenWidth, screenHeight)

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
        ClassicParallaxScrollTransition(),
        SwipeToRevealTransition(),
        SwipeToRevealStackedTransition(),
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
