package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.Signal
import org.junit.Assert.*
import org.junit.Test

class ObservedContextSummaryTest {
    @Test fun partialObservationShowsIncludedAndMissingInputs() {
        val result = ObservedContext.fromSignals(mapOf(
            "E01.lux" to Signal.observed(1.0),
            "U01.continuousMin" to Signal.observed(56.44),
            "S17.captureActive" to Signal.missing("capture_stopped")),
            mapOf("environment" to 49, "usage" to 60), true, 38, 24.5,
            listOf("E01.lux", "U01.continuousMin"))
        assertTrue(result.facts.contains("1 lx"))
        assertTrue(result.facts.contains("56분"))
        assertTrue(result.environment.contains("49/100"))
        assertTrue(result.included.contains("E01.lux"))
        assertTrue(result.missing.contains("화면 변화"))
        assertTrue(result.missing.contains("눈 지표"))
        assertTrue(result.accumulated.contains("누적 부담 38/100"))
        assertTrue(result.accumulated.contains("눈 컨디션 62/100"))
        assertTrue(result.accumulated.contains("24.5분"))
    }

    @Test fun absentInputIsNeverReportedAsZeroOrCurrent() {
        val result = ObservedContext.fromSignals(mapOf(
            "E01.lux" to Signal.missing("stale_sensor_sample"),
            "U01.continuousMin" to Signal.observed(40.0)),
            mapOf("usage" to 60), true, null, 0.0)
        assertTrue(result.facts.contains("조도 입력 없음"))
        assertFalse(result.facts.contains("0 lx"))
        assertTrue(result.missing.contains("주변 조도"))
        assertTrue(result.accumulated.contains("아직"))
    }

    @Test fun trueCaptureAndEyeGapsHaveSpecificMissingReasons() {
        val result = ObservedContext.fromSignals(mapOf(
            "U01.continuousMin" to Signal.observed(3.0),
            "S17.captureActive" to Signal.missing("stale_screen_sample"),
            "F06.openMean" to Signal.missing("stale_eye_geometry")),
            mapOf("usage" to 60), true, 4, 3.0)
        assertTrue(result.missing.contains("성공 화면 프레임 없음"))
        assertTrue(result.missing.contains("최근 유효 눈 표본 없음"))
        assertTrue(result.accumulated.contains("4/100"))
    }
}
