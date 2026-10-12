package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class EyeSignalLowLightTest {
    private val dark = CameraFrame(gain = 4.0, landmarkQuality = .5, rawLumaP50 = .08,
        ev100 = 1.5, lowLight = true, aeFpsMin = 15)

    @Test fun cameraConditionsAreRecordedEvenWithoutEyeGeometry() {
        val engine = EyeSignalEngine()
        (1..30).forEach { engine.add(it * 50L, null, null, emptyMap(), status = "low_light_no_face", camera = dark) }
        val s = engine.snapshot(1_500L)
        assertEquals(EyeSignalEngine.LOW_LIGHT_FACE_NOT_FOUND, s.getValue("F06.openMean").reason)
        assertEquals(EyeSignalEngine.LOW_LIGHT_FACE_NOT_FOUND, s.getValue("F02.blinksPerMin").reason)
        assertEquals(20.0, s.getValue("C01.cameraFps").value!!, 1e-9)
        assertEquals(4.0, s.getValue("C02.lowLightGain").value!!, 0.0)
        assertEquals(1.0, s.getValue("C03.enhancedShare").value!!, 0.0)
        assertEquals(1.5, s.getValue("C04.cameraEv100").value!!, 0.0)
        assertEquals(1.0, s.getValue("C05.lowLightShare").value!!, 0.0)
        assertEquals(.08, s.getValue("C06.rawLumaP50").value!!, 1e-9)
        assertEquals(15.0, s.getValue("C07.aeTargetFpsMin").value!!, 0.0)
        assertEquals(EyeSignalEngine.METRICS.toSet(), s.keys)
    }

    @Test fun brightFaceWithoutMetadataKeepsCameraSignalsMissingNotZero() {
        val engine = EyeSignalEngine()
        (1..30).forEach { engine.add(it * 50L, null, null, emptyMap(), status = "no_face") }
        val s = engine.snapshot(1_500L)
        assertEquals("eye_geometry_unavailable", s.getValue("F06.openMean").reason)
        assertEquals("camera_exposure_metadata_unavailable", s.getValue("C04.cameraEv100").reason)
        assertNull(s.getValue("C04.cameraEv100").value)
        assertEquals("raw_luma_unavailable", s.getValue("C05.lowLightShare").reason)
        assertEquals("fps_range_unavailable", s.getValue("C07.aeTargetFpsMin").reason)
        assertEquals(1.0, s.getValue("C02.lowLightGain").value!!, 0.0)
    }

    @Test fun enhancedFramesStillMeasureEyesWithLowerQuality() {
        val plain = EyeSignalEngine(); val enhanced = EyeSignalEngine()
        // A full minute of dense samples so quality is not clamped at its 0.05 floor.
        (1..600).forEach {
            plain.add(it * 100L, .2, .2, emptyMap())
            enhanced.add(it * 100L, .2, .2, emptyMap(), camera = dark)
        }
        val a = plain.snapshot(60_000L).getValue("F06.openMean")
        val b = enhanced.snapshot(60_000L).getValue("F06.openMean")
        assertNotNull(b.value)
        assertEquals(a.quality * .5, b.quality, 1e-9)
    }

    @Test fun cameraUnavailableReasonPropagatesToCameraSignals() {
        val s = EyeSignalEngine().snapshot(1_000L, "thermal_paused")
        assertEquals("thermal_paused", s.getValue("C01.cameraFps").reason)
    }
}
