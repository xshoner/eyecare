package com.neurolens.eyecare.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Research hypothesis for *observed burden per active minute*. The nominal 26/22/8/18/26
 * shares are fixed. Missing domains do not enter the rate and are never used to renormalize it.
 * Time is applied exactly once by [ObservedBurdenLedger], outside this scorer.
 */
object MvpScorer {
    const val VERSION = "efs-exposure-0.11"
    const val EXPOSURE_010_VERSION = "efs-exposure-0.10"
    const val PREVIOUS_VERSION = "efs-hypothesis-0.7"
    const val LEGACY_VERSION = "efs-hypothesis-0.5"
    const val SCENARIO_CAP = 8 // Maximum *rate* points, not an extra 8 points on the final score.
    val WEIGHTS = linkedMapOf("screen" to 26, "environment" to 22, "posture" to 8,
        "usage" to 18, "eyes" to 26)

    fun sentence(score: Int): String = when (score.coerceIn(0, 100)) {
        in 0..24 -> "관측된 화면 노출 부담이 낮은 구간입니다. 빠진 관측과 본인 증상은 따로 확인하세요."
        in 25..49 -> "관측된 화면 노출 부담이 쌓이고 있습니다. 잠깐 눈을 쉬게 해 주세요."
        in 50..74 -> "관측된 화면 노출 부담이 높아졌습니다. 쉬는 시간을 고려해 주세요."
        else -> "관측된 화면 노출 부담이 매우 높습니다. 화면 사용을 멈추고 쉬어 주세요."
    }

    private data class Cue(val id: String, val risk: Double, val quality: Double)
    private data class Domain(val risk: Double, val quality: Double, val ids: List<String>)
    private fun ramp(x: Double, low: Double, high: Double) =
        ((x - low) / (high - low) * 100.0).coerceIn(0.0, 100.0)
    private fun cue(signals: Map<String, Signal>, id: String, minQuality: Double = .30,
        risk: (Double) -> Double): Cue? {
        val signal = signals[id] ?: return null
        val value = signal.value?.takeIf { it.isFinite() } ?: return null
        if (signal.quality < minQuality) return null
        val normalized = risk(value).takeIf { it.isFinite() } ?: return null
        return Cue(id, normalized.coerceIn(0.0, 100.0), signal.quality.coerceIn(0.0, 1.0))
    }
    private fun strongest(vararg cues: Cue?): Cue? = cues.filterNotNull().maxByOrNull { it.risk }
    private fun domain(vararg families: Cue?): Domain? {
        val observed = families.filterNotNull()
        if (observed.isEmpty()) return null
        // A normal correlated cue cannot dilute a stronger observed cue.
        return Domain(observed.maxOf { it.risk }, observed.minOf { it.quality }, observed.map { it.id })
    }

