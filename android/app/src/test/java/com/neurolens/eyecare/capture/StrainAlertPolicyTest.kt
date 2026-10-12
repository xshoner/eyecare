package com.neurolens.eyecare.capture

import com.neurolens.eyecare.engine.StrainModel
import org.junit.Assert.*
import org.junit.Test

class StrainAlertPolicyTest {
    private val calm = StrainModel.assess(StrainModel.Inputs(lux = 300.0, distanceCm = 40.0))
    private val darkNear = StrainModel.assess(StrainModel.Inputs(lux = 3.0, distanceCm = 24.0, screenChange = .05))
    private val darkNearIntense = StrainModel.assess(StrainModel.Inputs(lux = 3.0, distanceCm = 24.0, screenChange = .15))
    private fun tick(now: Long, condition: Int, a: StrainModel.Assessment, continuous: Double, distance: Double? = 40.0) =
        StrainAlertPolicy.Tick(now, condition, a, continuous, distance, 0.0, cooldownMinutes = 20, sustainMinutes = 2)

    @Test fun breakIntervalShortensWithRisk() {
        val p = StrainAlertPolicy()
        assertEquals(20, p.breakInterval(calm))
        assertEquals(15, p.breakInterval(StrainModel.assess(StrainModel.Inputs(lux = 3.0))))
        assertEquals(10, p.breakInterval(darkNear))
        assertEquals(7, p.breakInterval(darkNearIntense))
    }

    @Test fun breakReminderComesSoonerInTheDarkCloseUp() {
        val normal = StrainAlertPolicy()
        assertNull(normal.evaluate(tick(0, 90, calm, 12.0)))
        assertEquals(StrainAlertPolicy.Kind.BREAK, normal.evaluate(tick(1, 90, calm, 20.0))?.kind)
        val risky = StrainAlertPolicy()
        assertEquals(StrainAlertPolicy.Kind.BREAK, risky.evaluate(tick(0, 90, darkNearIntense, 7.0, 24.0))?.kind)
    }

    @Test fun levelAlertsNeedTheConditionToHoldAndRespectTheGap() {
        val p = StrainAlertPolicy()
        assertNull(p.evaluate(tick(0, 35, calm, 1.0)))
        assertNull(p.evaluate(tick(60_000, 35, calm, 1.0)))
        assertEquals(StrainAlertPolicy.Kind.CAUTION, p.evaluate(tick(120_000, 35, calm, 1.0))?.kind)
        assertNull(p.evaluate(tick(130_000, 35, calm, 1.0)))     // 3-minute minimum gap
        // Risk is never held back by the gap once it has lasted the sustain time.
        assertNull(p.evaluate(tick(140_000, 15, calm, 1.0)))
        assertEquals(StrainAlertPolicy.Kind.RISK, p.evaluate(tick(260_000, 15, calm, 1.0))?.kind)
    }

    @Test fun forecastWarnsBeforeCrossingIntoCaution() {
        val p = StrainAlertPolicy()
        val fast = StrainModel.assess(StrainModel.Inputs(lux = 3.0, distanceCm = 22.0, screenChange = .15, darkMinutes = 30.0))
        assertTrue(fast.totalRate >= .8)
        assertEquals(4, p.minutesToNextBand(43, 1.0))
        val alert = p.evaluate(tick(0, 44, fast, 2.0, 22.0))
        assertEquals(StrainAlertPolicy.Kind.FORECAST, alert?.kind)
        assertTrue(alert!!.title.contains("주의"))
        assertNull(p.evaluate(tick(10 * 60_000, 44, fast, 2.0, 22.0))?.takeIf { it.kind == StrainAlertPolicy.Kind.FORECAST })
    }

    @Test fun snoozeSilencesEverythingButRisk() {
        val p = StrainAlertPolicy()
        p.snooze(0)
        assertNull(p.evaluate(tick(1_000, 90, calm, 25.0)))
    }
}
