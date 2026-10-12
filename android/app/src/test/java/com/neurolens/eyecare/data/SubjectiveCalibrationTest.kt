package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import org.junit.Assert.*
import org.junit.Test

class SubjectiveCalibrationTest {
    private fun report(i: Int, score: Int = 40, weight: Int = 100) = CareReport(
        id = "r-$i", sessionId = "s-$i", createdAt = i * 1_000_000L,
        windowStartAt = i * 1_000_000L - 60_000, intervalMin = 30,
        score = score, level = "test", confidence = "보통 · 연구용", inputs = emptyList(),
        usageScore = 20, screenScore = 30, screenCoverage = .5, version = MvpScorer.VERSION,
        scoreAvailable = true, availableWeight = weight)
    private fun answer(i: Int, nrs: Int, receipt: Long = i * 1_000_000L + 20_000L,
        id: String = "a-$i") = SubjectiveResponse(id, "r-$i", nrs,
        i * 1_000_000L + 10_000L, receivedAt = receipt)

    @Test fun fivePriorLabelsProduceSmallBoundedOffsetWithoutChangingRawScore() {
        val reports = (1..6).map { report(it) }
        val answers = (1..5).map { answer(it, 7) } // subjective 70 vs raw 40
        val result = PersonalFatigueCalibration.forReport(reports.last(), reports, answers)
        assertEquals(40, result.rawScore)
        assertEquals(5, result.samples)
        assertEquals(10, result.offset)
        assertEquals(50, result.personalizedScore)
        assertEquals("personal-offset-v1", result.version)
    }

    @Test fun missingFutureDelayedAndPoorQualityLabelsCannotTrain() {
        val reports = (1..7).map { report(it) }
        val answers = (1..5).map { answer(it, 7) } + answer(6, 10, 9_000_000L)
        val beforeReceipt = PersonalFatigueCalibration.forReport(reports[5], reports,
            answers.map { it.copy(receivedAt = 9_000_000L) })
        assertNull(beforeReceipt.personalizedScore)
        assertEquals(0, beforeReceipt.samples)
        val weak = PersonalFatigueCalibration.forReport(report(8, weight = 20), reports, answers)
        assertNull(weak.personalizedScore)
        val late = answers.map { it.copy(answeredAt = it.answeredAt + 2 * 24 * 60 * 60 * 1000L) }
        assertNull(PersonalFatigueCalibration.forReport(reports.last(), reports, late).personalizedScore)
    }

    @Test fun latestReceivedCorrectionWinsAndOutlierDoesNotDominateMedian() {
        val reports = (1..7).map { report(it) }
        val answers = (1..5).map { answer(it, if (it == 5) 0 else 7) } +
            answer(1, 7, receipt = 1_030_000L, id = "correction")
        val result = PersonalFatigueCalibration.forReport(reports.last(), reports, answers)
        assertEquals(5, result.samples)
        assertEquals(10, result.offset)
        assertEquals(50, result.personalizedScore)
    }
}
