package com.example.render

object OffsetEasing {
    fun map(progress: Float, linearZonePercent: Int, strengthPercent: Int): Float {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val linearZone = linearZonePercent.coerceIn(0, 100) / 100f
        val strength = strengthPercent.coerceIn(0, 100) / 100f
        val edgeZone = (1f - linearZone) / 2f
        if (edgeZone <= 0f || strength <= 0f) return clampedProgress

        if (clampedProgress < edgeZone) {
            val localProgress = clampedProgress / edgeZone
            return edgeZone * blendWithEase(localProgress, strength)
        }
        if (clampedProgress > 1f - edgeZone) {
            val localProgress = (1f - clampedProgress) / edgeZone
            return 1f - edgeZone * blendWithEase(localProgress, strength)
        }
        return clampedProgress
    }

    private fun blendWithEase(progress: Float, strength: Float): Float {
        val eased = 2f * progress * progress - progress * progress * progress
        return progress * (1f - strength) + eased * strength
    }
}
