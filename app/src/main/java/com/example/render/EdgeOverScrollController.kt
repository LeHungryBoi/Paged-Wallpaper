package com.example.render

import android.view.MotionEvent
import com.example.data.model.CalibrationConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

class EdgeOverScrollController {

    enum class EdgeSide {
        NONE,
        LEFT,
        RIGHT
    }

    enum class SnapBackMode {
        FAST,
        SLOW
    }

    data class TouchResult(
        val handled: Boolean,
        val needsRedraw: Boolean
    )

    var isTouchActive: Boolean = false
        private set

    var isOverScrollLocked: Boolean = false
        private set

    var overScrollFraction: Float = 0f
        private set

    var lockedEdgeOffset: Float = 0f
        private set

    var snapBackMode: SnapBackMode = SnapBackMode.SLOW
        private set

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var trackedPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var isGestureTracking: Boolean = false
    private var gestureRejected: Boolean = false
    private var gestureOriginX: Float = 0f
    private var gestureOriginY: Float = 0f
    private var maxHorizontalTravelPx: Float = 0f
    private var hasCrossedTouchSlop: Boolean = false
    private var hasLeftSettledEdge: Boolean = false
    private var lockOriginSlopPx: Float = 0f
    private var lastLeftEdgeOriginX: Float? = null
    private var lastRightEdgeOriginX: Float? = null
    private var wasAtLeftEdgeOffset: Boolean = false
    private var wasAtRightEdgeOffset: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastX: Float = 0f
    private var lastEventTimeMs: Long = 0L
    private var edgeAtDown: EdgeSide = EdgeSide.NONE
    private var outwardVelocityPxPerSec: Float = 0f
    private var activeOutwardVelocityNorm: Float = 0f
    private var peakOutwardRawPx: Float = 0f
    private var hasReversedPastOrigin: Boolean = false
    private var releaseStartFraction: Float = 0f
    private var releaseElapsedNanos: Long = 0L

