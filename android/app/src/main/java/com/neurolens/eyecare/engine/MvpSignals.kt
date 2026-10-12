package com.neurolens.eyecare.engine

import kotlin.math.*

/** A missing signal stays missing; quality controls its influence without erasing the observation. */
data class Signal(val value: Double? = null, val quality: Double = 0.0,
    val reason: String? = null, val estimated: Boolean = false) {
    companion object {
        fun observed(value: Double, quality: Double = 1.0, estimated: Boolean = false) =
            if (value.isFinite()) Signal(value, quality.coerceIn(0.05, 1.0), estimated = estimated) else missing("non_finite")
        fun missing(reason: String) = Signal(reason = reason)
    }
}

data class ScenarioAdjustment(val id: String, val points: Int, val evidence: List<String>,
    val rationale: String, val estimated: Boolean = true)

data class MvpResult(
    val score: Int, val confidence: String, val domains: Map<String, Int>,
    val signals: Map<String, Signal>, val inputs: List<String>, val baselineSamples: Int,
    val baseScore: Int = 0, val nominalWeights: Map<String, Int> = emptyMap(),
    val contributions: Map<String, Double> = emptyMap(), val availableWeight: Int = 0,
    val domainQualities: Map<String, Double> = emptyMap(),
    val adjustments: List<ScenarioAdjustment> = emptyList(), val scoreAvailable: Boolean = false,
    /** Confirmed local exposure accumulated in the current calendar day, never a health verdict. */
    val observedBurden: Int? = null, val observedMinutes: Double = 0.0,
    /** Burden units per active observed minute; quality is reported separately, not subtracted. */
    val burdenRate: Double = 0.0, val interactionRate: Double = 0.0,
    val note: String = "연구용 잠정 추정치 · 의학적 진단이 아닙니다"
)

/** Each metric has its own valid count and quality weight; missing reports do not dilute it. */
data class BaselineMetric(val mean: Double? = null, val count: Int = 0, val qualitySum: Double = 0.0) {
    val ready: Boolean get() = mean != null && count >= 3 && qualitySum >= 2.4

    fun updated(signal: Signal?): BaselineMetric {
        val value = signal?.value ?: return this
        if (!value.isFinite() || signal.quality <= 0.0) return this
        val weight = signal.quality.coerceIn(0.0, 1.0)
        val nextWeight = qualitySum + weight
        return BaselineMetric(if (mean == null) value else mean + (value - mean) * weight / nextWeight,
            count + 1, nextWeight)
    }
}

data class PersonalBaseline(
    val samples: Int = 0,
    val open: BaselineMetric = BaselineMetric(),
    val blink: BaselineMetric = BaselineMetric(),
    val usage: BaselineMetric = BaselineMetric(),
    val switches: BaselineMetric = BaselineMetric(),
    val unlocks: BaselineMetric = BaselineMetric(),
    val microBreaks: BaselineMetric = BaselineMetric(),
    val score: BaselineMetric = BaselineMetric()
) {
    fun updated(open: Signal?, blink: Signal?, usage: Signal?, switches: Signal?, unlocks: Signal?,
        microBreaks: Signal?, score: Signal?): PersonalBaseline = copy(
        samples = samples + 1, open = this.open.updated(open), blink = this.blink.updated(blink),
        usage = this.usage.updated(usage), switches = this.switches.updated(switches),
        unlocks = this.unlocks.updated(unlocks), microBreaks = this.microBreaks.updated(microBreaks),
        score = this.score.updated(score))
}