    fun score(signals: Map<String, Signal>, baseline: PersonalBaseline = PersonalBaseline()): MvpResult {
        val motion = strongest(
            cue(signals, "S06.change") { ramp(it, .02, .12) },
            cue(signals, "S07.changedArea") { ramp(it, .02, .50) },
            cue(signals, "S08.largeRatio") { it * 100 },
            cue(signals, "S09.flashPerMin") { ramp(it, 0.0, 6.0) },
            cue(signals, "S10.scenePerMin") { ramp(it, 0.0, 12.0) },
            cue(signals, "S11.scrollPxPerSec") { ramp(it, 0.0, 150.0) })
        val screen = domain(motion)
        // Thermal state is a measurement availability/safety condition, not a separate eye dose.
        // Lux alone is still an observed context cue when relative brightness is absent.
        // The joint cue supersedes it; the two are never added together.
        val environment = domain(strongest(
            cue(signals, "E08.darkBrightRisk") { it },
            cue(signals, "E01.lux") { x ->
                if (x in 0.0..200_000.0) (50.0 - x).coerceIn(0.0, 50.0) else Double.NaN
            }))
        val posture = domain(strongest(
            cue(signals, "M01.tiltDeg") { ramp(abs(it), 15.0, 70.0) },
            cue(signals, "M02.shake") { ramp(it, .2, 3.0) },
            cue(signals, "F12.headMotion") { ramp(it, .02, .25) }))
        // Active observed time is the usage contribution (60/100 of this domain per minute).
        // Near distance adds at most the other 40/100 within the SAME 18% domain. Its
        // already-composite quality includes coverage and must not be multiplied again.
        val active = cue(signals, "U01.continuousMin", .5) { 60.0 }
        val near = cue(signals, "D02.nearExposureRisk", .35) { it }
        val usage = if (active != null || near != null) Domain(
            (if (active != null) 60.0 else 0.0) + (near?.risk ?: 0.0) * .40,
            listOfNotNull(active?.quality, near?.quality).minOrNull() ?: 0.0,
            listOfNotNull(active?.id, near?.id)) else null
        val blink = strongest(
            cue(signals, "F02.blinksPerMin", .35) { x ->
                if (baseline.blink.ready) ramp(abs(x - baseline.blink.mean!!), 2.0, 15.0)
                else ramp(abs(x - 17.0), 5.0, 22.0)
            },
            cue(signals, "F03.longestIbiSec", .35) { ramp(it, 4.0, 25.0) },
            cue(signals, "F05.incompleteRatio", .35) { ramp(it, .05, .55) })
        val aperture = strongest(
            cue(signals, "F06.openMean", .35) { x -> if (baseline.open.ready)
                ramp((baseline.open.mean!! - x) / baseline.open.mean!!.coerceAtLeast(.01), .05, .45)
                else 100 - ramp(x, .08, .30) },
            cue(signals, "F07.closure80", .35) { ramp(it, .05, .45) },
            cue(signals, "F09.squint", .35) { ramp(it, .10, .65) })
        // One valid family can contribute a partial eye estimate; a yawn alone is excluded.
        val eyes = domain(blink, aperture)
        val observations = linkedMapOf("screen" to screen, "environment" to environment,
            "posture" to posture, "usage" to usage, "eyes" to eyes)
        val domains = observations.mapNotNull { (name, value) -> value?.let { name to it.risk.roundToInt() } }.toMap()
        val qualities = observations.mapNotNull { (name, value) -> value?.let { name to it.quality } }.toMap()
        val contributions = observations.mapNotNull { (name, value) -> value?.let {
            name to WEIGHTS.getValue(name) * it.risk / 100.0
        } }.toMap()
        val mainRate = contributions.values.sum() / 100.0
        // E08 already represents dark room + relative OS brightness. This cross-term is
        // the *joint* screen-motion condition, not another standalone darkness penalty.
        // Duration is not multiplied here: the ledger integrates the rate over observed time.
        val interactionRate = if (environment != null && motion != null && active != null)
            .08 * environment.risk / 100.0 * motion.risk / 100.0 else 0.0
        val available = observations.filterValues { it != null }.keys.sumOf { WEIGHTS.getValue(it) }
        val capture = signals["S17.captureActive"]?.value == 1.0
        val timeCoverage = signals["S18.timeCoverage"]?.value ?: 0.0
        val scoreAvailable = observations.values.any { it != null }
        val sufficient = scoreAvailable && capture && timeCoverage >= .30 &&
            screen != null && usage != null && eyes != null && environment != null &&
            available >= 90 && timeCoverage >= .50 &&
            (signals["F01.coverage"]?.value ?: 0.0) >= .50
        val inputs = observations.values.filterNotNull().flatMap { it.ids }.distinct()
        val adjustments = if (interactionRate > 0) listOf(ScenarioAdjustment(
            "dark_screen_motion_rate", (interactionRate * 100).roundToInt(),
            listOf("E08.darkBrightRisk", motion?.id ?: "S06.change"),
            "어두움·상대 밝기와 화면 변화가 함께 관측된 분당 부담률(최대 8%p); 시간 배수 없음"))
            else emptyList()
        return MvpResult(score = ((mainRate + interactionRate) * 100).roundToInt().coerceIn(0, 100),
            confidence = if (sufficient) "보통 · 연구용" else "낮음 · 부분 관측",
            domains = domains, signals = signals, inputs = inputs, baselineSamples = baseline.samples,
            baseScore = (mainRate * 100).roundToInt(), nominalWeights = WEIGHTS,
            contributions = contributions, availableWeight = available,
            domainQualities = qualities, adjustments = adjustments, scoreAvailable = scoreAvailable,
            burdenRate = mainRate + interactionRate, interactionRate = interactionRate,
            note = "연구 가설: 영역 부담률(26/22/8/18/26)과 최대 8%p의 어두움×화면변화 교호작용을 실제 관측 시간에 한 번만 적분. 결측은 낮은 부담이나 건강함이 아니며, 계수·척도는 임상 검증값이 아닙니다.")
    }
}