    fun hasActiveFraction(): Boolean = abs(overScrollFraction) > FRACTION_EPSILON

    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        touchSlop: Int,
        currentXOffset: Float,
        renderedXOffset: Float,
        calibration: CalibrationConfig,
        surfaceHeight: Int = 0
    ): TouchResult {
        val actionMasked = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = if (pointerIndex in 0 until event.pointerCount) {
            event.getPointerId(pointerIndex)
        } else {
            MotionEvent.INVALID_POINTER_ID
        }
        val lookupPointerId = when {
            activePointerId != MotionEvent.INVALID_POINTER_ID -> activePointerId
            trackedPointerId != MotionEvent.INVALID_POINTER_ID -> trackedPointerId
            else -> MotionEvent.INVALID_POINTER_ID
        }
        val activeIdx = if (lookupPointerId != MotionEvent.INVALID_POINTER_ID) {
            event.findPointerIndex(lookupPointerId)
        } else {
            -1
        }
        val x = when {
            actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount > 0 -> event.getX(0)
            activeIdx >= 0 -> event.getX(activeIdx)
            event.pointerCount > 0 -> event.getX(0)
            else -> 0f
        }
        val y = when {
            actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount > 0 -> event.getY(0)
            activeIdx >= 0 -> event.getY(activeIdx)
            event.pointerCount > 0 -> event.getY(0)
            else -> 0f
        }
        return onTouchSignal(
            actionMasked = actionMasked,
            pointerId = if (actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount > 0) {
                event.getPointerId(0)
            } else {
                pointerId
            },
            x = x,
            y = y,
            surfaceWidth = surfaceWidth,
            touchSlop = touchSlop,
            currentXOffset = currentXOffset,
            renderedXOffset = renderedXOffset,
            calibration = calibration,
            eventTimeMs = event.eventTime,
            surfaceHeight = surfaceHeight
        )
    }

    fun onTouchSignal(
        actionMasked: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        surfaceWidth: Int,
        touchSlop: Int,
        currentXOffset: Float,
        renderedXOffset: Float,
        calibration: CalibrationConfig,
        eventTimeMs: Long = 0L,
        surfaceHeight: Int = 0
    ): TouchResult {
        val safeWidth = surfaceWidth.coerceAtLeast(1)
        val slop = touchSlop.coerceAtLeast(1).toFloat()
        val dragSpan = overScrollDragDistancePx(safeWidth)

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (surfaceHeight > 0 &&
                    (y < surfaceHeight * TOP_GESTURE_EXCLUSION_FRACTION ||
                        y > surfaceHeight * BOTTOM_GESTURE_EXCLUSION_FRACTION)
                ) {
                    clearAllTouchState()
                    return TouchResult(handled = false, needsRedraw = false)
                }

                if (hasActiveFraction()) {
                    val existingEdge = if (overScrollFraction < 0f) EdgeSide.LEFT else EdgeSide.RIGHT
                    val anchors = resolveAnchors(calibration)
                    edgeAtDown = existingEdge
                    lockedEdgeOffset = if (existingEdge == EdgeSide.LEFT) {
                        anchors.first()
                    } else {
                        anchors.last()
                    }
                    activePointerId = pointerId
                    trackedPointerId = pointerId
                    val sign = if (existingEdge == EdgeSide.LEFT) 1f else -1f
                    val existingDistance = abs(overScrollFraction) * dragSpan + slop
                    downX = x - sign * existingDistance
                    downY = y
                    lastX = x
                    lastEventTimeMs = eventTimeMs
                    outwardVelocityPxPerSec = 0f
                    activeOutwardVelocityNorm = 0f
                    peakOutwardRawPx = existingDistance
                    hasReversedPastOrigin = false
                    snapBackMode = SnapBackMode.SLOW
                    releaseElapsedNanos = 0L
                    isTouchActive = true
                    isOverScrollLocked = true
                    isGestureTracking = true
                    gestureRejected = false
                    gestureOriginX = downX
                    gestureOriginY = y
                    maxHorizontalTravelPx = existingDistance
                    hasCrossedTouchSlop = true
                    hasLeftSettledEdge = false
                    lockOriginSlopPx = slop
                    lastLeftEdgeOriginX = null
                    lastRightEdgeOriginX = null
                    wasAtLeftEdgeOffset = existingEdge == EdgeSide.LEFT
                    wasAtRightEdgeOffset = existingEdge == EdgeSide.RIGHT
                    return TouchResult(handled = true, needsRedraw = false)
                }

                val (detectedEdge, anchor) = detectSettledEdge(
                    currentXOffset = currentXOffset,
                    renderedXOffset = renderedXOffset,
                    calibration = calibration
                )
                val (atLeftNow, atRightNow) = detectEdgeOffsetFlags(currentXOffset, calibration)
                trackedPointerId = pointerId
                isGestureTracking = true
                gestureRejected = false
                gestureOriginX = x
                gestureOriginY = y
                maxHorizontalTravelPx = 0f
                hasCrossedTouchSlop = false
                lastX = x
                lastEventTimeMs = eventTimeMs
                wasAtLeftEdgeOffset = atLeftNow || detectedEdge == EdgeSide.LEFT
                wasAtRightEdgeOffset = atRightNow || detectedEdge == EdgeSide.RIGHT

                if (detectedEdge != EdgeSide.NONE) {
                    edgeAtDown = detectedEdge
                    lockedEdgeOffset = anchor
                    activePointerId = pointerId
                    downX = x
                    downY = y
                    outwardVelocityPxPerSec = 0f
                    activeOutwardVelocityNorm = 0f
                    peakOutwardRawPx = 0f
                    hasReversedPastOrigin = false
                    snapBackMode = SnapBackMode.SLOW
                    releaseElapsedNanos = 0L
                    isTouchActive = true
                    isOverScrollLocked = false
                    hasLeftSettledEdge = false
                    lockOriginSlopPx = slop
                    lastLeftEdgeOriginX = if (detectedEdge == EdgeSide.LEFT) x else null
                    lastRightEdgeOriginX = if (detectedEdge == EdgeSide.RIGHT) x else null
                } else {
                    clearTouchOnly()
                    hasLeftSettledEdge = true
                    lockOriginSlopPx = 0f
                    lastLeftEdgeOriginX = if (atLeftNow) x else null
                    lastRightEdgeOriginX = if (atRightNow) x else null
                }
                return TouchResult(handled = false, needsRedraw = false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isGestureTracking || gestureRejected) {
                    return TouchResult(handled = false, needsRedraw = false)
                }

                val stepDx = x - lastX
                val (atLeftNow, atRightNow) = detectEdgeOffsetFlags(currentXOffset, calibration)
                val anchors = resolveAnchors(calibration)

                if (!isOverScrollLocked) {
                    val totalDx = x - gestureOriginX
                    val totalDy = y - gestureOriginY
                    maxHorizontalTravelPx = max(maxHorizontalTravelPx, abs(totalDx))
                    if (maxHorizontalTravelPx > slop) {
                        hasCrossedTouchSlop = true
                    }
                    if (abs(totalDy) > slop &&
                        abs(totalDy) > maxHorizontalTravelPx * VERTICAL_REJECT_RATIO
                    ) {
                        gestureRejected = true
                        clearAllTouchState()
                        return TouchResult(handled = false, needsRedraw = false)
                    }

                    if (isTouchActive && edgeAtDown != EdgeSide.NONE && !hasLeftSettledEdge) {
                        updateVelocity(x = x, eventTimeMs = eventTimeMs, surfaceWidth = safeWidth)
                        val dx = x - downX
                        val dy = y - downY
                        val outwardRawDx = when (edgeAtDown) {
                            EdgeSide.LEFT -> dx
                            EdgeSide.RIGHT -> -dx
                            EdgeSide.NONE -> 0f
                        }
                        if (outwardRawDx > lockOriginSlopPx &&
                            outwardRawDx > abs(dy) * HORIZONTAL_LOCK_RATIO
                        ) {
                            isOverScrollLocked = true
                            peakOutwardRawPx = outwardRawDx
                        } else if (outwardRawDx < -slop ||
                            (outwardRawDx < 0f &&
                                ((edgeAtDown == EdgeSide.LEFT && !atLeftNow) ||
                                    (edgeAtDown == EdgeSide.RIGHT && !atRightNow)))
                        ) {
                            hasLeftSettledEdge = true
                            clearTouchOnly()
                            wasAtLeftEdgeOffset = atLeftNow
                            wasAtRightEdgeOffset = atRightNow
                            lastX = x
                            lastEventTimeMs = eventTimeMs
                            return TouchResult(handled = false, needsRedraw = false)
                        } else {
                            wasAtLeftEdgeOffset = atLeftNow
                            wasAtRightEdgeOffset = atRightNow
                            lastX = x
                            lastEventTimeMs = eventTimeMs
                            return TouchResult(handled = false, needsRedraw = false)
                        }
                    } else if (anchors.size >= 2) {
                        if (atLeftNow) {
                            val updatedOrigin = when {
                                !wasAtLeftEdgeOffset && lastLeftEdgeOriginX != null ->
                                    minOf(x, lastLeftEdgeOriginX!!)
                                !wasAtLeftEdgeOffset || lastLeftEdgeOriginX == null -> x
                                else -> lastLeftEdgeOriginX!!
                            }
                            lastLeftEdgeOriginX = updatedOrigin
                            val outwardThreshold = if (hasCrossedTouchSlop) 0f else slop
                            val outwardPastBoundary = x - updatedOrigin
                            if (outwardPastBoundary > outwardThreshold && stepDx > 0f) {
                                val residualPx = if (overScrollFraction < -FRACTION_EPSILON) {
                                    abs(overScrollFraction) * dragSpan
                                } else {
                                    0f
                                }
                                edgeAtDown = EdgeSide.LEFT
                                lockedEdgeOffset = anchors.first()
                                activePointerId = trackedPointerId
                                downX = updatedOrigin - residualPx
                                downY = y
                                lockOriginSlopPx = if (hasCrossedTouchSlop) 0f else slop
                                hasLeftSettledEdge = false
                                hasReversedPastOrigin = false
                                snapBackMode = SnapBackMode.SLOW
                                releaseElapsedNanos = 0L
                                isTouchActive = true
                                isOverScrollLocked = true
                                peakOutwardRawPx = x - downX
                                updateVelocity(x = x, eventTimeMs = eventTimeMs, surfaceWidth = safeWidth)
                            } else if (x <= updatedOrigin && stepDx > 0f) {
                                edgeAtDown = EdgeSide.LEFT
                                lockedEdgeOffset = anchors.first()
                                activePointerId = trackedPointerId
                                downX = updatedOrigin
                                downY = y
                                lockOriginSlopPx = if (hasCrossedTouchSlop) 0f else slop
                                hasLeftSettledEdge = false
                                isTouchActive = true
                                isOverScrollLocked = false
                                updateVelocity(x = x, eventTimeMs = eventTimeMs, surfaceWidth = safeWidth)
                                wasAtLeftEdgeOffset = atLeftNow
                                wasAtRightEdgeOffset = atRightNow
                                lastX = x
                                lastEventTimeMs = eventTimeMs
                                return TouchResult(handled = false, needsRedraw = false)
                            } else {
                                wasAtLeftEdgeOffset = atLeftNow
                                wasAtRightEdgeOffset = atRightNow
                                lastX = x
                                lastEventTimeMs = eventTimeMs
                                return TouchResult(handled = false, needsRedraw = false)
                            }
                        } else if (atRightNow) {
                            val updatedOrigin = when {
                                !wasAtRightEdgeOffset && lastRightEdgeOriginX != null ->
                                    maxOf(x, lastRightEdgeOriginX!!)
                                !wasAtRightEdgeOffset || lastRightEdgeOriginX == null -> x
                                else -> lastRightEdgeOriginX!!
                            }
                            lastRightEdgeOriginX = updatedOrigin
                            val outwardThreshold = if (hasCrossedTouchSlop) 0f else slop
                            val outwardPastBoundary = updatedOrigin - x
                            if (outwardPastBoundary > outwardThreshold && stepDx < 0f) {
                                val residualPx = if (overScrollFraction > FRACTION_EPSILON) {
                                    abs(overScrollFraction) * dragSpan
                                } else {
                                    0f
                                }
                                edgeAtDown = EdgeSide.RIGHT
                                lockedEdgeOffset = anchors.last()
                                activePointerId = trackedPointerId
                                downX = updatedOrigin + residualPx
                                downY = y
                                lockOriginSlopPx = if (hasCrossedTouchSlop) 0f else slop
                                hasLeftSettledEdge = false
                                hasReversedPastOrigin = false
                                snapBackMode = SnapBackMode.SLOW
                                releaseElapsedNanos = 0L
                                isTouchActive = true
                                isOverScrollLocked = true
                                peakOutwardRawPx = downX - x
                                updateVelocity(x = x, eventTimeMs = eventTimeMs, surfaceWidth = safeWidth)
                            } else if (x >= updatedOrigin && stepDx < 0f) {
                                edgeAtDown = EdgeSide.RIGHT
                                lockedEdgeOffset = anchors.last()
                                activePointerId = trackedPointerId
                                downX = updatedOrigin
                                downY = y
                                lockOriginSlopPx = if (hasCrossedTouchSlop) 0f else slop
                                hasLeftSettledEdge = false
                                isTouchActive = true
                                isOverScrollLocked = false
                                updateVelocity(x = x, eventTimeMs = eventTimeMs, surfaceWidth = safeWidth)
                                wasAtLeftEdgeOffset = atLeftNow
                                wasAtRightEdgeOffset = atRightNow
                                lastX = x
                                lastEventTimeMs = eventTimeMs
                                return TouchResult(handled = false, needsRedraw = false)
                            } else {
                                wasAtLeftEdgeOffset = atLeftNow
                                wasAtRightEdgeOffset = atRightNow
                                lastX = x
                                lastEventTimeMs = eventTimeMs
                                return TouchResult(handled = false, needsRedraw = false)
                            }
                        } else {
                            wasAtLeftEdgeOffset = false
                            wasAtRightEdgeOffset = false
                            lastX = x
                            lastEventTimeMs = eventTimeMs
                            return TouchResult(handled = false, needsRedraw = false)
                        }
                    } else {
                        wasAtLeftEdgeOffset = atLeftNow
                        wasAtRightEdgeOffset = atRightNow
                        lastX = x
                        lastEventTimeMs = eventTimeMs
                        return TouchResult(handled = false, needsRedraw = false)
                    }
                } else {
                    updateVelocity(x = x, eventTimeMs = eventTimeMs, surfaceWidth = safeWidth)
                }

                if (!isOverScrollLocked) {
                    wasAtLeftEdgeOffset = atLeftNow
                    wasAtRightEdgeOffset = atRightNow
                    lastX = x
                    lastEventTimeMs = eventTimeMs
                    return TouchResult(handled = false, needsRedraw = false)
                }

                val dx = x - downX
                val outwardRawDx = when (edgeAtDown) {
                    EdgeSide.LEFT -> dx
                    EdgeSide.RIGHT -> -dx
                    EdgeSide.NONE -> 0f
                }

                if (outwardRawDx <= 0f) {
                    val hadFraction = hasActiveFraction()
                    hasReversedPastOrigin = true
                    snapBackMode = SnapBackMode.FAST
                    // Retain the live overScrollFraction so the outgoing page blends
                    // smoothly into the incoming page motion without pausing at rest.
                    releaseStartFraction = overScrollFraction
                    releaseElapsedNanos = 0L
                    if (edgeAtDown == EdgeSide.LEFT && lastLeftEdgeOriginX == null) {
                        lastLeftEdgeOriginX = x
                    } else if (edgeAtDown == EdgeSide.RIGHT && lastRightEdgeOriginX == null) {
                        lastRightEdgeOriginX = x
                    }
                    hasLeftSettledEdge = true
                    clearTouchOnly()
                    wasAtLeftEdgeOffset = atLeftNow
                    wasAtRightEdgeOffset = atRightNow
                    lastX = x
                    lastEventTimeMs = eventTimeMs
                    return TouchResult(handled = false, needsRedraw = hadFraction)
                }

                if (outwardRawDx >= peakOutwardRawPx) {
                    peakOutwardRawPx = outwardRawDx
                }

                val effectivePx = if (outwardRawDx >= peakOutwardRawPx) {
                    (outwardRawDx - lockOriginSlopPx).coerceAtLeast(0f)
                } else {
                    val returnRatio = (outwardRawDx / peakOutwardRawPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    (peakOutwardRawPx - lockOriginSlopPx).coerceAtLeast(0f) * returnRatio
                }

                val distanceComponent = (effectivePx / dragSpan).coerceIn(0f, 1f)
                val reversalAttenuation = if (peakOutwardRawPx > 0f) {
                    (outwardRawDx / peakOutwardRawPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val velocityRamp = (distanceComponent / VELOCITY_RAMP_DISTANCE).coerceIn(0f, 1f)
                val softenedVelocity = activeOutwardVelocityNorm.pow(VELOCITY_RESPONSE_EXPONENT)
                val velocityBoost = VELOCITY_INTENSITY_WEIGHT *
                    softenedVelocity *
                    (0.35f + 0.65f * distanceComponent) *
                    reversalAttenuation *
                    velocityRamp

                val magnitude = (distanceComponent + velocityBoost).coerceIn(0f, 1f)
                val nextFraction = when (edgeAtDown) {
                    EdgeSide.LEFT -> -magnitude
                    EdgeSide.RIGHT -> magnitude
                    EdgeSide.NONE -> 0f
                }
                val changed = abs(nextFraction - overScrollFraction) > FRACTION_EPSILON
                overScrollFraction = nextFraction
                wasAtLeftEdgeOffset = atLeftNow
                wasAtRightEdgeOffset = atRightNow
                lastX = x
                lastEventTimeMs = eventTimeMs
                return TouchResult(handled = true, needsRedraw = changed)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isGestureTracking && pointerId == trackedPointerId) {
                    val wasLocked = isOverScrollLocked
                    if (isTouchActive || wasLocked) {
                        prepareRelease()
                    }
                    clearAllTouchState()
                    return TouchResult(
                        handled = wasLocked,
                        needsRedraw = wasLocked || hasActiveFraction()
                    )
                }
                return TouchResult(handled = false, needsRedraw = false)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val wasActive = isTouchActive
                val wasLocked = isOverScrollLocked
                if (wasActive || wasLocked) {
                    prepareRelease()
                }
                clearAllTouchState()
                return TouchResult(
                    handled = wasActive || wasLocked,
                    needsRedraw = wasLocked || hasActiveFraction()
                )
            }

            else -> return TouchResult(handled = false, needsRedraw = false)
        }
    }

    fun promoteToFastSnapBack() {
        if (snapBackMode != SnapBackMode.FAST) {
            snapBackMode = SnapBackMode.FAST
        }
    }

    fun stepRelease(elapsedNanos: Long, fastSmoothingFraction: Float) {
        if (isOverScrollLocked || isTouchActive) return
        if (!hasActiveFraction()) {
            overScrollFraction = 0f
            return
        }
        when (snapBackMode) {
            SnapBackMode.FAST -> {
                val timeStep = (
                    1.0 - exp(
                        -elapsedNanos.coerceAtLeast(1L).toDouble() /
                            FAST_SNAP_BACK_TAU_NANOS.toDouble()
                    )
                    ).toFloat()
                val effectiveStep = minOf(
                    fastSmoothingFraction.coerceIn(0f, FAST_MAX_STEP_FRACTION),
                    timeStep.coerceIn(0f, FAST_MAX_STEP_FRACTION)
                )
                overScrollFraction += (0f - overScrollFraction) * effectiveStep
                if (!hasActiveFraction()) {
                    overScrollFraction = 0f
                }
            }
            SnapBackMode.SLOW -> {
                releaseElapsedNanos += elapsedNanos.coerceAtLeast(1L)
                val t = (releaseElapsedNanos.toFloat() / SLOW_SNAP_BACK_DURATION_NANOS.toFloat())
                    .coerceIn(0f, 1f)
                val remaining = (1f - t).pow(SLOW_EASE_OUT_POWER)
                overScrollFraction = releaseStartFraction * remaining
                if (t >= 1f || !hasActiveFraction()) {
                    overScrollFraction = 0f
                }
            }
        }
    }

    fun stepReleaseDecay(smoothingFraction: Float) {
        stepRelease(
            elapsedNanos = DEFAULT_FRAME_NANOS,
            fastSmoothingFraction = smoothingFraction
        )
    }

    fun reset() {
        clearAllTouchState()
        overScrollFraction = 0f
        snapBackMode = SnapBackMode.SLOW
        hasReversedPastOrigin = false
        releaseStartFraction = 0f
        releaseElapsedNanos = 0L
    }

    private fun updateVelocity(x: Float, eventTimeMs: Long, surfaceWidth: Int) {
        val stepDx = x - lastX
        val outwardStepDx = when (edgeAtDown) {
            EdgeSide.LEFT -> stepDx
            EdgeSide.RIGHT -> -stepDx
            EdgeSide.NONE -> 0f
        }
        if (eventTimeMs > lastEventTimeMs) {
            val dtMs = (eventTimeMs - lastEventTimeMs).coerceAtLeast(1L)
            val instantVel = (outwardStepDx / (dtMs / 1000f))
                .coerceIn(-MAX_TRACKED_VELOCITY_PX_S, MAX_TRACKED_VELOCITY_PX_S)
            val alpha = (dtMs / VELOCITY_SMOOTHING_TAU_MS).coerceIn(0.15f, 0.85f)
            outwardVelocityPxPerSec += (instantVel - outwardVelocityPxPerSec) * alpha

            val referenceVel = surfaceWidth * VELOCITY_REFERENCE_SCREEN_PER_SEC
            val currentVelNorm = (outwardVelocityPxPerSec / referenceVel).coerceIn(0f, 1f)
            if (outwardStepDx > 0f) {
                activeOutwardVelocityNorm = max(currentVelNorm, activeOutwardVelocityNorm * 0.92f)
            } else if (outwardStepDx < 0f) {
                activeOutwardVelocityNorm *= 0.45f
            }
        }
        lastX = x
        lastEventTimeMs = eventTimeMs
    }

    private fun prepareRelease() {
        snapBackMode = if (hasReversedPastOrigin) SnapBackMode.FAST else SnapBackMode.SLOW
        releaseStartFraction = overScrollFraction
        releaseElapsedNanos = 0L
    }

    private fun clearTouchOnly() {
        isTouchActive = false
        isOverScrollLocked = false
        edgeAtDown = EdgeSide.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        outwardVelocityPxPerSec = 0f
        activeOutwardVelocityNorm = 0f
        peakOutwardRawPx = 0f
    }

    private fun clearAllTouchState() {
        clearTouchOnly()
        trackedPointerId = MotionEvent.INVALID_POINTER_ID
        isGestureTracking = false
        gestureRejected = false
        gestureOriginX = 0f
        gestureOriginY = 0f
        maxHorizontalTravelPx = 0f
        hasCrossedTouchSlop = false
        hasLeftSettledEdge = false
        lockOriginSlopPx = 0f
        lastLeftEdgeOriginX = null
        lastRightEdgeOriginX = null
        wasAtLeftEdgeOffset = false
        wasAtRightEdgeOffset = false
    }

    private fun detectEdgeOffsetFlags(
        xOffset: Float,
        calibration: CalibrationConfig
    ): Pair<Boolean, Boolean> {
        val anchors = resolveAnchors(calibration)
        if (anchors.size < 2) return false to false
        val segmentTolerance = anchors.zipWithNext()
            .minOfOrNull { (start, end) -> abs(end - start) }
            ?.let { it * STATIC_SEGMENT_TOLERANCE_RATIO }
            ?: 0f
        val tolerance = maxOf(STATIC_OFFSET_TOLERANCE, segmentTolerance)
        val atLeft = abs(xOffset - anchors.first()) <= tolerance
        val atRight = abs(xOffset - anchors.last()) <= tolerance
        return atLeft to atRight
    }

    private fun detectSettledEdge(
        currentXOffset: Float,
        renderedXOffset: Float,
        calibration: CalibrationConfig
    ): Pair<EdgeSide, Float> {
        val anchors = resolveAnchors(calibration)
        if (anchors.size < 2) return EdgeSide.NONE to 0f

        val segmentTolerance = anchors.zipWithNext()
            .minOfOrNull { (start, end) -> abs(end - start) }
            ?.let { it * STATIC_SEGMENT_TOLERANCE_RATIO }
            ?: 0f
        val tolerance = maxOf(STATIC_OFFSET_TOLERANCE, segmentTolerance)
        val leftAnchor = anchors.first()
        val rightAnchor = anchors.last()

        val atLeft = abs(renderedXOffset - leftAnchor) <= tolerance &&
            abs(currentXOffset - leftAnchor) <= tolerance
        if (atLeft) return EdgeSide.LEFT to leftAnchor

        val atRight = abs(renderedXOffset - rightAnchor) <= tolerance &&
            abs(currentXOffset - rightAnchor) <= tolerance
        if (atRight) return EdgeSide.RIGHT to rightAnchor

        return EdgeSide.NONE to 0f
    }

    private fun resolveAnchors(calibration: CalibrationConfig): List<Float> {
        val count = calibration.pageCount.coerceAtLeast(1)
        return if (calibration.customOffsets.size >= count) {
            calibration.customOffsets.take(count)
        } else {
            List(count) { index ->
                if (count <= 1) 0f else index.toFloat() / (count - 1).toFloat()
            }
        }
    }

    private fun overScrollDragDistancePx(surfaceWidth: Int): Float =
        (surfaceWidth * DRAG_DISTANCE_SCREEN_FRACTION).coerceAtLeast(1f)

    companion object {
        const val FRACTION_EPSILON = 0.0005f
        const val SLOW_SNAP_BACK_DURATION_NANOS = 350_000_000L
        private const val DEFAULT_FRAME_NANOS = 16_666_667L
        private const val SLOW_EASE_OUT_POWER = 2.0f
        private const val FAST_SNAP_BACK_TAU_NANOS = 45_000_000L
        private const val FAST_MAX_STEP_FRACTION = 0.42f
        private const val STATIC_OFFSET_TOLERANCE = 0.002f
        private const val STATIC_SEGMENT_TOLERANCE_RATIO = 0.02f
        private const val DRAG_DISTANCE_SCREEN_FRACTION = 0.55f
        private const val VERTICAL_REJECT_RATIO = 0.85f
        private const val HORIZONTAL_LOCK_RATIO = 1.25f
        private const val TOP_GESTURE_EXCLUSION_FRACTION = 0.05f
        private const val BOTTOM_GESTURE_EXCLUSION_FRACTION = 0.86f
        private const val MAX_TRACKED_VELOCITY_PX_S = 12_000f
        private const val VELOCITY_SMOOTHING_TAU_MS = 45f
        private const val VELOCITY_REFERENCE_SCREEN_PER_SEC = 2.4f
        private const val VELOCITY_INTENSITY_WEIGHT = 0.28f
        private const val VELOCITY_RESPONSE_EXPONENT = 0.70f
        private const val VELOCITY_RAMP_DISTANCE = 0.05f
    }
}
