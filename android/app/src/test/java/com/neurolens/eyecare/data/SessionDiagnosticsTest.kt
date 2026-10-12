package com.neurolens.eyecare.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDiagnosticsTest {
    @Test fun boundedAccumulatorUsesObservedIntervalsAndPreservesMissing() {
        val stats = SessionDiagnosticsAccumulator(1_000L)
        assertNull(stats.snapshot(2_000L, 0, 0.0, 0.0, null, "0.9", "engine").cameraFps)
        stats.camera(1_000L, true, 10.0)
        stats.camera(1_100L, true, 20.0)
        stats.camera(1_200L, false, 30.0)
        stats.distance(1_000L, true)
        stats.distance(1_200L, true)
        stats.thermal(1_000L, 1, false)
        stats.thermal(1_100L, 3, true)
        val result = stats.snapshot(1_500L, 2, 30.0, 20.0,
            ResearchUploadState(true, 2, 1, 1, null, false, "", "HTTP_503"), "0.9", "engine")
        assertEquals(10.0, result.cameraFps!!, .001)
        assertEquals(100.0, result.frameGapMeanMs!!, .001)
        assertEquals(20.0, result.cameraProcessingMeanMs!!, .001)
        assertEquals(.2, result.eyeCoverage!!, .001)
        assertEquals(.4, result.distanceCoverage!!, .001)
        assertEquals(400L, result.thermalPausedMs)
        assertEquals("HTTP_503", result.syncReasonCode)
        assertEquals(3, result.maxThermalStatus)
    }
}
