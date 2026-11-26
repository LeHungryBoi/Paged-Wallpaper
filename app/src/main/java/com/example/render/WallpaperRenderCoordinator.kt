package com.example.render

import android.util.Log
import android.view.Surface
import com.example.data.repository.WallpaperRepository

/**
 * Coordinates the active GPU rendering backend, preferring native NDK Vulkan on supported
 * devices and automatically falling back to the retained-context OpenGL ES 2.0 renderer if
 * Vulkan device/swapchain initialization fails.
 */
internal class WallpaperRenderCoordinator(
    private val repository: WallpaperRepository,
    private val vulkanFactory: () -> WallpaperRenderBackend = { VulkanWallpaperRenderer(repository) },
    private val glFactory: () -> WallpaperRenderBackend = { GpuWallpaperRenderer(repository) }
) : WallpaperRenderBackend {

    private var activeBackend: WallpaperRenderBackend? = null
    private var usingVulkan = VulkanNativeBridge.isNativeLoaded
    private var glFailed = false
    private var released = false

    private var lastSurface: Surface? = null
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        activeBackend = if (usingVulkan) vulkanFactory() else glFactory()
    }

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        if (released) return
        lastSurface = surface
        lastWidth = width
        lastHeight = height
        activeBackend?.onSurfaceChanged(surface, width, height)
    }

    override fun onSurfaceDestroyed() {
        lastSurface = null
        activeBackend?.onSurfaceDestroyed()
    }

    override fun render(
        request: GpuFrameRequest,
        onSubmitted: (GpuFrameRequest) -> Unit,
        onFailure: () -> Unit,
        onRetryNeeded: () -> Unit
    ) {
        if (released) return
        val backend = activeBackend
        if (backend == null) {
            onFailure()
            return
        }
        backend.render(
            request = request,
            onSubmitted = onSubmitted,
            onFailure = {
                if (released) return@render
                if (usingVulkan && !glFailed) {
                    Log.w(TAG, "Switching from Vulkan backend to OpenGL ES 2.0 fallback")
                    usingVulkan = false
                    activeBackend?.release()
                    val fallback = glFactory()
                    activeBackend = fallback
                    val surface = lastSurface
                    if (surface != null && surface.isValid && lastWidth > 0 && lastHeight > 0) {
                        fallback.onSurfaceChanged(surface, lastWidth, lastHeight)
                    }
                    fallback.render(
                        request = request,
                        onSubmitted = onSubmitted,
                        onFailure = {
                            glFailed = true
                            onFailure()
                        },
                        onRetryNeeded = onRetryNeeded
                    )
                } else {
                    glFailed = true
                    onFailure()
                }
            },
            onRetryNeeded = onRetryNeeded
        )
    }

    override fun release() {
        if (released) return
        released = true
        lastSurface = null
        activeBackend?.release()
        activeBackend = null
    }

    companion object {
        private const val TAG = "WallpaperRenderCoord"
    }
}
