package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.engine.Signal

/** Evidence disclosure for a partial estimate and the independent observed-time ledger. */
data class ObservedContextSummary(
    val facts: String,
    val environment: String,
    val missing: String,
    val accumulated: String,
    val included: String = ""
)

object ObservedContext {
    private val labels = linkedMapOf("screen" to "화면 변화", "environment" to "주변 조도",
        "posture" to "자세·흔들림", "usage" to "사용 시간·근거리", "eyes" to "눈 지표")

    fun fromReport(report: CareReport): ObservedContextSummary = fromSignals(
        report.mvpSignals, report.mvpDomains, report.scoreAvailable,
        report.observedBurden, report.observedMinutes, report.inputs)

    fun fromSignals(signals: Map<String, Signal>, domains: Map<String, Int>,
        scoreAvailable: Boolean, observedBurden: Int?, observedMinutes: Double,
        inputs: List<String> = emptyList()): ObservedContextSummary {
        fun measured(id: String, minimumQuality: Double): Double? = signals[id]
            ?.takeIf { it.quality >= minimumQuality }?.value?.takeIf { it.isFinite() }
        val lux = measured("E01.lux", .30)?.takeIf { it in 0.0..200_000.0 }
        val minutes = measured("U01.continuousMin", .50)?.takeIf { it in 0.0..1_440.0 }
        val time = minutes?.let { if (it < 1.0) "1분 미만" else "${it.toInt()}분" }
        val facts = when {
            lux != null && time != null -> "최근 조도 ${lux.toInt()} lx · 연속 사용 $time 관측"
            lux != null -> "최근 조도 ${lux.toInt()} lx 관측 · 사용 시간 입력 없음"
            time != null -> "연속 사용 $time 관측 · 조도 입력 없음"
            else -> "조도와 연속 사용 시간은 현재 확인되지 않음"
        }
        val environment = if (domains.containsKey("environment"))
            "조도 맥락의 현재 기여 ${domains.getValue("environment")}/100 (절대 조도 또는 조도·밝기 추정)"
        else "조도 맥락 입력 없음"
        val used = labels.filterKeys { domains.containsKey(it) }.values
        val absent = labels.filterKeys { !domains.containsKey(it) }.values
        val included = "실제 반영 영역: ${used.joinToString(" · ").ifEmpty { "없음" }}" +
            if (inputs.isEmpty()) "" else " (${inputs.joinToString(", ")})"
        val screenReason = signals["S17.captureActive"]?.reason ?: signals["S06.change"]?.reason
        val eyeReason = signals["F06.openMean"]?.reason
        val detail = listOfNotNull(
            if ("screen" in domains) null else when (screenReason) {
                "stale_screen_sample" -> "최근 10초 성공 화면 프레임 없음"
                "no_frame_pair" -> "비교 가능한 화면 프레임 쌍 부족"
                "thermal_paused" -> "열 상태로 화면 관측 중지"
                else -> null
            },
            if ("eyes" in domains) null else when (eyeReason) {
                "stale_eye_sample", "stale_eye_geometry" -> "최근 유효 눈 표본 없음"
                "eye_geometry_unavailable", "insufficient_eye_observation" -> "눈 표본 부족"
                "thermal_paused" -> "열 상태로 눈 관측 중지"
                else -> null
            }).joinToString(" · ")
        val missing = if (absent.isEmpty()) "누락 영역 없음"
            else "현재 빠진 영역: ${absent.joinToString(" · ")}. 점수에 0으로 대입하지 않음." +
                if (detail.isEmpty()) "" else " 원인: $detail."
        val accumulated = if (observedBurden != null && observedMinutes > 0.0 && observedMinutes.isFinite())
            "오늘 실제 유효 입력 관측 ${"%.1f".format(observedMinutes)}분 기준 오늘의 눈 컨디션 ${EyeCondition.fromBurden(observedBurden)}/100 (누적 부담 $observedBurden/100). 사용할수록 낮아지며, 입력 누락 시 이전 부담을 지우지 않습니다."
        else "오늘의 눈 컨디션은 아직 관측 시간이 부족해 표시하지 않습니다. 현재 부분 추정은 누적량이나 건강 판정이 아닙니다."
        return ObservedContextSummary(facts, environment, missing, accumulated, included)
    }
}
