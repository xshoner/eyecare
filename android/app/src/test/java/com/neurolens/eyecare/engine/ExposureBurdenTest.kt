package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class ExposureBurdenTest {
    private fun scenario(dark: Boolean, stimulating: Boolean, near: Boolean = false,
        screenQuality: Double = 1.0, eye: Boolean = true): Map<String, Signal> = buildMap {
        put("U01.continuousMin", Signal.observed(0.0))
        put("S17.captureActive", Signal.observed(1.0))
        put("S18.timeCoverage", Signal.observed(.85))
        put("S06.change", Signal.observed(if (stimulating) .10 else .02, screenQuality))
        put("S07.changedArea", Signal.observed(if (stimulating) .4 else .02, screenQuality))
        put("S03.blueRatio", Signal.observed(.30))
        put("S16.emissionProxy", Signal.observed(.65, .7, estimated = true))
        put("E01.lux", Signal.observed(if (dark) 1.0 else 400.0))
        put("S15.brightness", Signal.observed(.8))
        put("E08.darkBrightRisk", Signal.observed(if (dark) 88.2 else 0.0, .6, estimated = true))
        put("E05.thermalStatus", Signal.observed(1.0))
        put("M03.walking", Signal.observed(0.0))
        put("M01.tiltDeg", Signal.observed(15.0))
        put("U03.switchesPerMin", Signal.observed(0.0))
        put("U06.unlocks", Signal.observed(0.0))
        put("U07.microBreaks", Signal.observed(0.0))
        if (near) put("D02.nearExposureRisk", Signal.observed(80.0, .8, estimated = true))
        if (eye) {
            put("F02.blinksPerMin", Signal.observed(13.0, .8))
            put("F06.openMean", Signal.observed(.20, .8))
            put("F07.closure80", Signal.observed(.05, .8))
            put("F09.squint", Signal.observed(.10, .8))
            put("F01.coverage", Signal.observed(.8))
        }
    }

    private fun scoreAt(minutes: Int, signals: Map<String, Signal>): Int {
        val rate = MvpScorer.score(signals)
        val ledger = ObservedBurdenLedger(ObservedBurdenState(1))
        ledger.observe(0, 1, true, rate.burdenRate, rate.interactionRate)
        for (second in 1..minutes * 60)
            ledger.observe(second * 1_000L, 1, true, rate.burdenRate, rate.interactionRate)
        return ledger.score()
    }

    @Test fun sameConditionsAccumulateMonotonicallyWithoutClinicalTarget() {
        val cases = listOf(scenario(false, false), scenario(false, true),
            scenario(true, false), scenario(true, true))
        cases.forEach { inputs ->
            val values = listOf(5, 10, 30, 60).map { scoreAt(it, inputs) }
            assertEquals(values.sorted(), values)
            assertTrue(values.last() > values.first())
        }
        assertTrue(scoreAt(60, scenario(true, true)) > scoreAt(60, scenario(false, true)))
        assertTrue(scoreAt(60, scenario(true, true)) > scoreAt(60, scenario(true, false)))
        assertTrue(scoreAt(60, scenario(true, true, near = true)) > scoreAt(60, scenario(true, true)))
    }

    @Test fun correlatedNormalCuesCannotDiluteStrongScreenOrDarkness() {
        val high = MvpScorer.score(scenario(true, true))
        val withNormal = MvpScorer.score(scenario(true, true) +
            ("S09.flashPerMin" to Signal.observed(0.0)) +
            ("E05.thermalStatus" to Signal.observed(0.0)) +
            ("M01.tiltDeg" to Signal.observed(0.0)))
        assertEquals(high.domains.getValue("screen"), withNormal.domains.getValue("screen"))
        assertEquals(high.domains.getValue("environment"), withNormal.domains.getValue("environment"))
        assertTrue(withNormal.burdenRate >= high.burdenRate)
        assertEquals(listOf(26, 22, 8, 18, 26), high.nominalWeights.values.toList())
    }

    @Test fun caseLikeDarkLongSessionWithoutScreenAndEyesHasExplicitPartialEstimate() {
        // Synthetic approximation of the 1-lux / 56-minute partial report. The actual
        // screen brightness, frame coverage, and device identity are unknown.
        val partial = mapOf("E08.darkBrightRisk" to Signal.observed(88.0, .6),
            "E05.thermalStatus" to Signal.observed(1.0),
            "M01.tiltDeg" to Signal.observed(16.0),
            "M02.shake" to Signal.observed(.21),
            "U01.continuousMin" to Signal.observed(56.0),
            "U03.switchesPerMin" to Signal.observed(0.0),
            "U06.unlocks" to Signal.observed(0.0),
            "S06.change" to Signal.missing("capture_stopped"),
            "S17.captureActive" to Signal.missing("capture_stopped"),
            "S18.timeCoverage" to Signal.observed(0.0))
        val old = LegacyMvpScorer.score(partial)
        val current = MvpScorer.score(partial)
        assertTrue(old.scoreAvailable)
        assertTrue(current.scoreAvailable)
        assertTrue(current.score > 0)
        assertEquals("낮음 · 부분 관측", current.confidence)
        assertEquals(48, current.availableWeight)
        assertFalse(current.domains.containsKey("screen"))
        assertFalse(current.domains.containsKey("eyes"))
        assertTrue(current.burdenRate > 0.0) // known exposure remains visible separately
    }

    @Test fun sensorLossThermalPauseAndRestartNeverEraseObservedBurden() {
        val measured = MvpScorer.score(scenario(true, true))
        val lowQuality = MvpScorer.score(scenario(true, true, screenQuality = .1))
        assertTrue(lowQuality.scoreAvailable)
        assertNull(lowQuality.domains["screen"])
        val ledger = ObservedBurdenLedger(ObservedBurdenState(1))
        ledger.observe(0, 1, true, measured.burdenRate, measured.interactionRate)
        for (second in 1..600) ledger.observe(second * 1_000L, 1, true,
            measured.burdenRate, measured.interactionRate)
        val before = ledger.score()
        ledger.observe(601_000, 1, false, null) // severe thermal: no fabricated recovery
        ledger.observe(621_000, 1, false, null) // stall: no fabricated exposure
        val restarted = ObservedBurdenLedger(ledger.state())
        restarted.observe(641_000, 1, false, null) // 20-second app/service switch
        assertEquals(before, restarted.score())
        restarted.observe(642_000, 1, true, lowQuality.burdenRate, lowQuality.interactionRate)
        assertTrue(restarted.score() >= before)
        assertTrue(restarted.state().burdenMinutes >= ledger.state().burdenMinutes)
    }

    @Test fun scoreDoesNotDependOnTickFrequencyOrDuplicateProxies() {
        val inputs = scenario(true, true, near = true)
        val rate = MvpScorer.score(inputs)
        val duplicate = MvpScorer.score(inputs +
            ("D01.distanceEstimateCm" to Signal.observed(24.0, .8, estimated = true)) +
            ("E01.lux" to Signal.observed(1.0)))
        assertEquals(rate.burdenRate, duplicate.burdenRate, 1e-12)
        val fast = ObservedBurdenLedger(ObservedBurdenState(1))
        val slow = ObservedBurdenLedger(ObservedBurdenState(1))
        fast.observe(0, 1, true, rate.burdenRate, rate.interactionRate)
        slow.observe(0, 1, true, rate.burdenRate, rate.interactionRate)
        for (i in 1..600) fast.observe(i * 1_000L, 1, true, rate.burdenRate, rate.interactionRate)
        for (i in 1..120) slow.observe(i * 5_000L, 1, true, rate.burdenRate, rate.interactionRate)
        assertEquals(fast.score(), slow.score())
        assertEquals(fast.state().burdenMinutes, slow.state().burdenMinutes, 1e-9)
    }

    @Test fun protectedDarkFramesCannotMasqueradeAsHealthyScreenSamples() {
        val screen = ScreenSignalEngine()
        screen.frame(IntArray(64) { 0xFF000000.toInt() }, 8, 8, 1_000)
        screen.frame(IntArray(64) { 0xFF000000.toInt() }, 8, 8, 1_500)
        val signals = screen.snapshot(1_500)
        assertTrue(signals.getValue("S09.flashPerMin").quality < .30)
        assertFalse(MvpScorer.score(signals + scenario(true, false).filterKeys {
            it !in setOf("S06.change", "S07.changedArea") }).domains.containsKey("screen"))
    }

    @Test fun oldFormulaIsFrozenForHistoricalVersion() {
        assertEquals("efs-hypothesis-0.7", LegacyMvpScorer.VERSION)
        assertEquals("efs-exposure-0.11", MvpScorer.VERSION)
        assertEquals("efs-exposure-0.10", MvpScorer.EXPOSURE_010_VERSION)
        val historical = LegacyMvpScorer.score(scenario(true, true) +
            ("U01.continuousMin" to Signal.observed(5.0)))
        assertTrue(historical.scoreAvailable)
        assertEquals(28, historical.score)
    }

    @Test fun printSyntheticOldVersusNewFiveToSixtyMinuteComparison() {
        for (dark in listOf(false, true)) for (stimulating in listOf(false, true)) {
            for (minutes in listOf(5, 10, 30, 60)) {
                val signals = scenario(dark, stimulating) +
                    ("U01.continuousMin" to Signal.observed(minutes.toDouble()))
                val old = LegacyMvpScorer.score(signals)
                val current = MvpScorer.score(signals)
                val newScore = scoreAt(minutes, signals)
                println("SYNTHETIC old_vs_new dark=$dark motion=$stimulating minutes=$minutes " +
                    "old=${old.score} oldAvailable=${old.scoreAvailable} new=$newScore " +
                    "newAvailable=${current.scoreAvailable} newRate=${"%.3f".format(current.burdenRate)}")
                assertTrue(newScore in 0..100)
            }
        }
    }
}
