package com.example.render

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix as GlMatrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.data.model.PageItem
import com.example.data.model.WallpaperAppState
import com.example.data.repository.WallpaperRepository
import com.example.transition.TransitionRegistry
import com.example.util.ImageProcessingUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.sin

data class GpuFrameRequest(
    val state: WallpaperAppState,
    val width: Int,
    val height: Int,
    val pageA: Int,
    val pageB: Int,
    val progress: Float,
    val pagePosition: Float,
    val frameTimeNanos: Long,
    val fromOffsetUpdate: Boolean,
    val isVisible: Boolean,
    val swipeForward: Boolean,
    val resumeFadeProgress: Float,
    val motionBlurRadius: Int,
    val motionBlurDirection: Int,
    val dimAmount: Float,
    val receivedXOffset: Float?,
    val receivedYOffset: Float?,
    val density: Float,
    val frameDebugColor: Int,
    val edgeOverScrollFraction: Float = 0f
)

class GpuWallpaperRenderer(
    private val repository: WallpaperRepository
) {
    private data class PendingFrame(
        val request: GpuFrameRequest,
        val onSubmitted: (GpuFrameRequest) -> Unit,
        val onFailure: () -> Unit
    )

    private data class AssetKey(
        val generation: Long,
        val page: PageItem?,
        val pageIndex: Int,
        val width: Int,
        val height: Int,
        val usesParallax: Boolean
    )

    private data class AssetSlot(val pageIndex: Int, val usesParallax: Boolean)

    private inner class PageTexture(
        val key: AssetKey,
        val textureId: Int,
        val blurredTextureId: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val leftTravelPx: Int,
        val rightTravelPx: Int
    ) {
        fun delete() {
            glDeleteTextures(textureId, blurredTextureId)
        }
    }

    private data class MissingAsset(val key: AssetKey, val retryAfterNanos: Long)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("PagedWallpaperGL").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val frameLock = Any()
    private var renderScheduled = false
    private var pendingFrame: PendingFrame? = null

    @Volatile
    private var windowSurface: Surface? = null

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    @Volatile
    private var released = false

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var textureProgram = 0
    private var blurProgram = 0
    private var whiteTexture = 0
    private var debugCardTexture = 0
    private var debugCircleTexture = 0
    private var debugTextureDensity = 0f
    private var maxTextureSize = 0
    private var motionBlurTexture = 0
    private var motionBlurFramebuffer = 0
    private var motionBlurWidth = 0
    private var motionBlurHeight = 0

    private val pageTextures = mutableMapOf<AssetSlot, PageTexture>()
    private val missingAssets = mutableMapOf<AssetSlot, MissingAsset>()

    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val cubeMatrix = FloatArray(16)
    private val cubeScaleMatrix = FloatArray(16)
    private val graphicsMatrixValues = FloatArray(9)
    private val cubeCamera = Camera()
    private val graphicsMatrix = android.graphics.Matrix()

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    0f, 0f, 0f, 0f,
                    1f, 0f, 1f, 0f,
                    0f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        val sizeChanged = surfaceWidth != width || surfaceHeight != height
        windowSurface = surface
        surfaceWidth = width
        surfaceHeight = height
        renderHandler.post {
            if (released) return@post
            if (sizeChanged) {
                releasePageTextures()
                releaseMotionBlurTarget()
            }
        }
    }

    fun onSurfaceDestroyed() {
        windowSurface = null
        synchronized(frameLock) { pendingFrame = null }
        renderHandler.post { destroyGl() }
    }

    fun render(
        request: GpuFrameRequest,
        onSubmitted: (GpuFrameRequest) -> Unit,
        onFailure: () -> Unit
    ) {
        if (released) return
        val shouldSchedule = synchronized(frameLock) {
            pendingFrame = PendingFrame(request, onSubmitted, onFailure)
            if (renderScheduled) {
                false
            } else {
                renderScheduled = true
                true
            }
        }
        if (shouldSchedule) renderHandler.post { drainFrame() }
    }

    fun release() {
        if (released) return
        released = true
        windowSurface = null
        synchronized(frameLock) { pendingFrame = null }
        renderHandler.post {
            destroyGl()
            renderThread.quitSafely()
        }
    }

    private fun drainFrame() {
        val frame = synchronized(frameLock) {
            val next = pendingFrame
            pendingFrame = null
            renderScheduled = false
            next
        }
        if (frame == null || released) return
        val surface = windowSurface ?: return
        try {
            ensureGlReady(surface, frame.request.width, frame.request.height)
            drawFrame(frame.request)
            val display = eglDisplay ?: throw IllegalStateException("EGL display is unavailable")
            val drawSurface = eglSurface ?: throw IllegalStateException("EGL surface is unavailable")
            if (!EGL14.eglSwapBuffers(display, drawSurface)) {
                throw IllegalStateException("eglSwapBuffers failed: 0x${EGL14.eglGetError().toString(16)}")
            }
            mainHandler.post {
                if (!released) frame.onSubmitted(frame.request)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "GPU wallpaper rendering failed; using software fallback", error)
            destroyGl()
            mainHandler.post {
                if (!released && windowSurface != null) frame.onFailure()
            }
        }
    }

    private fun ensureGlReady(surface: Surface, width: Int, height: Int) {
        if (eglDisplay == null) {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("No EGL display")
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }
            eglDisplay = display

            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val counts = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, counts, 0) ||
                counts[0] <= 0
            ) {
                throw IllegalStateException("No OpenGL ES 2.0 configuration")
            }
            val context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("eglCreateContext failed")
            }
            eglContext = context

            val window = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (window == null || window == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed")
            }
            eglSurface = window
            if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }
            EGL14.eglSwapInterval(display, 1)
            initializeGlResources()
        }
        if (eglSurface == null) throw IllegalStateException("EGL surface was not created")
        GlMatrix.orthoM(
            projectionMatrix,
            0,
            0f,
            width.toFloat(),
            height.toFloat(),
            0f,
            -1000f,
            1000f
        )
        GLES20.glViewport(0, 0, width, height)
    }

    private fun initializeGlResources() {
        textureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        blurProgram = createProgram(TEXTURE_VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
        whiteTexture = createSolidTexture()
        val sizes = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, sizes, 0)
        maxTextureSize = sizes[0]
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawFrame(frame: GpuFrameRequest) {
        if (frame.motionBlurRadius > 0 && frame.motionBlurDirection != 0) {
            ensureMotionBlurTarget(frame.width, frame.height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionBlurFramebuffer)
            GLES20.glViewport(0, 0, motionBlurWidth, motionBlurHeight)
            renderWallpaper(frame, includeDebugIndicators = false)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, frame.width, frame.height)
            drawMotionBlur(frame)
            GLES20.glEnable(GLES20.GL_BLEND)
            drawDebugIndicators(frame)
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, frame.width, frame.height)
            renderWallpaper(frame, includeDebugIndicators = true)
        }
        if (frame.resumeFadeProgress < 1f) {
            drawRect(
                0f,
                0f,
                frame.width.toFloat(),
                frame.height.toFloat(),
                0f,
                0f,
                0f,
                1f - frame.resumeFadeProgress.coerceIn(0f, 1f)
            )
        }
    }

    private fun renderWallpaper(frame: GpuFrameRequest, includeDebugIndicators: Boolean) {
        GLES20.glClearColor(18f / 255f, 22f / 255f, 34f / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val setting = frame.state.transitions.getOrNull(frame.pageA)
        val transitionId = setting?.transitionId(frame.swipeForward) ?: frame.state.transitionType
        val transition = TransitionRegistry.get(transitionId)
        val isEdgeOverScroll = frame.pageA == frame.pageB &&
            kotlin.math.abs(frame.edgeOverScrollFraction) > EdgeOverScrollController.FRACTION_EPSILON

        if (isEdgeOverScroll) {
            val edgePage = getPageTexture(frame, frame.pageA, usesParallax = true)
            drawEdgeOverScroll(frame, edgePage)
        } else {
            val pageA = getPageTexture(frame, frame.pageA, transition.supportsParallax)
            val pageB = if (frame.pageA == frame.pageB) {
                null
            } else {
                getPageTexture(frame, frame.pageB, transition.supportsParallax)
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
                    frame,
                    pageA,
                    pageB,
                    transition.id == "parallax_classic",
                    slowImageMotionEnabled,
                    parallaxOutgoingRate,
                    parallaxIncomingRate
                )
                "swipe_reveal", "swipe_reveal_stacked" -> drawSwipeReveal(
                    frame,
                    pageA,
                    pageB,
                    transition.id == "swipe_reveal",
                    revealOutgoingRate,
                    revealIncomingRate
                )
                "fade" -> drawFade(frame, pageA, pageB)
                "soft_blur" -> drawSoftBlur(frame, pageA, pageB)
                "depth" -> drawDepth(frame, pageA, pageB)
                "slide_over" -> drawSlideOver(frame, pageA, pageB)
                "cube" -> drawCube(frame, pageA, pageB)
                else -> drawScroll(frame, pageA, pageB)
            }
        }

        if (frame.dimAmount > 0.01f) {
            drawRect(
                0f,
                0f,
                frame.width.toFloat(),
                frame.height.toFloat(),
                0f,
                0f,
                0f,
                frame.dimAmount.coerceIn(0f, 1f)
            )
        }
        if (includeDebugIndicators) drawDebugIndicators(frame)
    }

    private fun drawEdgeOverScroll(frame: GpuFrameRequest, page: PageTexture?) {
        if (page == null) return
        val sourceLeft = EdgeOverScrollScaling.computeSourceLeftPx(
            intensity = frame.edgeOverScrollFraction,
            leftTravelPx = page.leftTravelPx,
            rightTravelPx = page.rightTravelPx,
            screenWidth = frame.width
        )
        drawPage(page, 0, sourceLeft, frame, 1f)
    }

    private fun drawScroll(frame: GpuFrameRequest, pageA: PageTexture?, pageB: PageTexture?) {
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageA != null && p < 1f) {
            drawFullPage(pageA, (-p * frame.width).toInt(), frame, 1f)
        }
        if (pageB != null && p > 0f) {
            drawFullPage(pageB, ((1f - p) * frame.width).toInt(), frame, 1f)
        }
    }

    private fun drawParallax(
        frame: GpuFrameRequest,
        pageA: PageTexture?,
        pageB: PageTexture?,
        classicIncoming: Boolean,
        slowImageMotionEnabled: Boolean,
        outgoingMotionRate: Float,
        incomingMotionRate: Float
    ) {
        val p = frame.progress.coerceIn(0f, 1f)
        val outgoingRate = outgoingMotionRate.coerceIn(0f, 1f)
        val incomingRate = incomingMotionRate.coerceIn(0f, 1f)
        val pageARate = if (frame.swipeForward) outgoingRate else incomingRate
        val pageBRate = if (frame.swipeForward) incomingRate else outgoingRate
        val sourceA = if (slowImageMotionEnabled) {
            (pageA?.leftTravelPx ?: 0) * (1f - pageARate * p)
        } else {
            (pageA?.leftTravelPx ?: 0) + (pageA?.rightTravelPx ?: 0) * pageARate * p
        }
        val sourceB = if (slowImageMotionEnabled || classicIncoming) {
            (pageB?.leftTravelPx ?: 0) + (pageB?.rightTravelPx ?: 0) * pageBRate * (1f - p)
        } else {
            (pageB?.leftTravelPx ?: 0) * (1f - pageBRate * (1f - p))
        }
        pageA?.let { drawPage(it, (-p * frame.width).toInt(), sourceA, frame, 1f) }
        pageB?.let { drawPage(it, ((1f - p) * frame.width).toInt(), sourceB, frame, 1f) }
    }

    private fun drawSwipeReveal(
        frame: GpuFrameRequest,
        pageA: PageTexture?,
        pageB: PageTexture?,
        followsDirection: Boolean,
        outgoingRevealRate: Float,
        incomingRevealRate: Float
    ) {
        val p = frame.progress.coerceIn(0f, 1f)
        val pageARate = if (frame.swipeForward) outgoingRevealRate else incomingRevealRate
        val pageBRate = if (frame.swipeForward) incomingRevealRate else outgoingRevealRate
        val sourceA = (pageA?.leftTravelPx ?: 0) + (pageA?.rightTravelPx ?: 0) * pageARate * p
        val sourceB = (pageB?.leftTravelPx ?: 0) * (1f - pageBRate * (1f - p))
        if (followsDirection && !frame.swipeForward) {
            pageA?.let { drawPage(it, 0, sourceA, frame, 1f) }
            pageB?.let {
                drawPage(it, ((1f - p) * frame.width).toInt(), sourceB, frame, 1f)
            }
        } else {
            pageB?.let { drawPage(it, 0, sourceB, frame, 1f) }
            pageA?.let {
                drawPage(it, (-p * frame.width).toInt(), sourceA, frame, 1f)
            }
        }
    }

    private fun drawFade(frame: GpuFrameRequest, pageA: PageTexture?, pageB: PageTexture?) {
        val p = frame.progress.coerceIn(0f, 1f)
        pageA?.let { drawFullPage(it, 0, frame, 1f - p) }
        pageB?.let { drawFullPage(it, 0, frame, p) }
    }

    private fun drawSoftBlur(frame: GpuFrameRequest, pageA: PageTexture?, pageB: PageTexture?) {
        val p = frame.progress.coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        pageA?.let { drawFullPage(it, 0, frame, 1f - eased) }
        pageB?.let { drawFullPage(it, 0, frame, eased) }
        val blurStrength = (sin(Math.PI * eased).toFloat() * 0.22f).coerceIn(0f, 0.22f)
        if (blurStrength > 0.001f) {
            pageA?.let { drawTexture(it.blurredTextureId, frame, 1f, (1f - eased) * blurStrength) }
            pageB?.let { drawTexture(it.blurredTextureId, frame, 1f, eased * blurStrength) }
        }
    }

    private fun drawDepth(frame: GpuFrameRequest, pageA: PageTexture?, pageB: PageTexture?) {
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageA != null && p < 1f) {
            drawTexture(pageA.textureId, frame, 1f - 0.22f * p, 1f - p)
        }
        if (pageB != null && p > 0f) {
            drawTexture(pageB.textureId, frame, 1.15f - 0.15f * p, p)
        }
    }

    private fun drawSlideOver(frame: GpuFrameRequest, pageA: PageTexture?, pageB: PageTexture?) {
        val p = frame.progress.coerceIn(0f, 1f)
        pageA?.let {
            drawFullPage(it, 0, frame, 1f)
            if (p > 0f) {
                drawRect(
                    0f,
                    0f,
                    frame.width.toFloat(),
                    frame.height.toFloat(),
                    0f,
                    0f,
                    0f,
                    quantizedAlpha(p * 110f / 255f)
                )
            }
        }
        if (pageB != null && p > 0f) {
            val left = ((1f - p) * frame.width).toInt()
            drawFullPage(pageB, left, frame, 1f)
            if (left > 0) {
                val stripeWidth = (frame.width * 0.05f).toInt()
                drawRect(
                    (left - stripeWidth).coerceAtLeast(0).toFloat(),
                    0f,
                    (left - (left - stripeWidth).coerceAtLeast(0)).toFloat(),
                    frame.height.toFloat(),
                    0f,
                    0f,
                    0f,
                    120f / 255f
                )
            }
        }
    }

    private fun drawCube(frame: GpuFrameRequest, pageA: PageTexture?, pageB: PageTexture?) {
        val p = frame.progress.coerceIn(0f, 1f)
        if (pageA != null && p < 1f) {
            drawCubeFace(pageA.textureId, frame, -90f * p, frame.width.toFloat())
            drawRect(
                0f,
                0f,
                frame.width.toFloat(),
                frame.height.toFloat(),
                0f,
                0f,
                0f,
                quantizedAlpha(p * 180f / 255f)
            )
        }
        if (pageB != null && p > 0f) {
            drawCubeFace(pageB.textureId, frame, 90f * (1f - p), 0f)
            drawRect(
                0f,
                0f,
                frame.width.toFloat(),
                frame.height.toFloat(),
                0f,
                0f,
                0f,
                quantizedAlpha((1f - p) * 180f / 255f)
            )
        }
    }

    private fun drawCubeFace(textureId: Int, frame: GpuFrameRequest, rotation: Float, pivotX: Float) {
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
        GlMatrix.scaleM(
            cubeScaleMatrix,
            0,
            frame.width.toFloat(),
            frame.height.toFloat(),
            1f
        )
        GlMatrix.multiplyMM(modelMatrix, 0, cubeMatrix, 0, cubeScaleMatrix, 0)
        drawTexturedQuad(textureId, 0f, 0f, 1f, 1f, 1f)
    }

    private fun graphicsMatrixToGl(values: FloatArray, output: FloatArray) {
        output.fill(0f)
        output[0] = values[android.graphics.Matrix.MSCALE_X]
        output[4] = values[android.graphics.Matrix.MSKEW_X]
        output[12] = values[android.graphics.Matrix.MTRANS_X]
        output[1] = values[android.graphics.Matrix.MSKEW_Y]
        output[5] = values[android.graphics.Matrix.MSCALE_Y]
        output[13] = values[android.graphics.Matrix.MTRANS_Y]
        output[3] = values[android.graphics.Matrix.MPERSP_0]
        output[7] = values[android.graphics.Matrix.MPERSP_1]
        output[10] = 1f
        output[15] = values[android.graphics.Matrix.MPERSP_2]
    }

    private fun getPageTexture(
        frame: GpuFrameRequest,
        pageIndex: Int,
        usesParallax: Boolean
    ): PageTexture? {
        val slot = AssetSlot(pageIndex, usesParallax)
        val page = frame.state.pages.find { it.pageIndex == pageIndex }
        val key = AssetKey(
            repository.bitmapGeneration,
            page,
            pageIndex,
            frame.width,
            frame.height,
            usesParallax
        )
        pageTextures[slot]?.let { if (it.key == key) return it }
        val now = System.nanoTime()
        missingAssets[slot]?.let {
            if (it.key == key && now < it.retryAfterNanos) return null
        }

        val parallax = if (usesParallax) {
            repository.getParallaxBitmap(pageIndex, frame.width, frame.height)
        } else {
            repository.getCroppedBitmap(pageIndex, frame.width, frame.height)?.let {
                WallpaperRepository.ParallaxBitmap(it, 0, 0)
            }
        }
        val bitmap = parallax?.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            missingAssets[slot] = MissingAsset(key, now + 2_000_000_000L)
            return null
        }
        val textureId = uploadBitmap(bitmap)
        var blurredTextureId = 0
        return try {
            val blurred = ImageProcessingUtils.createSmoothBlurredCopy(bitmap)
            try {
                blurredTextureId = uploadBitmap(blurred)
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
            val previous = pageTextures.put(slot, texture)
            previous?.delete()
            missingAssets.remove(slot)
            texture
        } catch (error: Throwable) {
            glDeleteTextures(textureId, blurredTextureId)
            missingAssets[slot] = MissingAsset(key, now + 2_000_000_000L)
            throw error
        }
    }

    private fun uploadBitmap(source: Bitmap): Int {
        var bitmap = source
        if (bitmap.width > maxTextureSize || bitmap.height > maxTextureSize) {
            val scale = minOf(
                maxTextureSize.toFloat() / bitmap.width,
                maxTextureSize.toFloat() / bitmap.height
            )
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        try {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, ids, 0)
                throw IllegalStateException("Texture upload failed: 0x${error.toString(16)}")
            }
            return ids[0]
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun drawPage(
        page: PageTexture,
        left: Int,
        sourceLeft: Float,
        frame: GpuFrameRequest,
        alpha: Float
    ) {
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
            textureId = page.textureId,
            left = left.toFloat(),
            top = 0f,
            width = frame.width.toFloat(),
            height = frame.height.toFloat(),
            alpha = alpha,
            uvOffsetX = uvOffsetX,
            uvScaleX = uvScaleX
        )
    }

    private fun drawFullPage(
        page: PageTexture,
        left: Int,
        frame: GpuFrameRequest,
        alpha: Float
    ) {
        drawTextureModel(
            textureId = page.textureId,
            left = left.toFloat(),
            top = 0f,
            width = frame.width.toFloat(),
            height = frame.height.toFloat(),
            alpha = alpha
        )
    }

    private fun drawTexture(textureId: Int, frame: GpuFrameRequest, scale: Float, alpha: Float) {
        val width = frame.width * scale
        val height = frame.height * scale
        drawTextureModel(
            textureId,
            (frame.width - width) / 2f,
            (frame.height - height) / 2f,
            width,
            height,
            alpha
        )
    }

    private fun drawTextureModel(
        textureId: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        alpha: Float,
        uvOffsetX: Float = 0f,
        uvScaleX: Float = 1f
    ) {
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.translateM(modelMatrix, 0, left, top, 0f)
        GlMatrix.scaleM(modelMatrix, 0, width, height, 1f)
        drawTexturedQuad(textureId, uvOffsetX, 0f, uvScaleX, 1f, alpha)
    }

    private fun drawTexturedQuad(
        textureId: Int,
        uvOffsetX: Float,
        uvOffsetY: Float,
        uvScaleX: Float,
        uvScaleY: Float,
        alpha: Float,
        red: Float = 1f,
        green: Float = 1f,
        blue: Float = 1f
    ) {
        GlMatrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        GLES20.glUseProgram(textureProgram)
        bindQuad(textureProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(textureProgram, "uMvp"),
            1,
            false,
            mvpMatrix,
            0
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(textureProgram, "uUvOffset"),
            uvOffsetX,
            uvOffsetY
        )
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(textureProgram, "uUvScale"),
            uvScaleX,
            uvScaleY
        )
        GLES20.glUniform4f(
            GLES20.glGetUniformLocation(textureProgram, "uColor"),
            red,
            green,
            blue,
            quantizedAlpha(alpha)
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
        drawTexturedQuad(whiteTexture, 0f, 0f, 1f, 1f, alpha, red, green, blue)
    }

    private fun drawMotionBlur(frame: GpuFrameRequest) {
        val radius = ceil(frame.motionBlurRadius.toFloat() * motionBlurWidth / frame.width)
            .toInt()
            .coerceIn(1, MAX_BLUR_RADIUS)
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.scaleM(modelMatrix, 0, frame.width.toFloat(), frame.height.toFloat(), 1f)
        GlMatrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(blurProgram)
        bindQuad(blurProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBlurTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(blurProgram, "uMvp"),
            1,
            false,
            mvpMatrix,
            0
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uRadius"), radius)
        val sampleStep = if (radius < MAX_BLUR_TAPS) {
            1f / motionBlurWidth
        } else {
            radius.toFloat() / (MAX_BLUR_TAPS - 1).toFloat() / motionBlurWidth
        }
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(blurProgram, "uSampleStep"),
            sampleStep
        )
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(blurProgram, "uDirection"),
            if (frame.motionBlurDirection > 0) -1f else 1f
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun ensureMotionBlurTarget(screenWidth: Int, screenHeight: Int) {
        val width = (screenWidth / 2).coerceAtLeast(1)
        val height = (screenHeight / 2).coerceAtLeast(1)
        if (motionBlurTexture != 0 && motionBlurWidth == width && motionBlurHeight == height) return
        releaseMotionBlurTarget()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        motionBlurTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionBlurTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glGenFramebuffers(1, ids, 0)
        motionBlurFramebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionBlurFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            motionBlurTexture,
            0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("Motion blur framebuffer is incomplete")
        }
        motionBlurWidth = width
        motionBlurHeight = height
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawDebugIndicators(frame: GpuFrameRequest) {
        val showsOffsetOverlay = frame.state.showOffsetDebugOverlay
        val showsFrameIndicator = frame.state.showFrameDebugIndicator && frame.isVisible
        if (!showsOffsetOverlay && !showsFrameIndicator) return
        ensureDebugTextures(frame.density)
        if (showsOffsetOverlay) drawOffsetDebugOverlay(frame)
        if (frame.state.showFrameDebugIndicator && frame.isVisible) {
            val density = frame.density
            val size = 28f * density
            val margin = 20f * density
            val left = frame.width - size - margin
            val top = margin
            val border = 2f * density
            drawRect(left, top, size, border, 1f, 1f, 1f, 1f)
            drawRect(left, top + size - border, size, border, 1f, 1f, 1f, 1f)
            drawRect(left, top + border, border, size - 2f * border, 1f, 1f, 1f, 1f)
            drawRect(
                left + size - border,
                top + border,
                border,
                size - 2f * border,
                1f,
                1f,
                1f,
                1f
            )
            drawRect(
                left + border,
                top + border,
                size - 2f * border,
                size - 2f * border,
                Color.red(frame.frameDebugColor) / 255f,
                Color.green(frame.frameDebugColor) / 255f,
                Color.blue(frame.frameDebugColor) / 255f,
                1f
            )
        }
    }

    private fun ensureDebugTextures(density: Float) {
        if (debugCardTexture != 0 && debugTextureDensity == density) return
        if (debugCardTexture != 0) glDeleteTextures(debugCardTexture, debugCircleTexture)
        debugTextureDensity = density
        val width = ceil(240f * density).toInt().coerceAtLeast(1)
        val height = ceil(82f * density).toInt().coerceAtLeast(1)
        val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 22, 28, 40)
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12f * density, 12f * density, background)
        val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = 11f * density
        }
        labels.textAlign = Paint.Align.CENTER
        canvas.drawText("Launcher offset received", width / 2f, 19f * density, labels)
        labels.textAlign = Paint.Align.LEFT
        canvas.drawText("X", 16f * density, 45f * density, labels)
        canvas.drawText("Y", 16f * density, 68f * density, labels)
        debugCardTexture = uploadBitmap(card)
        card.recycle()

        val circle = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(circle).drawCircle(32f, 32f, 31f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        debugCircleTexture = uploadBitmap(circle)
        circle.recycle()
    }

    private fun drawOffsetDebugOverlay(frame: GpuFrameRequest) {
        val density = frame.density
        val cardWidth = 240f * density
        val cardHeight = 82f * density
        val left = (frame.width - cardWidth) / 2f
        val top = 20f * density
        drawTextureModel(debugCardTexture, left, top, cardWidth, cardHeight, 1f)
        val trackStart = left + 42f * density
        val trackEnd = left + cardWidth - 16f * density
        val trackWidth = trackEnd - trackStart
        val lineHeight = 3f * density
        val xY = top + 41f * density
        val yY = top + 64f * density
        drawRect(trackStart, xY - lineHeight / 2f, trackWidth, lineHeight, 210f / 255f, 220f / 255f, 235f / 255f, 150f / 255f)
        drawRect(trackStart, yY - lineHeight / 2f, trackWidth, lineHeight, 210f / 255f, 220f / 255f, 235f / 255f, 150f / 255f)
        frame.receivedXOffset?.let { offset ->
            drawDebugDot(trackStart + trackWidth * offset.coerceIn(0f, 1f), xY, 5f * density, 80f / 255f, 220f / 255f, 1f)
        }
        frame.receivedYOffset?.let { offset ->
            drawDebugDot(trackStart + trackWidth * offset.coerceIn(0f, 1f), yY, 5f * density, 1f, 180f / 255f, 0f)
        }
    }

    private fun drawDebugDot(
        centerX: Float,
        centerY: Float,
        radius: Float,
        red: Float,
        green: Float,
        blue: Float
    ) {
        GlMatrix.setIdentityM(modelMatrix, 0)
        GlMatrix.translateM(modelMatrix, 0, centerX - radius, centerY - radius, 0f)
        GlMatrix.scaleM(modelMatrix, 0, radius * 2f, radius * 2f, 1f)
        drawTexturedQuad(debugCircleTexture, 0f, 0f, 1f, 1f, 1f, red, green, blue)
    }

    private fun bindQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(0)
    }

    private fun createSolidTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        pixel.put(byteArrayOf(-1, -1, -1, -1)).position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            1,
            1,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixel
        )
        return ids[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Shader link failed: $message")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compilation failed: $message")
        }
        return shader
    }

    private fun quantizedAlpha(alpha: Float): Float =
        (alpha.coerceIn(0f, 1f) * 255f).toInt() / 255f

    private fun glDeleteTextures(first: Int, second: Int) {
        val ids = intArrayOf(first, second).filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
    }

    private fun releasePageTextures() {
        pageTextures.values.forEach { it.delete() }
        pageTextures.clear()
        missingAssets.clear()
    }

    private fun releaseMotionBlurTarget() {
        if (motionBlurFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(motionBlurFramebuffer), 0)
        }
        if (motionBlurTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(motionBlurTexture), 0)
        }
        motionBlurFramebuffer = 0
        motionBlurTexture = 0
        motionBlurWidth = 0
        motionBlurHeight = 0
    }

    private fun destroyGl() {
        val display = eglDisplay
        if (display != null && eglContext != null) {
            EGL14.eglMakeCurrent(
                display,
                eglSurface ?: EGL14.EGL_NO_SURFACE,
                eglSurface ?: EGL14.EGL_NO_SURFACE,
                eglContext
            )
            releasePageTextures()
            releaseMotionBlurTarget()
            glDeleteTextures(debugCardTexture, debugCircleTexture)
            if (whiteTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(whiteTexture), 0)
            if (textureProgram != 0) GLES20.glDeleteProgram(textureProgram)
            if (blurProgram != 0) GLES20.glDeleteProgram(blurProgram)
        }
        debugCardTexture = 0
        debugCircleTexture = 0
        debugTextureDensity = 0f
        whiteTexture = 0
        textureProgram = 0
        blurProgram = 0
        if (display != null) {
            eglSurface?.let { EGL14.eglDestroySurface(display, it) }
            eglContext?.let { EGL14.eglDestroyContext(display, it) }
            EGL14.eglTerminate(display)
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    companion object {
        private const val TAG = "GpuWallpaperRenderer"
        private const val MAX_BLUR_RADIUS = 511
        private const val MAX_BLUR_TAPS = 32
        private const val TEXTURE_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying highp vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        private const val TEXTURE_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uUvOffset;
            uniform vec2 uUvScale;
            uniform vec4 uColor;
            varying highp vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord * uUvScale + uUvOffset) * uColor;
            }
        """
        private const val BLUR_FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform int uRadius;
            uniform float uSampleStep;
            uniform float uDirection;
            varying highp vec2 vTexCoord;
            void main() {
                vec2 uv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
                vec4 sum = vec4(0.0);
                for (int i = 0; i < 32; i++) {
                    if (i > uRadius) break;
                    float offset = float(i) * uSampleStep * uDirection;
                    vec2 sampleUv = clamp(uv + vec2(offset, 0.0), vec2(0.0), vec2(1.0));
                    sum += texture2D(uTexture, sampleUv);
                }
                gl_FragColor = sum / min(float(uRadius) + 1.0, 32.0);
            }
        """
    }
}
