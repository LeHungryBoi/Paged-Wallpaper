package com.example

import com.example.diagnostics.WallpaperDiagnosticsSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperDiagnosticsSessionTest {

    @Test
    fun recordsMovingUpdateAndFrameSubmissionRatesSeparatelyFromVisibleAverage() {
        val start = 1_000_000_000L
        val session = WallpaperDiagnosticsSession(
            startedAtNanos = start,
            recordedAtMillis = 1_000L,
            initiallyVisible = true
        )

        session.recordOffsetUpdate(0.1f, true, start)
        session.recordOffsetUpdate(0.2f, true, start + 16_000_000L)
        session.recordOffsetUpdate(0.3f, true, start + 32_000_000L)
        session.recordFrameSubmitted(true, true, start + 16_000_000L)
        session.recordFrameSubmitted(true, true, start + 32_000_000L)
        session.recordFrameSubmitted(true, true, start + 48_000_000L)
        session.recordFrameSubmitted(false, true, start + 64_000_000L)

        val result = session.snapshot(start + 100_000_000L)

        assertEquals(3, result.visibleOffsetCallbacks)
        assertEquals(2, result.changedOffsetCallbacks)
        assertEquals(2, result.movingOffsetIntervalCount)
        assertEquals(32_000_000L, result.movingOffsetDurationNanos)
        assertEquals(4, result.visibleFramesSubmitted)
        assertEquals(3, result.offsetDrivenFramesSubmitted)
        assertEquals(2, result.movingFrameIntervalCount)
        assertEquals(32_000_000L, result.movingFrameDurationNanos)
        assertEquals(62.5f, result.movingOffsetHz!!, 0.1f)
        assertEquals(62.5f, result.movingFrameSubmissionHz!!, 0.1f)
        assertEquals(30f, result.averageOffsetCallbackHz, 0.1f)
        assertEquals(40f, result.averageFrameSubmissionHz, 0.1f)
    }

    @Test
    fun excludesTimeWhenWallpaperIsNotVisibleFromVisibleAverageRates() {
        val start = 2_000_000_000L
        val session = WallpaperDiagnosticsSession(
            startedAtNanos = start,
            recordedAtMillis = 2_000L,
            initiallyVisible = true
        )

        session.recordOffsetUpdate(0.1f, true, start + 10_000_000L)
        session.recordVisibilityChanged(false, start + 20_000_000L)
        session.recordOffsetUpdate(0.2f, false, start + 30_000_000L)
        session.recordVisibilityChanged(true, start + 80_000_000L)
        session.recordOffsetUpdate(0.2f, true, start + 90_000_000L)

        val result = session.snapshot(start + 100_000_000L)

        assertEquals(2, result.visibleOffsetCallbacks)
        assertEquals(40_000_000L, result.visibleDurationNanos)
        assertEquals(50f, result.averageOffsetCallbackHz, 0.1f)
        assertNull(result.movingOffsetHz)
    }
}
