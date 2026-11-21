package com.example.render

import com.example.data.model.EdgeOverScrollCurve
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object EdgeOverScrollScaling {

    private const val SCREEN_ANCHOR_MAX_UNITS = 0.72f
    private const val DIMINISHING_EXPONENT = 1.85f
    private const val LOG_BASE_REACH_UNITS = 0.50f
    private const val LOG_INTENSITY_FACTOR = 3.5f
    private const val POWER_FLING_BASE_SLOPE = 0.30f
    private const val POWER_FLING_EXPONENT = 2.2f

    fun computeSourceLeftPx(
        intensity: Float,
        leftTravelPx: Int,
        rightTravelPx: Int,
        screenWidth: Int,
        curve: EdgeOverScrollCurve
    ): Float {
        val clampedIntensity = intensity.coerceIn(-1f, 1f)
        val safeLeft = leftTravelPx.coerceAtLeast(0)
        val safeRight = rightTravelPx.coerceAtLeast(0)
        return when {
            clampedIntensity < 0f && safeLeft > 0 -> {
                val travelPx = computeTravelPx(
                    absIntensity = -clampedIntensity,
                    availableTravelPx = safeLeft,
                    screenWidth = screenWidth,
                    curve = curve
                )
                (safeLeft - travelPx).coerceIn(0f, safeLeft.toFloat())
            }
            clampedIntensity > 0f && safeRight > 0 -> {
                val travelPx = computeTravelPx(
                    absIntensity = clampedIntensity,
                    availableTravelPx = safeRight,
                    screenWidth = screenWidth,
                    curve = curve
                )
                (safeLeft + travelPx).coerceIn(safeLeft.toFloat(), (safeLeft + safeRight).toFloat())
            }
            else -> safeLeft.toFloat()
        }
    }

    fun computeTravelPx(
        absIntensity: Float,
        availableTravelPx: Int,
        screenWidth: Int,
        curve: EdgeOverScrollCurve
    ): Float {
        val u = absIntensity.coerceIn(0f, 1f)
        if (u <= 0f || availableTravelPx <= 0 || screenWidth <= 0) return 0f
        val reachUnits = availableTravelPx.toFloat() / screenWidth.toFloat()
        val scaledUnits = when (curve) {
            EdgeOverScrollCurve.DIMINISHING_SCREEN_ANCHORED -> {
                val maxUnits = minOf(SCREEN_ANCHOR_MAX_UNITS, reachUnits)
                val eased = 1f - (1f - u).pow(DIMINISHING_EXPONENT)
                maxUnits * eased
            }
            EdgeOverScrollCurve.LOGARITHMIC_BOOST -> {
                val maxUnits = if (reachUnits <= LOG_BASE_REACH_UNITS) {
                    val ratio = reachUnits / LOG_BASE_REACH_UNITS
                    reachUnits * (0.85f + 0.15f * ratio)
                } else {
                    (LOG_BASE_REACH_UNITS + 0.45f * ln(1f + (reachUnits - LOG_BASE_REACH_UNITS) * 2.2f))
                        .coerceAtMost(reachUnits)
                }
                val eased = ln(1f + u * LOG_INTENSITY_FACTOR) / ln(1f + LOG_INTENSITY_FACTOR)
                maxUnits * eased
            }
            EdgeOverScrollCurve.POWER_FLING -> {
                val eased = POWER_FLING_BASE_SLOPE * u +
                    (1f - POWER_FLING_BASE_SLOPE) * u.pow(POWER_FLING_EXPONENT)
                reachUnits * eased
            }
        }
        return (scaledUnits * screenWidth).coerceIn(0f, availableTravelPx.toFloat())
    }
}
