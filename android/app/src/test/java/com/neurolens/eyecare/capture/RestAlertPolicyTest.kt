package com.neurolens.eyecare.capture

import org.junit.Assert.*
import org.junit.Test

class RestAlertPolicyTest {
    @Test fun requiresValidContinuousHighScoreAndRespectsSnoozeAndCooldown() {
        val p = RestAlertPolicy()
        assertFalse(p.evaluate(0, 55, false, true, 2, 20))
        assertFalse(p.evaluate(1000, 55, true, true, 2, 20))
        assertFalse(p.evaluate(120_000, 55, true, true, 2, 20))
        assertTrue(p.evaluate(121_000, 55, true, true, 2, 20))
        assertFalse(p.evaluate(300_000, 55, true, true, 2, 20))
        p.snooze(300_000)
        assertFalse(p.evaluate(899_000, 55, true, true, 2, 20))
        assertTrue(p.evaluate(900_000, 55, true, true, 2, 20))
        p.dismiss(901_000, 20)
        assertFalse(p.evaluate(1_000_000, 55, true, true, 2, 20))
    }

    @Test fun lowScoreAndInactiveSessionResetAccumulation() {
        val p = RestAlertPolicy()
        p.evaluate(0, 55, true, true, 2, 20)
        assertFalse(p.evaluate(60_000, 40, true, true, 2, 20))
        assertFalse(p.evaluate(121_000, 55, true, true, 2, 20))
        assertFalse(p.evaluate(200_000, 55, true, false, 2, 20))
        assertFalse(p.evaluate(260_000, 55, true, true, 2, 20))
    }
}
