package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.Signal
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class DailyDistanceTest {
    private fun report(at: Long, cm: Double?, minutes: Double, near: Double) = CareReport(
        sessionId = "fixture", createdAt = at, windowStartAt = at - 600_000,
        intervalMin = 10, score = 30, level = "test", confidence = "partial",
        inputs = emptyList(), usageScore = 20, screenScore = 25, screenCoverage = .5,
        version = MvpScorer.VERSION,
        mvpSignals = mapOf("D01.distanceEstimateCm" to (cm?.let { Signal.observed(it) }
            ?: Signal.missing("no_face")), "D05.validMinutes" to Signal.observed(minutes),
            "D03.nearRatio" to Signal.observed(near), "D06.calibrated" to Signal.observed(0.0),
            "D08.windowMinutes" to Signal.observed(10.0)))
    @Test fun dayUsesObservedMinutesAndIgnoresMissingOrOlderVersion() {
        val day = 1_700_000_000_000L
        val result = DailyDistance.summarize(listOf(report(day, 25.0, 5.0, .8),
            report(day + 600_000, 45.0, 1.0, .1), report(day + 1_200_000, null, 0.0, 0.0),
            report(day + 1_800_000, 15.0, 10.0, 1.0).copy(version = MvpScorer.LEGACY_VERSION)),
            day, ZoneOffset.UTC)
        assertEquals(25.0, result.typicalCm!!, .01)
        assertEquals(6.0, result.observedMinutes, .01)
        assertEquals(4.1 / 6.0, result.nearRatio!!, .01)
        assertEquals(3, result.reports)
        assertEquals(.2, result.coverage, .01)
        assertFalse(result.calibrated)
    }
}
