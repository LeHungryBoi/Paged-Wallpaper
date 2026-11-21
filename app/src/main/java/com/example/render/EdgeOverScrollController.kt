package com.example.render

import android.view.MotionEvent
import com.example.data.model.CalibrationConfig
import kotlin.math.abs

class EdgeOverScrollController {

    enum class EdgeSide {
        NONE,
        LEFT,
        RIGHT
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

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var edgeAtDown: EdgeSide = EdgeSide.NONE

    fun hasActiveFraction(): Boolean = abs(overScrollFraction) > FRACTION_EPSILON

    fun onTouchEvent(
        event: MotionEvent,
        surfaceWidth: Int,
        touchSlop: Int,
        currentXOffset: Float,
        renderedXOffset: Float,
        calibration: CalibrationConfig
    ): TouchResult {
        val actionMasked = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = if (pointerIndex in 0 until event.pointerCount) {
            event.getPointerId(pointerIndex)
        } else {
            MotionEvent.INVALID_POINTER_ID
        }
        val activeIdx = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
            event.findPointerIndex(activePointerId)
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
            calibration = calibration
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
        calibration: CalibrationConfig
    ): TouchResult {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dragSpan = overScrollDragDistancePx(surfaceWidth)
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
                    val sign = if (existingEdge == EdgeSide.LEFT) 1f else -1f
                    val existingDistance = abs(overScrollFraction) * dragSpan + touchSlop
                    downX = x - sign * existingDistance
                    downY = y
                    isTouchActive = true
                    isOverScrollLocked = true
                    return TouchResult(handled = true, needsRedraw = false)
                }

                val (detectedEdge, anchor) = detectSettledEdge(
                    currentXOffset = currentXOffset,
                    renderedXOffset = renderedXOffset,
                    calibration = calibration
                )
                if (detectedEdge != EdgeSide.NONE) {
                    edgeAtDown = detectedEdge
                    lockedEdgeOffset = anchor
                    activePointerId = pointerId
                    downX = x
                    downY = y
                    isTouchActive = true
                    isOverScrollLocked = false
                } else {
                    clearTouchOnly()
                }
                return TouchResult(handled = false, needsRedraw = false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTouchActive || edgeAtDown == EdgeSide.NONE) {
                    return TouchResult(handled = false, needsRedraw = false)
                }
                val dx = x - downX
                val dy = y - downY
                val slop = touchSlop.coerceAtLeast(1).toFloat()

                if (!isOverScrollLocked) {
                    if (abs(dy) > slop && abs(dy) > abs(dx) * VERTICAL_REJECT_RATIO) {
                        clearTouchOnly()
                        return TouchResult(handled = false, needsRedraw = false)
                    }
                    when (edgeAtDown) {
                        EdgeSide.LEFT -> {
                            if (dx > slop) {
                                isOverScrollLocked = true
                            } else if (dx < -slop) {
                                clearTouchOnly()
                                return TouchResult(handled = false, needsRedraw = false)
                            }
                        }
                        EdgeSide.RIGHT -> {
                            if (dx < -slop) {
                                isOverScrollLocked = true
                            } else if (dx > slop) {
                                clearTouchOnly()
                                return TouchResult(handled = false, needsRedraw = false)
                            }
                        }
                        EdgeSide.NONE -> Unit
                    }
                }

                if (!isOverScrollLocked) {
                    return TouchResult(handled = false, needsRedraw = false)
                }

                val dragSpan = overScrollDragDistancePx(surfaceWidth)
                val nextFraction = when (edgeAtDown) {
                    EdgeSide.LEFT -> {
                        val effectiveDx = (dx - slop).coerceAtLeast(0f)
                        -(effectiveDx / dragSpan).coerceIn(0f, 1f)
                    }
                    EdgeSide.RIGHT -> {
                        val effectiveDx = (-dx - slop).coerceAtLeast(0f)
                        (effectiveDx / dragSpan).coerceIn(0f, 1f)
                    }
                    EdgeSide.NONE -> 0f
                }
                val changed = abs(nextFraction - overScrollFraction) > FRACTION_EPSILON
                overScrollFraction = nextFraction
                return TouchResult(handled = true, needsRedraw = changed)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isTouchActive && pointerId == activePointerId) {
                    val wasLocked = isOverScrollLocked
                    clearTouchOnly()
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
                clearTouchOnly()
                return TouchResult(
                    handled = wasActive || wasLocked,
                    needsRedraw = wasLocked || hasActiveFraction()
                )
            }

            else -> return TouchResult(handled = false, needsRedraw = false)
        }
    }

    fun stepReleaseDecay(smoothingFraction: Float) {
        if (isOverScrollLocked || isTouchActive) return
        if (!hasActiveFraction()) {
            overScrollFraction = 0f
            return
        }
        overScrollFraction += (0f - overScrollFraction) * smoothingFraction.coerceIn(0f, 1f)
        if (!hasActiveFraction()) {
            overScrollFraction = 0f
        }
    }

    fun reset() {
        clearTouchOnly()
        overScrollFraction = 0f
    }

    private fun clearTouchOnly() {
        isTouchActive = false
        isOverScrollLocked = false
        edgeAtDown = EdgeSide.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
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
        private const val STATIC_OFFSET_TOLERANCE = 0.002f
        private const val STATIC_SEGMENT_TOLERANCE_RATIO = 0.02f
        private const val DRAG_DISTANCE_SCREEN_FRACTION = 0.55f
        private const val VERTICAL_REJECT_RATIO = 1.2f
    }
}
