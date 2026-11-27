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

        assertTrue(gate.tryBeginFrame(fromOffsetUpdate = false))
        assertTrue(gate.isInFlight)

        assertFalse(gate.tryBeginFrame(fromOffsetUpdate = false))
        assertFalse(gate.tryBeginFrame(fromOffsetUpdate = true))
        assertTrue(gate.isInFlight)

        val deferred = gate.completeFrame()
        assertEquals(true, deferred?.fromOffsetUpdate)
        assertFalse(gate.isInFlight)
        assertNull(gate.completeFrame())

        assertTrue(gate.tryBeginFrame(fromOffsetUpdate = false))
    }

    @Test
    fun renderFrameGateCancelDropsDeferredWorkWhenSurfaceIsDestroyed() {
        val gate = RenderFrameGate()
        assertTrue(gate.tryBeginFrame(fromOffsetUpdate = true))
        assertFalse(gate.tryBeginFrame(fromOffsetUpdate = false))

        gate.cancel()

        assertFalse(gate.isInFlight)
        assertNull(gate.completeFrame())
        assertTrue(gate.tryBeginFrame(fromOffsetUpdate = false))
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
    }
}
