package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SubjectiveObservationTest {
    private val pseudonym = UUID.fromString("6a66086a-b1a0-4d3b-919c-14b64a896b41")

    @Test fun appendOnlyAnswerDoesNotDuplicateOrChangeObservationPayload() {
        val base = CareReport(id = UUID.randomUUID().toString(), sessionId = "synthetic",
            createdAt = 1_700_000_000_000L, windowStartAt = 1_700_000_000_000L - 60_000,
            intervalMin = 30, score = 42, level = "test", confidence = "partial",
            inputs = emptyList(), usageScore = 20, screenScore = 30, screenCoverage = .5,
            version = MvpScorer.VERSION, nrs = 7)
        val historical = ResearchObservationFactory.from(base, pseudonym, "research-v1")!!
        val eventBased = ResearchObservationFactory.from(base.copy(subjectiveEventV1 = true),
            pseudonym, "research-v1")!!
        assertEquals(7, historical.subjectiveFatigueNrs)
        assertNull(eventBased.subjectiveFatigueNrs)
        assertEquals(historical.id, eventBased.id)
        assertEquals(historical.score, eventBased.score)
    }
}
