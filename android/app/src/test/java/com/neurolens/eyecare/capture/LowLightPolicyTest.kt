package com.neurolens.eyecare.capture

import org.junit.Assert.*
import org.junit.Test

class LowLightPolicyTest {
    @Test fun fpsRangeKeepsFifteenFloorAndPrefersThirtyCeiling() {
        val ranges = listOf(7 to 30, 15 to 15, 15 to 30, 24 to 24, 30 to 30, 30 to 60)
        assertEquals(15 to 30, LowLightPolicy.chooseFpsRange(ranges))
        assertEquals(30 to 30, LowLightPolicy.chooseFpsRange(listOf(30 to 30, 30 to 60, 8 to 30)))
        assertEquals(30 to 60, LowLightPolicy.chooseFpsRange(listOf(30 to 60, 8 to 30)))
    }

    @Test fun fpsRangeFallsBackToHighestFloorAndRejectsInvalid() {
        assertEquals(10 to 30, LowLightPolicy.chooseFpsRange(listOf(5 to 30, 10 to 30, 10 to 10)))
        assertNull(LowLightPolicy.chooseFpsRange(emptyList()))
        assertNull(LowLightPolicy.chooseFpsRange(listOf(0 to 30, 20 to 10)))
    }

    @Test fun lumaStatsRespectStridesAndSubsampling() {
        // 4x4 image with row padding 2 and pixel stride 1; every pixel is 10 except two 250s.
        val width = 4; val height = 4; val rowStride = 6
        val y = ByteArray(rowStride * height) { 10 }
        y[0] = 250.toByte(); y[1] = 250.toByte()
        val all = LowLightPolicy.lumaStats(y, width, height, rowStride, 1, step = 1)!!
        assertEquals(10, all.p50)
        assertEquals(250, all.p95)
        val sparse = LowLightPolicy.lumaStats(y, width, height, rowStride, 1, step = 2)!!
        assertEquals(10, sparse.p50) // samples (0,0),(0,2),(2,0),(2,2)
        assertNull(LowLightPolicy.lumaStats(y, 0, height, rowStride, 1))
        assertNull(LowLightPolicy.lumaStats(ByteArray(0), width, height, rowStride, 1))
    }

    @Test fun enhancementOnlyForDarkFramesAndCapped() {
        assertFalse(LowLightPolicy.enhancementFor(LumaStats(90, 200)).active)
        assertFalse(LowLightPolicy.enhancementFor(null).active)
        val mild = LowLightPolicy.enhancementFor(LumaStats(60, 120))
        assertEquals(100.0 / 60, mild.gain, 1e-9)
        assertEquals(1.0, mild.gamma, 0.0)
        assertFalse(mild.grayscale)
        val dark = LowLightPolicy.enhancementFor(LumaStats(10, 40))
        assertEquals(4.0, dark.gain, 0.0)
        assertEquals(.85, dark.gamma, 0.0)
        assertTrue(dark.grayscale)
        assertFalse(LowLightPolicy.enhancementFor(LumaStats(70, 120)).active)
    }

    @Test fun lutIsIdentityOrMonotoneAndClipped() {
        val identity = LowLightPolicy.lut(Enhancement())
        assertArrayEquals(IntArray(256) { it }, identity)
        val boosted = LowLightPolicy.lut(Enhancement(gain = 3.0, gamma = .85))
        for (i in 1..255) assertTrue(boosted[i] >= boosted[i - 1])
        assertEquals(0, boosted[0])
        assertEquals(255, boosted[255])
        assertTrue(boosted[30] > 30 * 3 - 1)
    }

    @Test fun enhancedLandmarksLoseQualityGradually() {
        assertEquals(1.0, LowLightPolicy.enhancementQuality(1.0), 0.0)
        assertEquals(1.0, LowLightPolicy.enhancementQuality(1.5), 0.0)
        assertEquals(.5, LowLightPolicy.enhancementQuality(4.0), 1e-9)
        assertEquals(.75, LowLightPolicy.enhancementQuality(2.75), 1e-9)
        assertEquals(1.0, LowLightPolicy.enhancementQuality(Double.NaN), 0.0)
    }

    @Test fun ev100FollowsExposureTriangle() {
        // f/2, 1/50 s, ISO 100 -> log2(4 * 50) = 7.64
        assertEquals(7.644, LowLightPolicy.ev100(2.0, 20_000_000L, 100)!!, .001)
        // Doubling ISO or a 2x digital boost lowers EV by one stop.
        assertEquals(6.644, LowLightPolicy.ev100(2.0, 20_000_000L, 200)!!, .001)
        assertEquals(6.644, LowLightPolicy.ev100(2.0, 20_000_000L, 100, 200)!!, .001)
        assertNull(LowLightPolicy.ev100(null, 20_000_000L, 100))
        assertNull(LowLightPolicy.ev100(2.0, 0L, 100))
        assertNull(LowLightPolicy.ev100(2.0, 20_000_000L, 0))
    }

    @Test fun cameraClockUsesPlausibleRealtimeSensorTime() {
        assertEquals(990L, CameraClock.frameTimeMs(990_000_000L, true, 1_000L))
        assertEquals(1_000L, CameraClock.frameTimeMs(990_000_000L, false, 1_000L))
        assertEquals(1_000L, CameraClock.frameTimeMs(1_500_000_000L, true, 1_000L)) // future
        assertEquals(5_000L, CameraClock.frameTimeMs(1_000_000_000L, true, 5_000L)) // > 1 s old
        assertEquals(1_000L, CameraClock.frameTimeMs(0L, true, 1_000L))
    }

    @Test fun exposureCompensationNeedsDwellAndHasHysteresis() {
        val c = ExposureCompensator(maxIndex = 6, stepsPerEv = 3)
        assertNull(c.update(0, 30))
        assertNull(c.update(900, 30))
        assertEquals(3, c.update(1_000, 30)) // dark for 1 s -> +1 EV
        assertNull(c.update(2_000, 30)) // dwell restarts after a change
        assertNull(c.update(2_400, 30)) // still inside the 1.5 s minimum interval
        assertEquals(6, c.update(3_100, 30))
        assertNull(c.update(5_000, 30)) // already at max
        assertNull(c.update(5_100, 90)) // in-band: hold
        assertNull(c.update(6_000, 130))
        assertNull(c.update(7_900, 130))
        assertEquals(3, c.update(8_000, 130)) // bright for 2 s -> -1 EV
        assertEquals(3, c.index)
        assertNull(c.update(8_100, null))
    }

    @Test fun exposureCompensationNeverDarkensBelowDefaultOrRunsWithoutRange() {
        val c = ExposureCompensator(maxIndex = 4, stepsPerEv = 2)
        assertNull(c.update(0, 200)); assertNull(c.update(5_000, 200))
        assertEquals(0, c.index)
        val none = ExposureCompensator(maxIndex = 0, stepsPerEv = 3)
        assertNull(none.update(0, 10)); assertNull(none.update(5_000, 10))
    }
}
