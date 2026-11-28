package com.example.render

import android.view.Surface
import com.example.data.model.WallpaperAppState

data class GpuFrameRequest(
    val state: WallpaperAppState,
    val width: Int,
    val height: Int,
    val pageA: Int,
    val pageB: Int,
    val progress: Float,
    val pagePosition: Float,
    val continuousPagePosition: Float,
    val frameId: Long,
    val frameTimeNanos: Long,
    val fromOffsetUpdate: Boolean,
    val isVisible: Boolean,
    val swipeForward: Boolean,
    val entryScale: Float = 1f,
    val entryBlackFadeAlpha: Float = 0f,
    val entryBlurMix: Float = 0f,
    val motionBlurRadius: Int,
    val motionBlurDirection: Int,
    val darkenMix: Float = 0f,
    val receivedXOffset: Float?,
    val receivedYOffset: Float?,
    val signalOverlayLines: List<String> = emptyList(),
    val density: Float,
    val frameDebugColor: Int,
    val edgeOverScrollFraction: Float = 0f
)

interface WallpaperRenderBackend {
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int)
    fun onSurfaceDestroyed()
    fun render(
        request: GpuFrameRequest,
        onSubmitted: (GpuFrameRequest) -> Unit,
        onFailure: () -> Unit,
        onRetryNeeded: () -> Unit = {}
    )
    fun release()
}
