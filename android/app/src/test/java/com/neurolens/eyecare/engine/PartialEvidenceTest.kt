package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class PartialEvidenceTest {
    private val representatives = linkedMapOf(
        "screen" to ("S06.change" to Signal.observed(.1)),
        "environment" to ("E01.lux" to Signal.observed(1.0)),
        "posture" to ("M01.tiltDeg" to Signal.observed(45.0)),
        "usage" to ("U01.continuousMin" to Signal.observed(12.0)),
        "eyes" to ("F03.longestIbiSec" to Signal.observed(20.0)))

    @Test fun everyNonEmptyDomainCombinationProducesIdentifiedPartialEstimate() {
        val names = representatives.keys.toList()
        for (mask in 1 until (1 shl names.size)) {
            val selected = names.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val signals = selected.associate { representatives.getValue(it) }
            val result = MvpScorer.score(signals)
            assertTrue("mask=$mask", result.scoreAvailable)
            assertEquals(selected.toSet(), result.domains.keys)
            assertEquals(selected.sumOf { MvpScorer.WEIGHTS.getValue(it) }, result.availableWeight)
            assertEquals(signals.keys.toSet(), result.inputs.toSet())
            assertEquals(result.contributions.values.sum() / 100.0 + result.interactionRate,
                result.burdenRate, 1e-12)
            assertEquals("낮음 · 부분 관측", result.confidence)
        }
        assertFalse(MvpScorer.score(emptyMap()).scoreAvailable)
        assertFalse(MvpScorer.score(mapOf("E01.lux" to Signal.missing("stale"))).scoreAvailable)
    }

    @Test fun aMissingDomainDoesNotRenormalizeKnownContributionOrEraseLedger() {
        val full = MvpScorer.score(representatives.values.toMap())
        val known = MvpScorer.score(representatives.filterKeys { it != "eyes" }.values.toMap())
        assertEquals(full.contributions.getValue("environment"),
            known.contributions.getValue("environment"), 1e-12)
        assertEquals(full.contributions.getValue("usage"), known.contributions.getValue("usage"), 1e-12)
        assertTrue(known.score < full.score) // A missing eye cue lowers current evidence, not prior burden.
        val ledger = ObservedBurdenLedger(ObservedBurdenState(1))
        ledger.observe(0, 1, true, full.burdenRate)
        for (second in 1..60) ledger.observe(second * 1_000L, 1, true, full.burdenRate)
        val before = ledger.score()
        for (second in 61..120) ledger.observe(second * 1_000L, 1, true, known.burdenRate)
        assertTrue(ledger.score() >= before)
        val after = ledger.score()
        ledger.observe(121_000, 1, false, null)
        assertEquals(after, ledger.score())
    }

    @Test fun staleMonotonicSamplesAreRejectedAtBoundary() {
        assertFalse(ObservationFreshness.current(100_000, -1))
        assertFalse(ObservationFreshness.current(100_000, 100_001))
        assertTrue(ObservationFreshness.current(100_000, 90_000))
        assertFalse(ObservationFreshness.current(100_001, 90_000))
    }
}
