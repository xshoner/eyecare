package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class StrainModelTest {
    private fun minutesToReach(inputsAt: (Double) -> StrainModel.Inputs, target: Int, maxMinutes: Int = 600): Int? {
        val ledger = StrainLedger(StrainState(1))
        var t = 0L
        ledger.observe(t, 0, 1, viewing = true, resting = false, assessment = null)
        for (second in 1..maxMinutes * 60) {
            t += 1000
            val i = inputsAt(second / 60.0).copy(darkMinutes = ledger.darkMinutes, nearMinutes = ledger.nearMinutes)
            ledger.observe(t, t, 1, viewing = true, resting = false, assessment = StrainModel.assess(i))
            if (ledger.condition() <= target) return second / 60
        }
        return null
    }

    @Test fun missingInputsDeductOnlyTheBaseScreenTime() {
        val a = StrainModel.assess(StrainModel.Inputs())
        assertEquals(setOf(StrainModel.Factor.BASE), a.rates.keys)
        assertEquals(.05, a.totalRate, 1e-9)
        assertFalse(a.dark); assertNull(a.stimulus)
    }

    @Test fun nearIsJudgedAgainstAbsoluteAndHabitualDistance() {
        assertEquals(0.0, StrainModel.nearSeverity(40.0, null), 1e-9)
        assertEquals(1.0, StrainModel.nearSeverity(20.0, null), 1e-9)
        // 33 cm is only slightly close in absolute terms, but clearly closer than a usual 42 cm.
        assertTrue(StrainModel.nearSeverity(33.0, 42.0) > StrainModel.nearSeverity(33.0, null))
        assertEquals(0.0, StrainModel.nearSeverity(null, 40.0), 1e-9)
    }

    @Test fun darkDeductionGrowsWithTimeAndStimulation() {
        val static = StrainModel.Inputs(lux = 3.0, screenChange = .01)
        val intense = StrainModel.Inputs(lux = 3.0, screenChange = .15)
        val early = StrainModel.assess(intense).rates.getValue(StrainModel.Factor.DARK)
        val late = StrainModel.assess(intense.copy(darkMinutes = 40.0)).rates.getValue(StrainModel.Factor.DARK)
        assertEquals(3.0, late / early, 1e-9)
        val hour = StrainModel.assess(intense.copy(darkMinutes = 90.0)).rates.getValue(StrainModel.Factor.DARK)
        assertEquals(4.0, hour / early, 1e-9)
        assertEquals(StrainModel.Stimulus.STATIC, StrainModel.assess(static).stimulus)
        assertEquals(StrainModel.Stimulus.INTENSE, StrainModel.assess(intense).stimulus)
        assertTrue(StrainModel.assess(intense).rates.getValue(StrainModel.Factor.DARK) >
            StrainModel.assess(static).rates.getValue(StrainModel.Factor.DARK) * 2)
        assertNull(StrainModel.assess(StrainModel.Inputs(lux = 300.0)).rates[StrainModel.Factor.DARK])
    }

    @Test fun screenTimeAndLongViewingEscalateInSteps() {
        val base = listOf(0.0, 19.9, 20.0, 40.0, 60.0, 90.0).map { StrainModel.screenTimeRate(it) }
        assertEquals(listOf(.05, .05, .10, .20, .40, .90), base)
        val long = listOf(10.0, 20.0, 40.0, 60.0, 90.0).map { StrainModel.longRate(it) }
        assertEquals(listOf(0.0, .15, .30, .50, .80), long)
        // 90 minutes without a break in good conditions now lands in 보통 (0.14 stayed in 양호).
        val ledger = StrainLedger(StrainState(1))
        var t = 0L
        ledger.observe(t, 0, 1, viewing = true, resting = false, assessment = null)
        repeat(90 * 60) { s ->
            t += 1000
            ledger.observe(t, t, 1, viewing = true, resting = false,
                assessment = StrainModel.assess(StrainModel.Inputs(lux = 300.0, distanceCm = 40.0, continuousMin = (s + 1) / 60.0)))
        }
        assertTrue("condition ${ledger.condition()}", ledger.condition() in 50..62)
    }

    @Test fun nearViewingWeighsUpToFourTimesAfterTwentyMinutes() {
        assertEquals(1.0, StrainModel.nearTimeWeight(0.0), 1e-9)
        assertEquals(1.0, StrainModel.nearTimeWeight(20.0), 1e-9)
        assertEquals(2.5, StrainModel.nearTimeWeight(40.0), 1e-9)
        assertEquals(4.0, StrainModel.nearTimeWeight(90.0), 1e-9)
        val i = StrainModel.Inputs(lux = 300.0, distanceCm = 25.0)
        assertEquals(4.0, StrainModel.assess(i.copy(nearMinutes = 60.0)).rates.getValue(StrainModel.Factor.NEAR) /
            StrainModel.assess(i).rates.getValue(StrainModel.Factor.NEAR), 1e-9)
    }

    @Test fun otherFactorsDoubleBetweenTwentyAndSixtyMinutes() {
        val i = StrainModel.Inputs(lux = 300.0, flashPerMin = 8.0, blinksPerMin = 4.0, tiltDeg = 70.0)
        val early = StrainModel.assess(i.copy(continuousMin = 10.0)).rates
        val late = StrainModel.assess(i.copy(continuousMin = 60.0)).rates
        listOf(StrainModel.Factor.FLASH, StrainModel.Factor.BLINK, StrainModel.Factor.LYING).forEach { f ->
            assertEquals(f.name, 2.0, late.getValue(f) / early.getValue(f), 1e-9)
        }
    }

    @Test fun flashingContentIsADeductionOnItsOwn() {
        val a = StrainModel.assess(StrainModel.Inputs(lux = 300.0, flashPerMin = 8.0))
        assertTrue((a.rates[StrainModel.Factor.FLASH] ?: 0.0) > .3)
    }

    @Test fun darkNearLongCombinationMultipliesAndIsCapped() {
        val i = StrainModel.Inputs(lux = 3.0, distanceCm = 24.0, continuousMin = 35.0, screenChange = .15,
            flashPerMin = 8.0, darkMinutes = 40.0, nearMinutes = 40.0, brightness = 1.0)
        val a = StrainModel.assess(i)
        assertEquals(StrainModel.Combo.DARK_NEAR_LONG, a.combo)
        assertEquals(StrainModel.MAX_RATE, a.totalRate, 1e-9)
        assertEquals(StrainModel.Combo.DARK_NEAR, StrainModel.assess(i.copy(continuousMin = 10.0)).combo)
    }

    @Test fun riskyConditionsReachCautionMuchSoonerThanNormalUse() {
        val normal = minutesToReach({ m -> StrainModel.Inputs(lux = 300.0, distanceCm = 40.0, continuousMin = m, screenChange = .02) }, 39)
        val risky = minutesToReach({ m -> StrainModel.Inputs(lux = 3.0, distanceCm = 24.0, continuousMin = m,
            screenChange = .15, flashPerMin = 6.0, brightness = .9) }, 39)!!
        assertTrue("risky $risky", risky in 15..32)
        assertTrue("normal $normal vs risky $risky", normal != null && normal in 90..130 && normal > risky * 3)
    }

    @Test fun observedRestRestoresPointsFasterAtFirst() {
        assertEquals(7.5, StrainModel.recoveryPoints(0, 5 * 60_000L), 1e-9)
        assertEquals(5.0, StrainModel.recoveryPoints(5 * 60_000L, 5 * 60_000L), 1e-9)
        val ledger = StrainLedger(StrainState(1, deduction = 30.0, byFactor = mapOf("dark" to 20.0, "near" to 10.0)))
        var t = 0L
        ledger.observe(t, 0, 1, viewing = false, resting = true, assessment = null)
        repeat(600) { t += 1000; ledger.observe(t, t, 1, viewing = false, resting = true, assessment = null) }
        assertEquals(30.0 - 12.5, ledger.state().deduction, 1e-6)
        assertEquals(2.0, ledger.state().byFactor.getValue("dark") / ledger.state().byFactor.getValue("near"), 1e-9)
    }

    @Test fun unobservedTimeIsNeitherDeductionNorRest() {
        val ledger = StrainLedger(StrainState(1, deduction = 20.0))
        ledger.observe(0, 0, 1, viewing = false, resting = false, assessment = null)
        ledger.observe(1000, 1000, 1, viewing = false, resting = false, assessment = null)
        ledger.observe(60_000, 60_000, 1, viewing = true, resting = false, assessment = StrainModel.assess(StrainModel.Inputs()))
        assertEquals(20.0, ledger.state().deduction, 1e-9)   // the 59 s gap is skipped
        ledger.recoverGap(10 * 60_000L, 70_000, 1)
        assertEquals(20.0 - 12.5, ledger.state().deduction, 1e-6)
        val nextDay = ledger.observe(80_000, 80_000, 2, viewing = false, resting = false, assessment = null)
        assertEquals(100, nextDay.condition)
    }
}
