package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class InputValidityTest {
    @Test fun stableOnChangeValueRemainsCurrentOnlyWithinItsRegistration() {
        assertTrue(ObservationFreshness.sensorCurrent(600_000, 1_000, true, true))
        assertFalse(ObservationFreshness.sensorCurrent(600_000, 1_000, false, true))
        assertFalse(ObservationFreshness.sensorCurrent(600_000, 1_000, true, true, reliable = false))
        assertFalse(ObservationFreshness.sensorCurrent(600_000, -1, true, true))
        assertFalse(ObservationFreshness.sensorCurrent(1_000, 2_000, true, true))
        assertTrue(ObservationFreshness.sensorCurrent(11_000, 1_000, true, false))
        assertFalse(ObservationFreshness.sensorCurrent(11_001, 1_000, true, false))
    }

    @Test fun delayedPriorRegistrationEventCannotBecomeASecondInitialSample() {
        assertFalse(ObservationFreshness.belongsToRegistration(9_999, 10_000, 11_000))
        assertTrue(ObservationFreshness.belongsToRegistration(10_000, 10_000, 11_000))
        assertFalse(ObservationFreshness.belongsToRegistration(12_000, 10_000, 11_000))
        assertFalse(ObservationFreshness.belongsToRegistration(10_000, -1, 11_000))
    }

    @Test fun zeroLuxIsValidButInvalidOrAbsentSensorIsNotAHealthyZero() {
        val dark = MvpScorer.score(mapOf("E01.lux" to Signal.observed(0.0)))
        assertTrue(dark.scoreAvailable)
        assertEquals(11, dark.score)
        assertEquals(setOf("environment"), dark.domains.keys)
        assertFalse(MvpScorer.score(mapOf("E01.lux" to Signal.observed(Double.NaN))).scoreAvailable)
        assertFalse(MvpScorer.score(mapOf("E01.lux" to Signal.missing("sensor_unreliable"))).scoreAvailable)
    }

    @Test fun invalidPixelsAndSparseOrThermalFramesStayExplicitlyMissing() {
        val screen = ScreenSignalEngine()
        assertFalse(screen.frame(IntArray(1), 8, 8, 1_000))
        assertEquals("no_capture", screen.snapshot(1_000).getValue("S06.change").reason)
        assertTrue(screen.frame(IntArray(64) { 0xFF808080.toInt() }, 8, 8, 1_000))
        assertEquals("no_frame_pair", screen.snapshot(1_000).getValue("S06.change").reason)
        assertEquals("thermal_paused", screen.snapshot(1_000, "thermal_paused")
            .getValue("S06.change").reason)
        screen.reset("screen_off")
        assertEquals("no_capture", screen.snapshot(1_001).getValue("S06.change").reason)
    }

    @Test fun freshCameraCallbacksWithoutFreshEyeGeometryDoNotReviveOldEyeValues() {
        val eyes = EyeSignalEngine()
        for (at in 1_000L..2_000L step 100L) eyes.add(at, .2, .2, emptyMap())
        eyes.add(20_000, null, null, emptyMap(), status = "no_face")
        assertEquals("stale_eye_geometry", eyes.snapshot(20_000)
            .getValue("F06.openMean").reason)
        assertEquals("thermal_paused", eyes.snapshot(20_000, "thermal_paused")
            .getValue("F06.openMean").reason)
        val none = EyeSignalEngine()
        none.add(1_000, null, null, emptyMap(), status = "no_face")
        assertEquals("eye_geometry_unavailable", none.snapshot(1_000)
            .getValue("F06.openMean").reason)
        eyes.reset()
        assertEquals("camera_unavailable", eyes.snapshot(20_001)
            .getValue("F06.openMean").reason)
    }
}
