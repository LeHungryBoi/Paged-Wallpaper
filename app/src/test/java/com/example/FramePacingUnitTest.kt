package com.example

import com.example.render.FrameSmoothing
import com.example.render.RenderFrameGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingUnitTest {

    @Test
    fun renderFrameGateAllowsOnlyOneInFlightRequestAndCoalescesDeferredRedraws() {
        val gate = RenderFrameGate()

        val firstFrameId = gate.beginFrame(fromOffsetUpdate = false)
        assertTrue(firstFrameId != null)
        assertTrue(gate.isInFlight)

        assertNull(gate.beginFrame(fromOffsetUpdate = false))
        assertNull(gate.beginFrame(fromOffsetUpdate = true))
        assertTrue(gate.isInFlight)

        val completion = gate.completeFrame(checkNotNull(firstFrameId))
        assertEquals(true, completion?.deferredRedraw?.fromOffsetUpdate)
        assertFalse(gate.isInFlight)
        assertNull(gate.completeFrame(checkNotNull(firstFrameId)))

        assertTrue(gate.beginFrame(fromOffsetUpdate = false) != null)
    }

    @Test
    fun staleCompletionCannotReleaseANewerFrame() {
        val gate = RenderFrameGate()
        val firstFrameId = checkNotNull(gate.beginFrame(fromOffsetUpdate = false))
        assertNotNull(gate.completeFrame(firstFrameId))

        val secondFrameId = checkNotNull(gate.beginFrame(fromOffsetUpdate = true))
        assertNull(gate.completeFrame(firstFrameId))
        assertTrue(gate.isCurrent(secondFrameId))
        assertTrue(gate.isInFlight)
    }

    @Test
    fun renderFrameGateCancelDropsDeferredWorkWhenSurfaceIsDestroyed() {
        val gate = RenderFrameGate()
        assertTrue(gate.beginFrame(fromOffsetUpdate = true) != null)
        assertNull(gate.beginFrame(fromOffsetUpdate = false))

        gate.cancel()

        assertFalse(gate.isInFlight)
        assertNull(gate.beginFrame(fromOffsetUpdate = false).let { id ->
            if (id == null) null else gate.completeFrame(id)
        }?.deferredRedraw)
    }

    @Test
    fun smoothingDependsOnElapsedTimeRatherThanCallbackCount() {
        val oneStep = FrameSmoothing.fraction(20_000_000L)
        val halfStep = FrameSmoothing.fraction(10_000_000L)
        val twoHalfSteps = halfStep + (1f - halfStep) * halfStep

        assertEquals(oneStep, twoHalfSteps, 0.00001f)
    }

    @Test
    fun smoothingRespondsToLongerFrameIntervalsAndRemainsBounded() {
        val shortInterval = FrameSmoothing.fraction(8_000_000L)
        val longInterval = FrameSmoothing.fraction(32_000_000L)

        assertTrue(shortInterval > 0f)
        assertTrue(longInterval > shortInterval)
        assertTrue(longInterval < 1f)
        assertEquals(1f, FrameSmoothing.fraction(1_000_000_000L), 0.00001f)
        assertEquals(0f, FrameSmoothing.fraction(0L), 0f)
    }

    private fun assertNotNull(value: Any?) {
        assertTrue(value != null)
    }
}
