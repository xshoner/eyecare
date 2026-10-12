package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import org.junit.Assert.*
import org.junit.Test

class CalibrationValidationTest {
    private fun report(i: Int) = CareReport(id = "r-$i", sessionId = "s-$i",
        createdAt = i * 1_000_000L, windowStartAt = i * 1_000_000L - 60_000L,
        intervalMin = 30, score = 40, level = "test", confidence = "보통 · 연구용",
        inputs = emptyList(), usageScore = 20, screenScore = 30, screenCoverage = .5,
        version = MvpScorer.VERSION, scoreAvailable = true, availableWeight = 100)
    private fun label(i: Int, id: String = "a-$i", nrs: Int = 7, receipt: Long = i * 1_000_000L + 20_000) =
        SubjectiveResponse(id, "r-$i", nrs, i * 1_000_000L + 10_000, receivedAt = receipt)

    @Test fun rollingHoldoutsCompareSameTargetsWithoutUsingTheirOwnLabels() {
        val reports = (1..10).map(::report)
        val labels = (1..10).map(::label)
        val result = CalibrationValidator.evaluate(reports, labels)
        assertEquals(5, result.evaluated)
        assertEquals(10, result.sameVersionReports)
        assertEquals(10, result.eligibleLabels)
        assertEquals(6_000_000L, result.firstEvaluatedAt)
        assertEquals(10_000_000L, result.lastEvaluatedAt)
        assertEquals(30.0, result.rawMae!!, .001)
        assertTrue(result.personalMae!! < result.rawMae!!)
        assertTrue(result.sufficientEvidence)
    }

    @Test fun futureCorrectionsAndWeakReportsDoNotInflatePriorEvidence() {
        val reports = (1..6).map(::report)
        val labels = (1..6).map(::label)
        val later = label(1, id = "late-correction", nrs = 0, receipt = 10_000_000L)
        val a = CalibrationValidator.evaluate(reports, labels)
        val b = CalibrationValidator.evaluate(reports, labels + later)
        assertEquals(a, b)
        assertEquals(1, a.evaluated)
        assertFalse(a.sufficientEvidence)
        val weak = reports.map { it.copy(availableWeight = 10) }
        assertEquals(0, CalibrationValidator.evaluate(weak, labels).evaluated)
    }
}
