package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class ReportSchedulerTest {
    @Test fun activeTimePausesAndLongRestResets() {
        val s = ReportScheduler(10, startMs = 0)
        assertFalse(s.update(4 * 60_000L))
        s.update(4 * 60_000L, false)
        assertFalse(s.update(6 * 60_000L))
        s.update(6 * 60_000L, true)
        assertEquals(6 * 60_000L, s.remainingMs())
        s.update(7 * 60_000L, false)
        s.update(12 * 60_000L, true)
        assertEquals(1, s.restCount)
        assertEquals(0, s.continuousMillis())
        assertEquals(10 * 60_000L, s.remainingMs())
        assertTrue(s.update(22 * 60_000L))
        s.reported(22 * 60_000L)
        assertEquals(10 * 60_000L, s.remainingMs())
    }

    @Test fun changingIntervalCanTriggerImmediateReport() {
        val s = ReportScheduler(30, startMs = 0)
        s.update(12 * 60_000L)
        assertTrue(s.settings(10, ReportScheduler.Basis.ACTIVE_SCREEN, 12 * 60_000L))
        s.reported(12 * 60_000L)
        assertFalse(s.update(13 * 60_000L))
    }

    @Test fun elapsedBasisCountsShortScreenOff() {
        val s = ReportScheduler(10, ReportScheduler.Basis.ELAPSED, startMs = 0)
        s.update(4 * 60_000L, false)
        assertTrue(s.update(10 * 60_000L))
    }
}
