package com.neurolens.eyecare.capture

data class QualityInput(val cameraState: String, val observationStatus: String?,
    val faceIllumination: Double?, val distanceReason: String?, val eyeCoverage: Double?,
    val screenCoverage: Double?, val deliveredFps: Double?, val sessionElapsedMs: Long,
    val thermalPaused: Boolean, val screenOn: Boolean)

data class QualityAdvice(val code: String, val action: String)

/** UI-only guidance. It never turns missing evidence into a valid score. */
class QualityGuide {
    private var candidate: String? = null
    private var candidateSince = 0L
    private var shown: String? = null
    private var clearSince = 0L

    fun update(nowMs: Long, input: QualityInput): QualityAdvice? {
        val wanted = classify(input)
        if (wanted == null) {
            candidate = null
            if (shown != null) {
                if (clearSince == 0L) clearSince = nowMs
                if (nowMs - clearSince >= 3_000) shown = null
            }
        } else {
            clearSince = 0L
            if (candidate != wanted) { candidate = wanted; candidateSince = nowMs }
            if (wanted == "THERMAL_PAUSED" || wanted == "CAMERA_PERMISSION" ||
                nowMs - candidateSince >= 5_000) shown = wanted
        }
        return shown?.let { QualityAdvice(it, actions.getValue(it)) }
    }

    private fun classify(i: QualityInput): String? {
        if (i.thermalPaused) return "THERMAL_PAUSED"
        if (!i.screenOn || i.cameraState in setOf("not_started", "opening", "camera_paused")) return null
        if (i.cameraState == "permission_missing") return "CAMERA_PERMISSION"
        if (i.cameraState.startsWith("camera_error_") || i.cameraState in setOf(
                "camera_open_failed", "camera_stream_failed", "camera_config_failed", "camera_disconnected"))
            return "CAMERA_BUSY"
        if (i.sessionElapsedMs < 5_000) return null
        if (i.observationStatus == FaceEyeExtractor.LOW_LIGHT_NO_FACE) return "LOW_LIGHT"
        if (i.observationStatus == "no_face") return "NO_FACE"
        if (i.faceIllumination != null && i.faceIllumination < .08) return "DARK_FACE"
        if (i.observationStatus in setOf("eye_geometry_invalid", "eye_closed_or_iris_missing") ||
            i.distanceReason == "iris_too_small_or_occluded") return "EYE_OCCLUDED"
        if (i.distanceReason in setOf("camera_geometry_unavailable", "camera_geometry_missing"))
            return "GEOMETRY_UNSUPPORTED"
        if (i.distanceReason == "camera_geometry_or_calibration_needed") return "CALIBRATION_NEEDED"
        if (shown == "LOW_SAMPLE_RATE" && i.deliveredFps != null && i.deliveredFps < 8.0)
            return "LOW_SAMPLE_RATE"
        if (shown == "LOW_EYE_COVERAGE" && i.eyeCoverage != null && i.eyeCoverage < .40)
            return "LOW_EYE_COVERAGE"
        if (shown == "LOW_SCREEN_COVERAGE" && i.screenCoverage != null && i.screenCoverage < .40)
            return "LOW_SCREEN_COVERAGE"
        if (i.sessionElapsedMs >= 10_000 && i.deliveredFps != null && i.deliveredFps in 0.0..6.99)
            return "LOW_SAMPLE_RATE"
        if (i.sessionElapsedMs >= 10_000 && i.eyeCoverage != null && i.eyeCoverage < .30)
            return "LOW_EYE_COVERAGE"
        if (i.sessionElapsedMs >= 10_000 && i.screenCoverage != null && i.screenCoverage < .30)
            return "LOW_SCREEN_COVERAGE"
        if (i.sessionElapsedMs >= 10_000 && i.distanceReason == null &&
            i.eyeCoverage != null && i.eyeCoverage >= .30 &&
            i.screenCoverage != null && i.screenCoverage >= .30) return null
        return null
    }

    companion object {
        private val actions = mapOf(
            "THERMAL_PAUSED" to "폰이 뜨거워 잠시 멈췄어요. 조금 식으면 이어서 살펴볼게요.",
            "CAMERA_PERMISSION" to "카메라를 허용하면 보는 거리와 깜빡임도 함께 살펴볼 수 있어요.",
            "CAMERA_BUSY" to "다른 앱이 카메라를 쓰고 있어요. 그 앱을 닫으면 다시 연결할게요.",
            "LOW_LIGHT" to "주변이 어두워 눈을 찾기 어려워요. 화면 밝기를 조금 올리거나 얼굴 쪽에 약한 조명을 켜 주세요.",
            "NO_FACE" to "얼굴이 전면 카메라에 보이도록 폰을 조금 세워 주세요.",
            "DARK_FACE" to "얼굴이 어둡게 보여요. 얼굴 쪽을 밝게 하고 뒤쪽의 강한 빛은 피해 주세요.",
            "EYE_OCCLUDED" to "눈이 가려져 있어요. 머리카락이나 손이 눈을 가리지 않게 해 주세요.",
            "GEOMETRY_UNSUPPORTED" to "이 폰에서는 거리를 잴 수 없어요. 다른 항목은 계속 살펴볼게요.",
            "CALIBRATION_NEEDED" to "아래 '거리 맞추기'로 30cm를 한 번 맞추면 거리가 더 정확해져요.",
            "LOW_SAMPLE_RATE" to "폰이 바빠 깜빡임을 놓치고 있어요. 무거운 앱을 잠시 닫아 주세요.",
            "LOW_EYE_COVERAGE" to "눈이 잘 보이지 않는 시간이 길어요. 얼굴을 카메라 쪽으로 향해 주세요.",
            "LOW_SCREEN_COVERAGE" to "화면을 충분히 살펴보지 못하고 있어요. 화면 공유가 켜져 있는지 확인해 주세요."
        )
    }
}
