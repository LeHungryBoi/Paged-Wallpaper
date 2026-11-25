package com.example.util

import com.example.data.model.CropFillMode

data class ParallaxTravel(
    val leftTravelPx: Float,
    val rightTravelPx: Float
)

object ParallaxBounds {
    fun calculateTravel(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Float,
        targetHeight: Float,
        scale: Float,
        panX: Float,
        cropFillMode: CropFillMode,
        parallaxAmount: Float,
        symmetricParallax: Boolean = false
    ): ParallaxTravel {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0f || targetHeight <= 0f) {
            return ParallaxTravel(0f, 0f)
        }

        val clampedScale = scale.coerceIn(1f, 3f)
        val clampedPanX = panX.coerceIn(-1f, 1f)
        val travelAmount = parallaxAmount.coerceIn(0f, 1f)

        val totalHorizontalSlackPx: Float
        val availableLeftTravelPx: Float
        val availableRightTravelPx: Float

        if (cropFillMode == CropFillMode.CROP) {
            val sourceRatio = sourceWidth.toFloat() / sourceHeight
            val targetRatio = targetWidth / targetHeight
            val baseCropWidth = if (sourceRatio > targetRatio) {
                sourceHeight * targetRatio
            } else {
                sourceWidth.toFloat()
            }
            val cropWidth = baseCropWidth / clampedScale
            val maxPanOffset = ((sourceWidth - cropWidth) / 2f).coerceAtLeast(0f)
            val centerX = sourceWidth / 2f - clampedPanX * maxPanOffset
            val left = (centerX - cropWidth / 2f)
                .coerceIn(0f, (sourceWidth - cropWidth).coerceAtLeast(0f))

            totalHorizontalSlackPx =
                ((sourceWidth - cropWidth) * targetWidth / cropWidth).coerceAtLeast(0f)
            availableLeftTravelPx = (left * targetWidth / cropWidth).coerceAtLeast(0f)
            availableRightTravelPx =
                ((sourceWidth - left - cropWidth) * targetWidth / cropWidth).coerceAtLeast(0f)
        } else {
            val fitScale = minOf(
                targetWidth / sourceWidth,
                targetHeight / sourceHeight
            ) * clampedScale
            val drawWidth = sourceWidth * fitScale
            val maxPanOffset = ((drawWidth - targetWidth) / 2f).coerceAtLeast(0f)
            val centerX = targetWidth / 2f + clampedPanX * maxPanOffset
            val imageLeft = centerX - drawWidth / 2f

            totalHorizontalSlackPx = (drawWidth - targetWidth).coerceAtLeast(0f)
            availableLeftTravelPx = (-imageLeft).coerceAtLeast(0f)
            availableRightTravelPx = (imageLeft + drawWidth - targetWidth).coerceAtLeast(0f)
        }

        val targetPerSidePx = (totalHorizontalSlackPx * 0.5f) * travelAmount
        return if (symmetricParallax) {
            val clampedSymmetricPx = minOf(
                targetPerSidePx,
                minOf(availableLeftTravelPx, availableRightTravelPx)
            ).coerceAtLeast(0f)
            ParallaxTravel(
                leftTravelPx = clampedSymmetricPx,
                rightTravelPx = clampedSymmetricPx
            )
        } else {
            ParallaxTravel(
                leftTravelPx = minOf(targetPerSidePx, availableLeftTravelPx).coerceAtLeast(0f),
                rightTravelPx = minOf(targetPerSidePx, availableRightTravelPx).coerceAtLeast(0f)
            )
        }
    }
}
