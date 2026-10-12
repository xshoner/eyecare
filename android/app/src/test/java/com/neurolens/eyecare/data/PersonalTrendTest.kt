package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import org.junit.Assert.*
import org.junit.Test

class PersonalTrendTest {
    private fun report(at: Long, score: Int, weight: Int = 100, available: Boolean = true,
        version: String = MvpScorer.VERSION) = CareReport(
        id = "r-$at", sessionId = "s-$at", createdAt = at, windowStartAt = at - 1000,
        intervalMin = 30, score = score, level = "test", confidence = "보통 · 연구용",
        inputs = emptyList(), usageScore = 20, screenScore = null, screenCoverage = .4,
        version = version, scoreAvailable = available, availableWeight = weight)

    @Test fun firstUseAndUnavailableStayExplicit() {
        assertNull(PersonalTrend.summarize(emptyList()).latest)
        val first = PersonalTrend.summarize(listOf(report(10, 45)))
        assertNull(first.previousDelta)
        assertNull(first.priorMedian)
        val missing = PersonalTrend.summarize(listOf(report(20, 0, available = false), report(10, 45)))
        assertNull(missing.previousDelta)
    }

    @Test fun medianUsesOnlyEarlierComparableQualityReports() {
        val result = PersonalTrend.summarize(listOf(report(50, 80), report(10, 20),
            report(20, 40), report(30, 60), report(40, 99, weight = 5)))
        assertEquals(20, result.previousDelta)
        assertEquals(40, result.priorMedian)
        assertEquals(3, result.priorSamples)
    }

    @Test fun versionAndMissingnessDoNotLeakIntoComparison() {
        val result = PersonalTrend.summarize(listOf(report(50, 70), report(40, 90, version = "efs-heuristic-0.3"),
            report(30, 55), report(20, 60, weight = 0), report(10, 45)))
        assertEquals(15, result.previousDelta)
        assertNull(result.priorMedian)
        assertEquals(2, result.priorSamples)
    }
    @Test fun homeListAndDetailUseSameQualityFilteredPrevious() {
        val reports = listOf(report(40, 75), report(30, 99, weight = 5), report(20, 55))
        assertEquals(55, PersonalTrend.previous(reports[0], reports)?.score)
        assertEquals(20, PersonalTrend.summarize(reports).previousDelta)
        assertNull(PersonalTrend.previous(report(50, 60, version = "new-version"), reports))
    }
    @Test fun lowEvidenceCurrentScoreIsPreservedButNotComparedOrCharted() {
        val current = report(50, 82, weight = 20)
        val earlier = report(40, 55)
        assertTrue(current.scoreAvailable)
        assertEquals(82, current.score)
        assertFalse(PersonalTrend.comparable(current))
        assertNull(PersonalTrend.previous(current, listOf(earlier)))
        assertNull(PersonalTrend.summarize(listOf(current, earlier)).previousDelta)
        assertTrue(PersonalTrend.comparable(earlier))
        assertFalse(PersonalTrend.comparable(report(30, 40, version = "efs-heuristic-0.3")))
    }
}
