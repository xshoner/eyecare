package com.neurolens.eyecare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestEffectSummaryTest {
    private fun event(at: Long, type: String) = CareEvent("e-$at-$type", "session", at, type)
    private fun report(id: String, at: Long) = CareReport(id = id, sessionId = "session",
        createdAt = at, windowStartAt = at - 100, intervalMin = 10, score = 50,
        level = "test", confidence = "partial", inputs = emptyList(), usageScore = 0,
        screenScore = null, screenCoverage = .5)

    @Test fun selectionIsNotConfirmationAndOneNrsCannotPairTwice() {
        val events = listOf(event(1_000, "rest_alert_shown"), event(1_100, "rest_alert_selected"),
            event(2_000, "rest_alert_shown"), event(2_100, "rest_alert_snoozed"))
        val answer = SubjectiveResponse("a", "r", 6, 3_100)
        val summary = RestEffectAnalyzer.summarize(events, listOf(report("r", 3_000)),
            listOf(answer), 20)
        assertEquals(2, summary.shown)
        assertEquals(1, summary.selected)
        assertEquals(0, summary.confirmed)
        assertEquals(1, summary.pairedNrs)
        assertEquals(1, summary.missingNrs)
        assertEquals(6.0, summary.meanFollowupNrs!!, .001)
        assertNull(summary.suggestedCooldownMinutes)
    }

    @Test fun repeatedDismissalOnlyPreviewsLongerCooldown() {
        val events = (1..5).flatMap { i -> listOf(event(i * 100_000L, "rest_alert_shown"),
            event(i * 100_000L + 1, "rest_alert_dismissed")) } + event(600_000, "rest_confirmed")
        val summary = RestEffectAnalyzer.summarize(events, emptyList(), emptyList(), 20)
        assertEquals(1, summary.confirmed)
        assertEquals(5, summary.missingNrs)
        assertEquals(30, summary.suggestedCooldownMinutes)
    }
}
