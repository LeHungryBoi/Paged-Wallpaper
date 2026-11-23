package com.example.render

import kotlin.math.pow

data class EntryRenderState(
    val isActive: Boolean = false,
    val progress: Float = 1f,
    val scale: Float = 1f,
    val blackFadeAlpha: Float = 0f,
    val blurMix: Float = 0f
)

class EntryTransitionController {

    var isActive: Boolean = false
        private set

    var linearProgress: Float = 1f
        private set

    private var elapsedNanos: Long = 0L
    private var durationNanos: Long = DEFAULT_DURATION_MS * 1_000_000L

    fun trigger(durationMs: Int = DEFAULT_DURATION_MS) {
        durationNanos = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS) * 1_000_000L
        elapsedNanos = 0L
        linearProgress = 0f
        isActive = true
    }

    fun step(deltaNanos: Long) {
        if (!isActive) return
        elapsedNanos += deltaNanos.coerceAtLeast(1L)
        linearProgress = (elapsedNanos.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
        if (linearProgress >= 1f) {
            linearProgress = 1f
            isActive = false
        }
    }

    fun reset() {
        isActive = false
        elapsedNanos = 0L
        linearProgress = 1f
    }

    fun evaluate(
        enabled: Boolean,
        zoomEnabled: Boolean,
        zoomStartScale: Float,
        blackFadeEnabled: Boolean,
        blackFadeMaxAlpha: Float,
        blurEnabled: Boolean,
        blurMaxMix: Float
    ): EntryRenderState {
        if (!enabled || !isActive) {
            return EntryRenderState(
                isActive = false,
                progress = 1f,
                scale = 1f,
                blackFadeAlpha = 0f,
                blurMix = 0f
            )
        }

        val p = linearProgress.coerceIn(0f, 1f)
        val zoomEase = 1f - (1f - p).pow(ZOOM_EASE_OUT_POWER)
        val clampedStartScale = zoomStartScale.coerceIn(MIN_START_SCALE, MAX_START_SCALE)
        val scale = if (zoomEnabled) {
            clampedStartScale + (1f - clampedStartScale) * zoomEase
        } else {
            1f
        }

        val fadeT = (p / FADE_FINISH_FRACTION).coerceIn(0f, 1f)
        val fadeEase = 1f - (1f - fadeT).pow(FADE_EASE_OUT_POWER)
        val blackAlpha = if (blackFadeEnabled) {
            blackFadeMaxAlpha.coerceIn(0f, 1f) * (1f - fadeEase)
        } else {
            0f
        }

        val blurT = (p / BLUR_FINISH_FRACTION).coerceIn(0f, 1f)
        val blurEase = 1f - (1f - blurT).pow(FADE_EASE_OUT_POWER)
        val blurMix = if (blurEnabled) {
            blurMaxMix.coerceIn(0f, 1f) * (1f - blurEase)
        } else {
            0f
        }

        return EntryRenderState(
            isActive = true,
            progress = p,
            scale = scale,
            blackFadeAlpha = blackAlpha,
            blurMix = blurMix
        )
    }

    companion object {
        const val DEFAULT_DURATION_MS = 420
        const val MIN_DURATION_MS = 150
        const val MAX_DURATION_MS = 1200
        const val MIN_START_SCALE = 0.65f
        const val MAX_START_SCALE = 0.98f
        private const val ZOOM_EASE_OUT_POWER = 3.5f
        private const val FADE_EASE_OUT_POWER = 2.4f
        private const val FADE_FINISH_FRACTION = 0.78f
        private const val BLUR_FINISH_FRACTION = 0.85f
    }
}
