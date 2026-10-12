package com.neurolens.eyecare.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityGuideTest {
    private fun input(status: String? = "observed", eye: Double? = .8,
        thermal: Boolean = false) = QualityInput("observing", status, .5, null,
        eye, .8, 10.0, 20_000, thermal, true)

    @Test fun faceWarningDwellsAndClearsWithoutFlicker() {
        val guide = QualityGuide()
        assertNull(guide.update(20_000, input("no_face")))
        assertNull(guide.update(24_000, input("no_face")))
        assertEquals("NO_FACE", guide.update(25_000, input("no_face"))?.code)
        assertEquals("NO_FACE", guide.update(26_000, input())?.code)
        assertNull(guide.update(29_000, input()))
    }

    @Test fun lowCoverageUsesThirtyPercentEntryAndFortyPercentExit() {
        val guide = QualityGuide()
        guide.update(20_000, input(eye = .25))
        assertEquals("LOW_EYE_COVERAGE", guide.update(25_000, input(eye = .25))?.code)
        assertEquals("LOW_EYE_COVERAGE", guide.update(28_000, input(eye = .35))?.code)
        assertEquals("LOW_EYE_COVERAGE", guide.update(29_000, input(eye = .45))?.code)
        assertNull(guide.update(32_000, input(eye = .45)))
    }

    @Test fun lowLightFaceLossGetsSpecificCoaching() {
        val guide = QualityGuide()
        assertNull(guide.update(20_000, input(FaceEyeExtractor.LOW_LIGHT_NO_FACE)))
        assertEquals("LOW_LIGHT", guide.update(25_000, input(FaceEyeExtractor.LOW_LIGHT_NO_FACE))?.code)
    }

    @Test fun thermalPauseIsImmediate() {
        assertEquals("THERMAL_PAUSED", QualityGuide().update(1_000, input(thermal = true))?.code)
    }
}
