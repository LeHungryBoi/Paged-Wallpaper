package com.example.render

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.Matrix as GlMatrix
import com.example.data.model.PageItem
import com.example.data.repository.WallpaperRepository
import com.example.transition.TransitionRegistry
import com.example.util.ImageProcessingUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

internal interface VulkanDrawSink {
    val maxTextureSize: Int
    fun uploadBitmap(bitmap: Bitmap): Int
    fun deleteTexture(textureId: Int)
    fun drawTexturedQuad(
        textureId: Int,
        mvp: FloatArray,
        uvOffsetX: Float = 0f,
        uvOffsetY: Float = 0f,
        uvScaleX: Float = 1f,
        uvScaleY: Float = 1f,
        alpha: Float = 1f,
        red: Float = 1f,
        green: Float = 1f,
        blue: Float = 1f,
        darkTextureId: Int = 0,
        darkMix: Float = 0f
    )
    fun drawSolidRect(
        mvp: FloatArray,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float
    )
}

/**
 * Shared wallpaper scene composition, texture-slot cache, transition geometry, and debug HUD
 * emitter used by the native Vulkan backend.
 */
internal class WallpaperSceneComposer(
    private val repository: WallpaperRepository,
    private val sink: VulkanDrawSink
) {
    private data class AssetKey(
        val generation: Long,
        val darkenedGeneration: Long,
        val page: PageItem?,
        val pageIndex: Int,
        val width: Int,
        val height: Int,
        val usesParallax: Boolean,
        val darkened: Boolean
    )

    private data class AssetSlot(
        val pageIndex: Int,
        val usesParallax: Boolean,
        val darkened: Boolean
    )

    private data class PageTexture(
        val key: AssetKey,
        val textureId: Int,
        val blurredTextureId: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val leftTravelPx: Int,
        val rightTravelPx: Int
    )

    private data class PageRenderTextures(
        val primary: PageTexture?,
        val darkBlendTarget: PageTexture? = null,
        val darkMix: Float = 0f
    )

    private data class MissingAsset(val key: AssetKey, val retryAfterNanos: Long)

    private val pageTextures = mutableMapOf<AssetSlot, PageTexture>()
    private val missingAssets = mutableMapOf<AssetSlot, MissingAsset>()

    private var debugCardTexture = 0
    private var debugCircleTexture = 0
    private var debugTextureDensity = 0f
    private var signalCardTexture = 0
    private var signalCardLines: List<String> = emptyList()
    private var signalCardDensity = 0f
    private var signalCardWidthPx = 0
    private var signalCardHeightPx = 0

    private val baseProjectionMatrix = FloatArray(16)
    private val sceneScaleMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val cubeMatrix = FloatArray(16)
    private val cubeScaleMatrix = FloatArray(16)
    private val graphicsMatrixValues = FloatArray(9)
    private val cubeCamera = Camera()
    private val graphicsMatrix = Matrix()

    var missingConfiguredTextureInFrame: Boolean = false
        private set

    fun onDimensionsChanged() {
        releasePageTextures()
    }

    fun releasePageTextures() {
        pageTextures.values.forEach {
            if (it.textureId != 0) sink.deleteTexture(it.textureId)
            if (it.blurredTextureId != 0) sink.deleteTexture(it.blurredTextureId)
        }
        pageTextures.clear()
        missingAssets.clear()
    }

    fun releaseAllTextures() {
        releasePageTextures()
        if (debugCardTexture != 0) sink.deleteTexture(debugCardTexture)
        if (debugCircleTexture != 0) sink.deleteTexture(debugCircleTexture)
        if (signalCardTexture != 0) sink.deleteTexture(signalCardTexture)
        debugCardTexture = 0
        debugCircleTexture = 0
        debugTextureDensity = 0f
        signalCardTexture = 0
        signalCardLines = emptyList()
        signalCardDensity = 0f
    }

    fun setupOrtho(width: Int, height: Int) {
        GlMatrix.orthoM(
            baseProjectionMatrix, 0,
            0f, width.toFloat(),
            height.toFloat(), 0f,
            -1000f, 1000f
        )
        System.arraycopy(baseProjectionMatrix, 0, projectionMatrix, 0, 16)
    }

    fun computeFullscreenMvp(width: Int, height: Int): FloatArray {
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.scaleM(modelMatrix, 0, width.toFloat(), height.toFloat(), 1f)
        GlMatrix.multiplyMM(mvpMatrix, 0, baseProjectionMatrix, 0, modelMatrix, 0)
        return mvpMatrix
    }

    fun renderWallpaper(frame: GpuFrameRequest) {
        missingConfiguredTextureInFrame = false
        val entryScale = frame.entryScale.coerceIn(1f, 2f)
        if (entryScale > 1.0005f) {
            GlMatrix.setIdentityM(sceneScaleMatrix, 0)
            GlMatrix.translateM(sceneScaleMatrix, 0, frame.width / 2f, frame.height / 2f, 0f)
            GlMatrix.scaleM(sceneScaleMatrix, 0, entryScale, entryScale, 1f)
            GlMatrix.translateM(sceneScaleMatrix, 0, -frame.width / 2f, -frame.height / 2f, 0f)
            GlMatrix.multiplyMM(projectionMatrix, 0, baseProjectionMatrix, 0, sceneScaleMatrix, 0)
        } else {
            System.arraycopy(baseProjectionMatrix, 0, projectionMatrix, 0, 16)
        }

        val setting = frame.state.transitions.getOrNull(frame.pageA)
        val transitionId = setting?.transitionId(frame.swipeForward) ?: frame.state.transitionType
        val transition = TransitionRegistry.get(transitionId)
        val isEdgeOverScroll = frame.pageA == frame.pageB &&
            abs(frame.edgeOverScrollFraction) > EdgeOverScrollController.FRACTION_EPSILON
        val pageCount = frame.state.calibration.pageCount.coerceAtLeast(1)

        try {
            if (isEdgeOverScroll) {
                val edgePage = resolvePageTextures(frame, frame.pageA, usesParallax = true)
                val page = edgePage.primary ?: return
                val sourceLeft = EdgeOverScrollScaling.computeSourceLeftPx(
                    intensity = frame.edgeOverScrollFraction,
                    leftTravelPx = page.leftTravelPx,
                    rightTravelPx = page.rightTravelPx,
                    screenWidth = frame.width
                )
                drawPage(edgePage, 0, sourceLeft, frame, 1f)
                return
            }

            val hasLeftEdgeResidual = frame.pageA == 0 &&
                frame.edgeOverScrollFraction < -EdgeOverScrollController.FRACTION_EPSILON
            val lastPageIndex = (pageCount - 1).coerceAtLeast(0)
            val hasRightEdgeResidual = frame.pageB == lastPageIndex &&
                frame.edgeOverScrollFraction > EdgeOverScrollController.FRACTION_EPSILON

            val pageATex = resolvePageTextures(
                frame, frame.pageA, transition.supportsParallax || hasLeftEdgeResidual
            )
            val pageBTex = if (frame.pageA == frame.pageB) {
                PageRenderTextures(primary = null)
            } else {
                resolvePageTextures(
                    frame, frame.pageB, transition.supportsParallax || hasRightEdgeResidual
                )
            }
            val pageA = pageATex.primary
            val pageB = pageBTex.primary
            val pageAEdgeResidualPx = if (hasLeftEdgeResidual && pageA != null && pageA.leftTravelPx > 0) {
                -EdgeOverScrollScaling.computeTravelPx(
                    absIntensity = -frame.edgeOverScrollFraction,
                    availableTravelPx = pageA.leftTravelPx,
                    screenWidth = frame.width
                )
            } else {
                0f
            }
            val pageBEdgeResidualPx = if (hasRightEdgeResidual && pageB != null && pageB.rightTravelPx > 0) {
                EdgeOverScrollScaling.computeTravelPx(
                    absIntensity = frame.edgeOverScrollFraction,
                    availableTravelPx = pageB.rightTravelPx,
                    screenWidth = frame.width
                )
            } else {
                0f
            }
            val parallaxMotion = setting?.parallaxMotion(frame.swipeForward)
            val slowImageMotionEnabled = parallaxMotion?.slowImageMotionEnabled ?: false
            val parallaxOutgoingRate = parallaxMotion?.outgoingMotionRate?.coerceIn(0f, 1f) ?: 1f
            val parallaxIncomingRate = parallaxMotion?.incomingMotionRate?.coerceIn(0f, 1f) ?: 1f
            val revealRates = setting?.revealMotionRates(frame.swipeForward)
            val revealOutgoingRate = revealRates?.outgoingRate ?: 0f
            val revealIncomingRate = revealRates?.incomingRate ?: 0f

            when (transition.id) {
                "parallax", "parallax_classic" -> drawParallax(
                    frame, pageATex, pageBTex,
                    transition.id == "parallax_classic",
                    slowImageMotionEnabled,
                    parallaxOutgoingRate, parallaxIncomingRate,
                    pageAEdgeResidualPx, pageBEdgeResidualPx
                )
                "swipe_reveal", "swipe_reveal_stacked" -> drawSwipeReveal(
                    frame, pageATex, pageBTex,
                    transition.id == "swipe_reveal",
                    revealOutgoingRate, revealIncomingRate,
                    pageAEdgeResidualPx, pageBEdgeResidualPx
                )
                "fade" -> drawFade(frame, pageATex, pageBTex)
                "soft_blur" -> drawSoftBlur(frame, pageATex, pageBTex)
                "depth" -> drawDepth(frame, pageATex, pageBTex)
                "slide_over" -> drawSlideOver(
                    frame, pageATex, pageBTex, pageAEdgeResidualPx, pageBEdgeResidualPx
                )
                "cube" -> drawCube(frame, pageATex, pageBTex)
                else -> drawScroll(
                    frame, pageATex, pageBTex, pageAEdgeResidualPx, pageBEdgeResidualPx
                )
            }
        } finally {
            System.arraycopy(baseProjectionMatrix, 0, projectionMatrix, 0, 16)
        }
    }

    fun renderOverlayAndDebug(frame: GpuFrameRequest) {
        if (frame.entryBlackFadeAlpha > 0.004f) {
            drawRect(
                0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                0f, 0f, 0f, frame.entryBlackFadeAlpha.coerceIn(0f, 1f)
            )
        }
        drawDebugIndicators(frame)
    }

    private fun resolvePageTextures(
        frame: GpuFrameRequest,
        pageIndex: Int,
        usesParallax: Boolean
    ): PageRenderTextures {
        val page = frame.state.pages.find { it.pageIndex == pageIndex }
        val canDarken = repository.shouldDarkenPage(page, frame.state.darken)
        val effectiveMix = if (canDarken) frame.darkenMix.coerceIn(0f, 1f) else 0f
        if (effectiveMix <= 0.001f) {
            return PageRenderTextures(primary = getPageTexture(frame, pageIndex, usesParallax, false))
        }
        if (effectiveMix >= 0.999f) {
            return PageRenderTextures(primary = getPageTexture(frame, pageIndex, usesParallax, true))
        }
        val normal = getPageTexture(frame, pageIndex, usesParallax, false)
        val dark = getPageTexture(frame, pageIndex, usesParallax, true)
        return when {
            normal != null && dark != null -> PageRenderTextures(normal, dark, effectiveMix)
            normal != null -> PageRenderTextures(primary = normal)
            else -> PageRenderTextures(primary = dark)
        }
    }

    private fun getPageTexture(
        frame: GpuFrameRequest,
        pageIndex: Int,
        usesParallax: Boolean,
        darkened: Boolean
    ): PageTexture? {
        val slot = AssetSlot(pageIndex, usesParallax, darkened)
        val page = frame.state.pages.find { it.pageIndex == pageIndex }
        val isIntentionallyBlank = page == null || page.isBlank ||
            (page.originalId == null && page.croppedFileName == null)
        val key = AssetKey(
            generation = repository.bitmapGeneration,
            darkenedGeneration = if (darkened) repository.darkenedBitmapGeneration else 0L,
            page = page,
            pageIndex = pageIndex,
            width = frame.width,
            height = frame.height,
            usesParallax = usesParallax,
            darkened = darkened
        )
        pageTextures[slot]?.let { if (it.key == key) return it }
        val now = System.nanoTime()
        missingAssets[slot]?.let {
            if (it.key == key && now < it.retryAfterNanos) {
                if (!isIntentionallyBlank) missingConfiguredTextureInFrame = true
                return null
            }
        }
        val parallax = if (usesParallax) {
            repository.getParallaxBitmap(pageIndex, frame.width, frame.height, darkened = darkened)
        } else {
            repository.getCroppedBitmap(pageIndex, frame.width, frame.height, darkened = darkened)?.let {
                WallpaperRepository.ParallaxBitmap(it, 0, 0)
            }
        }
        val bitmap = parallax?.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            val delay = if (isIntentionallyBlank) BLANK_ASSET_RETRY_NANOS else {
                missingConfiguredTextureInFrame = true
                TRANSIENT_ASSET_RETRY_NANOS
            }
            missingAssets[slot] = MissingAsset(key, now + delay)
            return null
        }

        val textureId = uploadScaledBitmap(bitmap)
        if (textureId == 0) {
            missingConfiguredTextureInFrame = true
            missingAssets[slot] = MissingAsset(key, now + TRANSIENT_ASSET_RETRY_NANOS)
            return null
        }
        var blurredTextureId = 0
        val blurred = ImageProcessingUtils.createSmoothBlurredCopy(bitmap)
        try {
            blurredTextureId = uploadScaledBitmap(blurred)
        } finally {
            blurred.recycle()
        }
        val texture = PageTexture(
            key = key,
            textureId = textureId,
            blurredTextureId = blurredTextureId,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            leftTravelPx = parallax.leftTravelPx,
            rightTravelPx = parallax.rightTravelPx
        )
        val prev = pageTextures.put(slot, texture)
        if (prev != null) {
            if (prev.textureId != 0) sink.deleteTexture(prev.textureId)
            if (prev.blurredTextureId != 0) sink.deleteTexture(prev.blurredTextureId)
        }
        missingAssets.remove(slot)
        return texture
    }

    private fun uploadScaledBitmap(source: Bitmap): Int {
        val maxSize = sink.maxTextureSize.coerceAtLeast(2048)
        var bitmap = source
        if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        return try {
            sink.uploadBitmap(bitmap)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun drawScroll(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures,
        pageAEdgeResidualPx: Float,
        pageBEdgeResidualPx: Float
    ) {
        val pageA = pageATex.primary
        val pageB = pageBTex.primary
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageA != null && p < 1f) {
            val leftA = (-p * frame.width).toInt()
            if (pageAEdgeResidualPx != 0f && pageA.originalWidth > frame.width) {
                drawPage(pageATex, leftA, pageA.leftTravelPx + pageAEdgeResidualPx, frame, 1f)
            } else {
                drawFullPage(pageATex, leftA, frame, 1f)
            }
        }
        if (pageB != null && p > 0f) {
            val leftB = ((1f - p) * frame.width).toInt()
            if (pageBEdgeResidualPx != 0f && pageB.originalWidth > frame.width) {
                drawPage(pageBTex, leftB, pageB.leftTravelPx + pageBEdgeResidualPx, frame, 1f)
            } else {
                drawFullPage(pageBTex, leftB, frame, 1f)
            }
        }
    }

    private fun drawParallax(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures,
        classicIncoming: Boolean,
        slowImageMotionEnabled: Boolean,
        outgoingMotionRate: Float,
        incomingMotionRate: Float,
        pageAEdgeResidualPx: Float,
        pageBEdgeResidualPx: Float
    ) {
        val pageA = pageATex.primary
        val pageB = pageBTex.primary
        val p = frame.progress.coerceIn(0f, 1f)
        val outgoingRate = outgoingMotionRate.coerceIn(0f, 1f)
        val incomingRate = incomingMotionRate.coerceIn(0f, 1f)
        val pageARate = if (frame.swipeForward) outgoingRate else incomingRate
        val pageBRate = if (frame.swipeForward) incomingRate else outgoingRate
        val sourceA = (
            if (slowImageMotionEnabled) {
                (pageA?.leftTravelPx ?: 0) * (1f - pageARate * p)
            } else {
                (pageA?.leftTravelPx ?: 0) + (pageA?.rightTravelPx ?: 0) * pageARate * p
            }
            ) + pageAEdgeResidualPx
        val sourceB = (
            if (slowImageMotionEnabled || classicIncoming) {
                (pageB?.leftTravelPx ?: 0) + (pageB?.rightTravelPx ?: 0) * pageBRate * (1f - p)
            } else {
                (pageB?.leftTravelPx ?: 0) * (1f - pageBRate * (1f - p))
            }
            ) + pageBEdgeResidualPx
        if (pageA != null) drawPage(pageATex, (-p * frame.width).toInt(), sourceA, frame, 1f)
        if (pageB != null) drawPage(pageBTex, ((1f - p) * frame.width).toInt(), sourceB, frame, 1f)
    }

    private fun drawSwipeReveal(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures,
        followsDirection: Boolean,
        outgoingRevealRate: Float,
        incomingRevealRate: Float,
        pageAEdgeResidualPx: Float,
        pageBEdgeResidualPx: Float
    ) {
        val pageA = pageATex.primary
        val pageB = pageBTex.primary
        val p = frame.progress.coerceIn(0f, 1f)
        val pageARate = if (frame.swipeForward) outgoingRevealRate else incomingRevealRate
        val pageBRate = if (frame.swipeForward) incomingRevealRate else outgoingRevealRate
        val sourceA = (pageA?.leftTravelPx ?: 0) + (pageA?.rightTravelPx ?: 0) * pageARate * p +
            pageAEdgeResidualPx
        val sourceB = (pageB?.leftTravelPx ?: 0) * (1f - pageBRate * (1f - p)) +
            pageBEdgeResidualPx
        if (followsDirection && !frame.swipeForward) {
            if (pageA != null) drawPage(pageATex, 0, sourceA, frame, 1f)
            if (pageB != null) drawPage(pageBTex, ((1f - p) * frame.width).toInt(), sourceB, frame, 1f)
        } else {
            if (pageB != null) drawPage(pageBTex, 0, sourceB, frame, 1f)
            if (pageA != null) drawPage(pageATex, (-p * frame.width).toInt(), sourceA, frame, 1f)
        }
    }

    private fun drawFade(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures
    ) {
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageATex.primary != null) drawFullPage(pageATex, 0, frame, 1f - p)
        if (pageBTex.primary != null) drawFullPage(pageBTex, 0, frame, p)
    }

    private fun drawSoftBlur(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures
    ) {
        val pageA = pageATex.primary
        val pageB = pageBTex.primary
        val p = frame.progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        if (pageA != null) drawFullPage(pageATex, 0, frame, 1f - eased)
        if (pageB != null) drawFullPage(pageBTex, 0, frame, eased)
        val blurStrength = (sin(Math.PI * eased).toFloat() * 0.22f).coerceIn(0f, 0.22f)
        if (blurStrength > 0.001f) {
            pageA?.takeIf { it.blurredTextureId != 0 }?.let {
                drawScaledTexture(
                    it.blurredTextureId, frame, 1f, (1f - eased) * blurStrength,
                    darkTextureId = pageATex.darkBlendTarget?.blurredTextureId ?: 0,
                    darkMix = pageATex.darkMix
                )
            }
            pageB?.takeIf { it.blurredTextureId != 0 }?.let {
                drawScaledTexture(
                    it.blurredTextureId, frame, 1f, eased * blurStrength,
                    darkTextureId = pageBTex.darkBlendTarget?.blurredTextureId ?: 0,
                    darkMix = pageBTex.darkMix
                )
            }
        }
    }

    private fun drawDepth(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures
    ) {
        val pageA = pageATex.primary
        val pageB = pageBTex.primary
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageA != null && p < 1f) {
            drawScaledTexture(
                pageA.textureId, frame, 1f - 0.22f * p, 1f - p,
                blurredTextureId = pageA.blurredTextureId,
                darkTextureId = pageATex.darkBlendTarget?.textureId ?: 0,
                darkBlurredTextureId = pageATex.darkBlendTarget?.blurredTextureId ?: 0,
                darkMix = pageATex.darkMix
            )
        }
        if (pageB != null && p > 0f) {
            drawScaledTexture(
                pageB.textureId, frame, 1.15f - 0.15f * p, p,
                blurredTextureId = pageB.blurredTextureId,
                darkTextureId = pageBTex.darkBlendTarget?.textureId ?: 0,
                darkBlurredTextureId = pageBTex.darkBlendTarget?.blurredTextureId ?: 0,
                darkMix = pageBTex.darkMix
            )
        }
    }

    private fun drawSlideOver(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures,
        pageAEdgeResidualPx: Float,
        pageBEdgeResidualPx: Float
    ) {
        val pageA = pageATex.primary
        val pageB = pageBTex.primary
        val p = frame.progress.coerceIn(0f, 1f)
        pageA?.let {
            if (pageAEdgeResidualPx != 0f && it.originalWidth > frame.width) {
                drawPage(pageATex, 0, it.leftTravelPx + pageAEdgeResidualPx, frame, 1f)
            } else {
                drawFullPage(pageATex, 0, frame, 1f)
            }
            if (p > 0f) {
                drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), 0f, 0f, 0f, quantizedAlpha(p * 110f / 255f))
            }
        }
        if (pageB != null && p > 0f) {
            val left = ((1f - p) * frame.width).toInt()
            if (pageBEdgeResidualPx != 0f && pageB.originalWidth > frame.width) {
                drawPage(pageBTex, left, pageB.leftTravelPx + pageBEdgeResidualPx, frame, 1f)
            } else {
                drawFullPage(pageBTex, left, frame, 1f)
            }
            if (left > 0) {
                val stripeWidth = (frame.width * 0.05f).toInt()
                val stripeLeft = (left - stripeWidth).coerceAtLeast(0).toFloat()
                drawRect(stripeLeft, 0f, left - stripeLeft, frame.height.toFloat(), 0f, 0f, 0f, 120f / 255f)
            }
        }
    }

    private fun drawCube(
        frame: GpuFrameRequest,
        pageATex: PageRenderTextures,
        pageBTex: PageRenderTextures
    ) {
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageATex.primary != null && p < 1f) {
            drawCubeFace(pageATex, frame, -90f * p, frame.width.toFloat())
            drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), 0f, 0f, 0f, quantizedAlpha(p * 180f / 255f))
        }
        if (pageBTex.primary != null && p > 0f) {
            drawCubeFace(pageBTex, frame, 90f * (1f - p), 0f)
            drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), 0f, 0f, 0f, quantizedAlpha((1f - p) * 180f / 255f))
        }
    }

    private fun drawCubeFace(
        pageTex: PageRenderTextures,
        frame: GpuFrameRequest,
        rotation: Float,
        pivotX: Float
    ) {
        val page = pageTex.primary ?: return
        cubeCamera.save()
        cubeCamera.rotateY(rotation)
        graphicsMatrix.reset()
        cubeCamera.getMatrix(graphicsMatrix)
        cubeCamera.restore()
        graphicsMatrix.preTranslate(-pivotX, -frame.height / 2f)
        graphicsMatrix.postTranslate(pivotX, frame.height / 2f)
        graphicsMatrix.getValues(graphicsMatrixValues)
        graphicsMatrixToGl(graphicsMatrixValues, cubeMatrix)
        GlMatrix.setIdentityM(cubeScaleMatrix, 0)
        GlMatrix.scaleM(cubeScaleMatrix, 0, frame.width.toFloat(), frame.height.toFloat(), 1f)
        GlMatrix.multiplyMM(modelMatrix, 0, cubeMatrix, 0, cubeScaleMatrix, 0)
        GlMatrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        sink.drawTexturedQuad(
            textureId = page.textureId,
            mvp = mvpMatrix,
            alpha = 1f,
            darkTextureId = pageTex.darkBlendTarget?.textureId ?: 0,
            darkMix = pageTex.darkMix
        )
        if (frame.entryBlurMix > 0.004f && page.blurredTextureId != 0) {
            val mix = frame.entryBlurMix.coerceIn(0f, 1f)
            sink.drawTexturedQuad(
                textureId = page.blurredTextureId,
                mvp = mvpMatrix,
                alpha = mix,
                red = mix,
                green = mix,
                blue = mix,
                darkTextureId = pageTex.darkBlendTarget?.blurredTextureId ?: 0,
                darkMix = pageTex.darkMix
            )
        }
    }

    private fun drawPage(
        pageTex: PageRenderTextures,
        left: Int,
        sourceLeft: Float,
        frame: GpuFrameRequest,
        alpha: Float
    ) {
        val page = pageTex.primary ?: return
        val darkTextureId = pageTex.darkBlendTarget?.textureId ?: 0
        val darkBlurredTextureId = pageTex.darkBlendTarget?.blurredTextureId ?: 0
        val uvScaleX: Float
        val uvOffsetX: Float
        if (page.originalWidth <= frame.width) {
            uvScaleX = 1f
            uvOffsetX = 0f
        } else {
            val boundedLeft = sourceLeft.coerceIn(0f, (page.originalWidth - frame.width).toFloat())
            uvScaleX = frame.width.toFloat() / page.originalWidth
            uvOffsetX = boundedLeft / page.originalWidth
        }
        drawTextureModel(
            page.textureId, left.toFloat(), 0f, frame.width.toFloat(), frame.height.toFloat(),
            alpha, uvOffsetX, uvScaleX, darkTextureId = darkTextureId, darkMix = pageTex.darkMix
        )
        if (frame.entryBlurMix > 0.004f && page.blurredTextureId != 0) {
            val mix = (frame.entryBlurMix * alpha).coerceIn(0f, 1f)
            drawTextureModel(
                page.blurredTextureId, left.toFloat(), 0f, frame.width.toFloat(), frame.height.toFloat(),
                mix, uvOffsetX, uvScaleX, red = mix, green = mix, blue = mix,
                darkTextureId = darkBlurredTextureId, darkMix = pageTex.darkMix
            )
        }
    }

    private fun drawFullPage(
        pageTex: PageRenderTextures,
        left: Int,
        frame: GpuFrameRequest,
        alpha: Float
    ) {
        val page = pageTex.primary ?: return
        val darkTextureId = pageTex.darkBlendTarget?.textureId ?: 0
        val darkBlurredTextureId = pageTex.darkBlendTarget?.blurredTextureId ?: 0
        drawTextureModel(
            page.textureId, left.toFloat(), 0f, frame.width.toFloat(), frame.height.toFloat(),
            alpha, darkTextureId = darkTextureId, darkMix = pageTex.darkMix
        )
        if (frame.entryBlurMix > 0.004f && page.blurredTextureId != 0) {
            val mix = (frame.entryBlurMix * alpha).coerceIn(0f, 1f)
            drawTextureModel(
                page.blurredTextureId, left.toFloat(), 0f, frame.width.toFloat(), frame.height.toFloat(),
                mix, red = mix, green = mix, blue = mix,
                darkTextureId = darkBlurredTextureId, darkMix = pageTex.darkMix
            )
        }
    }

    private fun drawScaledTexture(
        textureId: Int,
        frame: GpuFrameRequest,
        scale: Float,
        alpha: Float,
        blurredTextureId: Int = 0,
        darkTextureId: Int = 0,
        darkBlurredTextureId: Int = 0,
        darkMix: Float = 0f
    ) {
        val width = frame.width * scale
        val height = frame.height * scale
        val left = (frame.width - width) / 2f
        val top = (frame.height - height) / 2f
        drawTextureModel(
            textureId, left, top, width, height, alpha,
            darkTextureId = darkTextureId, darkMix = darkMix
        )
        if (frame.entryBlurMix > 0.004f && blurredTextureId != 0) {
            val mix = (frame.entryBlurMix * alpha).coerceIn(0f, 1f)
            drawTextureModel(
                blurredTextureId, left, top, width, height, mix,
                red = mix, green = mix, blue = mix,
                darkTextureId = darkBlurredTextureId, darkMix = darkMix
            )
        }
    }

    private fun drawTextureModel(
        textureId: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        alpha: Float,
        uvOffsetX: Float = 0f,
        uvScaleX: Float = 1f,
        red: Float = 1f,
        green: Float = 1f,
        blue: Float = 1f,
        darkTextureId: Int = 0,
        darkMix: Float = 0f
    ) {
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.translateM(modelMatrix, 0, left, top, 0f)
        GlMatrix.scaleM(modelMatrix, 0, width, height, 1f)
        GlMatrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        sink.drawTexturedQuad(
            textureId = textureId,
            mvp = mvpMatrix,
            uvOffsetX = uvOffsetX,
            uvScaleX = uvScaleX,
            alpha = quantizedAlpha(alpha),
            red = red,
            green = green,
            blue = blue,
            darkTextureId = darkTextureId,
            darkMix = darkMix
        )
    }

    private fun drawRect(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float
    ) {
        if (width <= 0f || height <= 0f || alpha <= 0f) return
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.translateM(modelMatrix, 0, left, top, 0f)
        GlMatrix.scaleM(modelMatrix, 0, width, height, 1f)
        GlMatrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        sink.drawSolidRect(mvpMatrix, red, green, blue, quantizedAlpha(alpha))
    }

    private fun drawDebugIndicators(frame: GpuFrameRequest) {
        val showsOffset = frame.state.showOffsetDebugOverlay
        val showsFrame = frame.state.showFrameDebugIndicator && frame.isVisible
        val showsSignal = frame.state.showSignalDebugOverlay && frame.signalOverlayLines.isNotEmpty()
        if (!showsOffset && !showsFrame && !showsSignal) return
        if (showsOffset || showsFrame) ensureDebugTextures(frame.density)
        if (showsOffset) drawOffsetDebugOverlay(frame)
        if (showsSignal) drawSignalDebugOverlay(frame)
        if (showsFrame) {
            val d = frame.density
            val size = 28f * d
            val margin = 20f * d
            val left = frame.width - size - margin
            val top = margin
            val border = 2f * d
            drawRect(left, top, size, border, 1f, 1f, 1f, 1f)
            drawRect(left, top + size - border, size, border, 1f, 1f, 1f, 1f)
            drawRect(left, top + border, border, size - 2f * border, 1f, 1f, 1f, 1f)
            drawRect(left + size - border, top + border, border, size - 2f * border, 1f, 1f, 1f, 1f)
            drawRect(
                left + border, top + border, size - 2f * border, size - 2f * border,
                Color.red(frame.frameDebugColor) / 255f,
                Color.green(frame.frameDebugColor) / 255f,
                Color.blue(frame.frameDebugColor) / 255f,
                1f
            )
        }
    }

    private fun drawSignalDebugOverlay(frame: GpuFrameRequest) {
        val lines = frame.signalOverlayLines
        if (lines.isEmpty()) return
        val d = frame.density
        if (signalCardTexture == 0 || signalCardLines != lines || signalCardDensity != d) {
            if (signalCardTexture != 0) {
                sink.deleteTexture(signalCardTexture)
                signalCardTexture = 0
            }
            signalCardLines = lines
            signalCardDensity = d
            val cardWidth = minOf(352f * d, (frame.width - 20f * d).coerceAtLeast(180f))
            val lineH = 15f * d
            val headerH = 22f * d
            val pad = 12f * d
            val bmpW = ceil(cardWidth).toInt().coerceAtLeast(1)
            val bmpH = ceil(pad * 2f + headerH + lines.size * lineH).toInt().coerceAtLeast(1)
            signalCardWidthPx = bmpW
            signalCardHeightPx = bmpH
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawRoundRect(
                RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()),
                14f * d, 14f * d,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(228, 15, 23, 42) }
            )
            canvas.drawRoundRect(
                RectF(d, d, bmpW - d, bmpH - d),
                14f * d, 14f * d,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(140, 56, 189, 248)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f * d
                }
            )
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(125, 211, 252)
                textSize = 11.5f * d
                isFakeBoldText = true
            }
            canvas.drawText("Live Signal Diagnostics", pad, pad + 11.5f * d, titlePaint)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.5f * d
                typeface = Typeface.MONOSPACE
            }
            var y = pad + headerH + 10.5f * d
            lines.forEachIndexed { idx, line ->
                linePaint.color = when {
                    idx < 2 -> Color.rgb(167, 243, 208)
                    idx == 2 && line.startsWith("CMD:") -> Color.rgb(253, 230, 138)
                    else -> Color.rgb(226, 232, 240)
                }
                canvas.drawText(line, pad, y, linePaint)
                y += lineH
            }
            signalCardTexture = sink.uploadBitmap(bmp)
            bmp.recycle()
        }
        val top = if (frame.state.showOffsetDebugOverlay) 186f * d else 96f * d
        val left = ((frame.width - signalCardWidthPx) / 2f).coerceAtLeast(8f * d)
        drawTextureModel(signalCardTexture, left, top, signalCardWidthPx.toFloat(), signalCardHeightPx.toFloat(), 1f)
    }

    private fun ensureDebugTextures(density: Float) {
        if (debugCardTexture != 0 && debugTextureDensity == density) return
        if (debugCardTexture != 0) sink.deleteTexture(debugCardTexture)
        if (debugCircleTexture != 0) sink.deleteTexture(debugCircleTexture)
        debugTextureDensity = density
        val width = ceil(240f * density).toInt().coerceAtLeast(1)
        val height = ceil(82f * density).toInt().coerceAtLeast(1)
        val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            12f * density, 12f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 22, 28, 40) }
        )
        val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Launcher offset received", width / 2f, 19f * density, labels)
        labels.textAlign = Paint.Align.LEFT
        canvas.drawText("X", 16f * density, 45f * density, labels)
        canvas.drawText("Y", 16f * density, 68f * density, labels)
        debugCardTexture = sink.uploadBitmap(card)
        card.recycle()

        val circle = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(circle).drawCircle(32f, 32f, 31f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        debugCircleTexture = sink.uploadBitmap(circle)
        circle.recycle()
    }

    private fun drawOffsetDebugOverlay(frame: GpuFrameRequest) {
        val d = frame.density
        val cardWidth = 240f * d
        val cardHeight = 82f * d
        val left = (frame.width - cardWidth) / 2f
        val top = 20f * d
        drawTextureModel(debugCardTexture, left, top, cardWidth, cardHeight, 1f)
        val trackStart = left + 42f * d
        val trackEnd = left + cardWidth - 16f * d
        val trackWidth = trackEnd - trackStart
        val lineH = 3f * d
        val xY = top + 41f * d
        val yY = top + 64f * d
        drawRect(trackStart, xY - lineH / 2f, trackWidth, lineH, 210f / 255f, 220f / 255f, 235f / 255f, 150f / 255f)
        drawRect(trackStart, yY - lineH / 2f, trackWidth, lineH, 210f / 255f, 220f / 255f, 235f / 255f, 150f / 255f)
        frame.receivedXOffset?.let { offset ->
            drawDebugDot(trackStart + trackWidth * offset.coerceIn(0f, 1f), xY, 5f * d, 80f / 255f, 220f / 255f, 1f)
        }
        frame.receivedYOffset?.let { offset ->
            drawDebugDot(trackStart + trackWidth * offset.coerceIn(0f, 1f), yY, 5f * d, 1f, 180f / 255f, 0f)
        }
    }

    private fun drawDebugDot(cx: Float, cy: Float, radius: Float, r: Float, g: Float, b: Float) {
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.translateM(modelMatrix, 0, cx - radius, cy - radius, 0f)
        GlMatrix.scaleM(modelMatrix, 0, radius * 2f, radius * 2f, 1f)
        GlMatrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        sink.drawTexturedQuad(debugCircleTexture, mvpMatrix, red = r, green = g, blue = b)
    }

    private fun graphicsMatrixToGl(values: FloatArray, output: FloatArray) {
        output.fill(0f)
        output[0] = values[Matrix.MSCALE_X]
        output[4] = values[Matrix.MSKEW_X]
        output[12] = values[Matrix.MTRANS_X]
        output[1] = values[Matrix.MSKEW_Y]
        output[5] = values[Matrix.MSCALE_Y]
        output[13] = values[Matrix.MTRANS_Y]
        output[3] = values[Matrix.MPERSP_0]
        output[7] = values[Matrix.MPERSP_1]
        output[10] = 1f
        output[15] = values[Matrix.MPERSP_2]
    }

    private fun quantizedAlpha(alpha: Float): Float =
        (alpha.coerceIn(0f, 1f) * 255f).toInt() / 255f

    companion object {
        private const val TRANSIENT_ASSET_RETRY_NANOS = 100_000_000L
        private const val BLANK_ASSET_RETRY_NANOS = 2_000_000_000L
    }
}
