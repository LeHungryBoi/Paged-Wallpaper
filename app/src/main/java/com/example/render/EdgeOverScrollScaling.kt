package com.example.render

import kotlin.math.pow

object EdgeOverScrollScaling {

    private const val SCREEN_ANCHOR_MAX_UNITS = 0.72f
    private const val DIMINISHING_EXPONENT = 1.85f

    fun computeSourceLeftPx(
        intensity: Float,
        leftTravelPx: Int,
        rightTravelPx: Int,
        screenWidth: Int
    ): Float {
        val clampedIntensity = intensity.coerceIn(-1f, 1f)
        val safeLeft = leftTravelPx.coerceAtLeast(0)
        val safeRight = rightTravelPx.coerceAtLeast(0)
        return when {
            clampedIntensity < 0f && safeLeft > 0 -> {
                val travelPx = computeTravelPx(
                    absIntensity = -clampedIntensity,
                    availableTravelPx = safeLeft,
                    screenWidth = screenWidth
                )
                (safeLeft - travelPx).coerceIn(0f, safeLeft.toFloat())
            }
            clampedIntensity > 0f && safeRight > 0 -> {
                val travelPx = computeTravelPx(
                    absIntensity = clampedIntensity,
                    availableTravelPx = safeRight,
                    screenWidth = screenWidth
                )
                (safeLeft + travelPx).coerceIn(safeLeft.toFloat(), (safeLeft + safeRight).toFloat())
            }
            else -> safeLeft.toFloat()
        }
    }

    fun computeTravelPx(
        absIntensity: Float,
        availableTravelPx: Int,
        screenWidth: Int
    ): Float {
        val u = absIntensity.coerceIn(0f, 1f)
        if (u <= 0f || availableTravelPx <= 0 || screenWidth <= 0) return 0f
        val reachUnits = availableTravelPx.toFloat() / screenWidth.toFloat()
        val maxUnits = minOf(SCREEN_ANCHOR_MAX_UNITS, reachUnits)
        val eased = 1f - (1f - u).pow(DIMINISHING_EXPONENT)
        val scaledUnits = maxUnits * eased
        return (scaledUnits * screenWidth).coerceIn(0f, availableTravelPx.toFloat())
    }
}
