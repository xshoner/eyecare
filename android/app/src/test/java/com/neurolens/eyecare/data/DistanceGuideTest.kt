package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.Signal
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class DistanceGuideTest {
    @Test fun adviceSaysHowManyCentimetresToMoveBack() {
        assertEquals(DistanceGuide.Level.GOOD, DistanceGuide.advise(40.0).level)
        assertNull(DistanceGuide.advise(35.0).moveCm)
        val close = DistanceGuide.advise(28.4)
        assertEquals(DistanceGuide.Level.CLOSE, close.level)
        assertEquals(7, close.moveCm)
        assertTrue(close.detail.contains("7cm"))
        val tooClose = DistanceGuide.advise(20.0)
        assertEquals(DistanceGuide.Level.TOO_CLOSE, tooClose.level)
        assertEquals(15, tooClose.moveCm)
        assertEquals(DistanceGuide.Level.UNKNOWN, DistanceGuide.advise(null).level)
        assertEquals(DistanceGuide.Level.UNKNOWN, DistanceGuide.advise(Double.NaN).level)
    }

    private fun report(id: String, at: Long, cm: Double, minutes: Double) = CareReport(
        id = id, sessionId = "s", createdAt = at, windowStartAt = at - 600_000, intervalMin = 10,
        score = 30, level = "t", confidence = "보통", inputs = emptyList(), usageScore = 0, screenScore = null,
        screenCoverage = 1.0, version = MvpScorer.VERSION, mvpSignals = mapOf(
            "D01.distanceEstimateCm" to Signal.observed(cm, .8), "D05.validMinutes" to Signal.observed(minutes)))

    @Test fun meanIsWeightedByValidMinutesAndRecentSpansSevenDays() {
        val day = 86_400_000L
        val now = 30 * day + 3_600_000
        val reports = listOf(report("a", now, 30.0, 9.0), report("b", now - 60_000, 40.0, 1.0),
            report("c", now - 3 * day, 20.0, 10.0), report("d", now - 9 * day, 60.0, 10.0))
        val today = DailyDistance.summarize(reports, now, ZoneOffset.UTC)
        assertEquals(31.0, today.meanCm!!, 1e-9)
        assertEquals(30.0, today.typicalCm!!, 1e-9)
        val week = DailyDistance.recent(reports, now, 7, ZoneOffset.UTC)
        assertEquals((30.0 * 9 + 40.0 + 20.0 * 10) / 20.0, week.meanCm!!, 1e-9)
        assertEquals(3, week.reports)
    }
}
