package com.example.render

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.data.repository.WallpaperRepository
import com.example.diagnostics.WallpaperDiagnosticsRecorder
import kotlin.math.ceil

internal class VulkanWallpaperRenderer(
    private val repository: WallpaperRepository
) : WallpaperRenderBackend {

    private data class PendingFrame(
        val request: GpuFrameRequest,
        val onSubmitted: (GpuFrameRequest) -> Unit,
        val onFailure: () -> Unit,
        val onRetryNeeded: () -> Unit
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("PagedWallpaperVK").apply { start() }
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

    private var nativeHandle = 0L
    private var whiteTextureId = 0
    private var maxTextureSize = 4096
    private var boundSurface: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0
    private var consecutiveFailures = 0
    private var lastNativeTraceRevision = 0L

    private val drawSink = object : VulkanDrawSink {
        override val maxTextureSize: Int
            get() = this@VulkanWallpaperRenderer.maxTextureSize

        override fun uploadBitmap(bitmap: Bitmap): Int {
            val handle = nativeHandle
            if (handle == 0L || bitmap.isRecycled) return 0
            val rgbaBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return 0
            }
            return try {
                VulkanNativeBridge.nativeUploadBitmap(handle, rgbaBitmap)
            } finally {
                if (rgbaBitmap !== bitmap) rgbaBitmap.recycle()
            }
        }

        override fun deleteTexture(textureId: Int) {
            val handle = nativeHandle
            if (handle != 0L && textureId != 0) {
                VulkanNativeBridge.nativeDeleteTexture(handle, textureId)
            }
        }

        override fun drawTexturedQuad(
            textureId: Int,
            mvp: FloatArray,
            uvOffsetX: Float,
            uvOffsetY: Float,
            uvScaleX: Float,
            uvScaleY: Float,
            alpha: Float,
            red: Float,
            green: Float,
            blue: Float,
            darkTextureId: Int,
            darkMix: Float
        ) {
            val handle = nativeHandle
            if (handle == 0L) return
            VulkanNativeBridge.nativeDrawQuad(
                handle = handle,
                textureId = if (textureId != 0) textureId else whiteTextureId,
                darkTextureId = darkTextureId,
                darkMix = darkMix,
                mvpArray = mvp,
                uvOffsetX = uvOffsetX,
                uvOffsetY = uvOffsetY,
                uvScaleX = uvScaleX,
                uvScaleY = uvScaleY,
                r = red,
                g = green,
                b = blue,
                a = alpha
            )
        }

        override fun drawSolidRect(
            mvp: FloatArray,
            red: Float,
            green: Float,
            blue: Float,
            alpha: Float
        ) {
            val handle = nativeHandle
            if (handle == 0L) return
            VulkanNativeBridge.nativeDrawQuad(
                handle = handle,
                textureId = whiteTextureId,
                darkTextureId = 0,
                darkMix = 0f,
                mvpArray = mvp,
                uvOffsetX = 0f,
                uvOffsetY = 0f,
                uvScaleX = 1f,
                uvScaleY = 1f,
                r = red,
                g = green,
                b = blue,
                a = alpha
            )
        }
    }

    private val composer = WallpaperSceneComposer(repository, drawSink)

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        val sizeChanged = surfaceWidth != width || surfaceHeight != height
        windowSurface = surface
        surfaceWidth = width
        surfaceHeight = height
        renderHandler.post {
            if (released) return@post
            if (sizeChanged) {
                composer.onDimensionsChanged()
            }
            if (nativeHandle != 0L && surface.isValid) {
                if (VulkanNativeBridge.nativeOnSurfaceChanged(nativeHandle, surface, width, height)) {
                    boundSurface = surface
                    boundWidth = width
                    boundHeight = height
                } else {
                    boundSurface = null
                }
            }
        }
    }

    override fun onSurfaceDestroyed() {
        windowSurface = null
        synchronized(frameLock) { pendingFrame = null }
        renderHandler.post {
            boundSurface = null
            boundWidth = 0
            boundHeight = 0
            if (nativeHandle != 0L) {
                VulkanNativeBridge.nativeOnSurfaceDestroyed(nativeHandle)
            }
        }
    }

    override fun render(
        request: GpuFrameRequest,
        onSubmitted: (GpuFrameRequest) -> Unit,
        onFailure: () -> Unit,
        onRetryNeeded: () -> Unit
    ) {
        if (released) return
        val shouldSchedule = synchronized(frameLock) {
            pendingFrame = PendingFrame(request, onSubmitted, onFailure, onRetryNeeded)
            if (renderScheduled) {
                false
            } else {
                renderScheduled = true
                true
            }
        }
        if (shouldSchedule) renderHandler.post { drainFrame() }
    }

    override fun release() {
        if (released) return
        released = true
        windowSurface = null
        synchronized(frameLock) { pendingFrame = null }
        renderHandler.post {
            destroyVulkan()
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
        val req = frame.request
        WallpaperDiagnosticsRecorder.recordFrameRenderStage(req.frameId, "render_thread_start")
        val surface = windowSurface ?: return
        if (!surface.isValid) return

        try {
            if (!ensureVulkanReady(surface, req.width, req.height)) {
                Log.w(TAG, "Vulkan unavailable on this device/surface; falling back to OpenGL ES")
                destroyVulkan()
                mainHandler.post {
                    if (!released && windowSurface != null) frame.onFailure()
                }
                return
            }

            composer.setupOrtho(req.width, req.height)
            val useMotionBlur = req.motionBlurRadius > 0 && req.motionBlurDirection != 0
            val mbWidth = (req.width / 2).coerceAtLeast(1)
            val mbHeight = (req.height / 2).coerceAtLeast(1)

            WallpaperDiagnosticsRecorder.recordFrameRenderStage(req.frameId, "vk_begin_start")
            var beginStatus = VulkanNativeBridge.nativeBeginFrame(
                nativeHandle,
                req.frameId,
                req.frameTimeNanos,
                useMotionBlur,
                mbWidth,
                mbHeight,
                CLEAR_R,
                CLEAR_G,
                CLEAR_B
            )
            if (beginStatus == 1) {
                if (!VulkanNativeBridge.nativeOnSurfaceChanged(nativeHandle, surface, req.width, req.height)) {
                    throw IllegalStateException("Vulkan swapchain rebind failed after OUT_OF_DATE")
                }
                boundSurface = surface
                boundWidth = req.width
                boundHeight = req.height
                beginStatus = VulkanNativeBridge.nativeBeginFrame(
                    nativeHandle,
                    req.frameId,
                    req.frameTimeNanos,
                    useMotionBlur,
                    mbWidth,
                    mbHeight,
                    CLEAR_R,
                    CLEAR_G,
                    CLEAR_B
                )
            }
            if (beginStatus != 0) {
                throw IllegalStateException("Vulkan nativeBeginFrame failed with status=$beginStatus")
            }
            publishNativeFrameTrace()
            WallpaperDiagnosticsRecorder.recordFrameRenderStage(req.frameId, "vk_begin_complete")

            composer.renderWallpaper(req)
            WallpaperDiagnosticsRecorder.recordFrameRenderStage(req.frameId, "scene_recorded")

            if (useMotionBlur) {
                VulkanNativeBridge.nativeBeginMainPass(nativeHandle, CLEAR_R, CLEAR_G, CLEAR_B)
                val radius = ceil(req.motionBlurRadius.toFloat() * mbWidth / req.width)
                    .toInt()
                    .coerceIn(1, MAX_BLUR_RADIUS)
                val sampleStep = if (radius < MAX_BLUR_TAPS) {
                    1f / mbWidth
                } else {
                    radius.toFloat() / (MAX_BLUR_TAPS - 1).toFloat() / mbWidth
                }
                val direction = if (req.motionBlurDirection > 0) -1f else 1f
                VulkanNativeBridge.nativeDrawMotionBlurPass(
                    nativeHandle,
                    composer.computeFullscreenMvp(req.width, req.height),
                    radius,
                    sampleStep,
                    direction
                )
            }

            composer.renderOverlayAndDebug(req)

            val endStatus = VulkanNativeBridge.nativeEndFrame(nativeHandle)
            if (endStatus < 0) {
                throw IllegalStateException("Vulkan nativeEndFrame failed with status=$endStatus")
            }
            WallpaperDiagnosticsRecorder.recordFrameRenderStage(req.frameId, "vk_end_complete")
            publishNativeFrameTrace()
            scheduleNativeTraceDrain()

            consecutiveFailures = 0
            val needsAssetRetry = composer.missingConfiguredTextureInFrame
            mainHandler.post {
                if (!released) frame.onSubmitted(req)
            }
            if (needsAssetRetry) {
                mainHandler.postDelayed({
                    if (!released && windowSurface?.isValid == true) {
                        frame.onRetryNeeded()
                    }
                }, ASSET_RETRY_DELAY_MS)
            }
        } catch (error: Throwable) {
            WallpaperDiagnosticsRecorder.recordFrameFailure(
                req.frameId,
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            Log.e(TAG, "Vulkan wallpaper rendering error; resetting Vulkan state", error)
            destroyVulkan()
            if (!surface.isValid || released) return
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                mainHandler.post {
                    if (!released && windowSurface != null) frame.onFailure()
                }
            } else {
                mainHandler.postDelayed({
                    if (!released && windowSurface?.isValid == true) {
                        frame.onRetryNeeded()
                    }
                }, TRANSIENT_RETRY_DELAY_MS)
            }
        }
    }

    private fun publishNativeFrameTrace() {
        val handle = nativeHandle
        if (handle == 0L) return
        val updates = VulkanNativeBridge.nativeGetFrameTrace(handle, lastNativeTraceRevision)
        if (updates.isEmpty()) return
        WallpaperDiagnosticsRecorder.recordNativeFrameTrace(updates)
        var index = 0
        while (index + TRACE_FIELDS_PER_SAMPLE <= updates.size) {
            lastNativeTraceRevision = maxOf(lastNativeTraceRevision, updates[index])
            index += TRACE_FIELDS_PER_SAMPLE
        }
    }

    private fun scheduleNativeTraceDrain() {
        // Let the GPU fence and display compositor finish naturally. The native
        // getter only collects already-completed results; it never waits on GPU.
        TRACE_DRAIN_DELAYS_MS.forEach { delay ->
            renderHandler.postDelayed({
                if (!released) publishNativeFrameTrace()
            }, delay)
        }
    }

    private fun ensureVulkanReady(surface: Surface, width: Int, height: Int): Boolean {
        if (!VulkanNativeBridge.isNativeLoaded) return false
        if (nativeHandle == 0L) {
            val handle = VulkanNativeBridge.nativeCreate()
            if (handle == 0L) return false
            nativeHandle = handle
            lastNativeTraceRevision = 0L
            maxTextureSize = VulkanNativeBridge.nativeGetMaxTextureSize(handle).coerceAtLeast(2048)
            whiteTextureId = VulkanNativeBridge.nativeGetWhiteTextureId(handle)
        }
        if (surface !== boundSurface || boundWidth != width || boundHeight != height) {
            if (!VulkanNativeBridge.nativeOnSurfaceChanged(nativeHandle, surface, width, height)) {
                return false
            }
            boundSurface = surface
            boundWidth = width
            boundHeight = height
        }
        return true
    }

    private fun destroyVulkan() {
        composer.releaseAllTextures()
        boundSurface = null
        boundWidth = 0
        boundHeight = 0
        whiteTextureId = 0
        if (nativeHandle != 0L) {
            VulkanNativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        lastNativeTraceRevision = 0L
    }

    companion object {
        private const val TAG = "VulkanWallpaperRenderer"
        private const val CLEAR_R = 18f / 255f
        private const val CLEAR_G = 22f / 255f
        private const val CLEAR_B = 34f / 255f
        private const val MAX_BLUR_RADIUS = 511
        private const val MAX_BLUR_TAPS = 32
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private val TRACE_DRAIN_DELAYS_MS = longArrayOf(24L, 64L, 128L)
        private const val TRACE_FIELDS_PER_SAMPLE = 18
        private const val ASSET_RETRY_DELAY_MS = 110L
        private const val TRANSIENT_RETRY_DELAY_MS = 16L
    }
}
