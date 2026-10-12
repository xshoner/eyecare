package com.neurolens.eyecare.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Deduction model `efs-deduction-0.15`. The eye condition starts at 100 each local day and loses
 * points per observed viewing minute for each active strain factor; observed rest restores points.
 * Rates escalate with time: continuous viewing raises the screen-time and long-viewing rates in steps,
 * near and dark viewing grow with their own continuous minutes, and the remaining factors double
 * between 20 and 60 continuous minutes. Every rate, threshold and multiplier below is a product
 * hypothesis to be calibrated against self-reported fatigue, not a clinical limit. A factor whose
 * inputs are missing deducts nothing beyond the screen-time base.
 */
object StrainModel {
    const val VERSION = "efs-deduction-0.15"
    /** 0.14 used flat time weights; its reports stay readable and comparable within the deduction family. */
    const val PREVIOUS_VERSION = "efs-deduction-0.14"
    val VERSIONS = setOf(VERSION, PREVIOUS_VERSION)
    fun isDeduction(version: String?) = version in VERSIONS
    /** Total deduction never exceeds this many points per minute, however many factors stack. */
    const val MAX_RATE = 4.0

    enum class Factor(val id: String, val label: String, val tip: String) {
        BASE("screen_time", "화면 시청", "20분마다 20초 먼 곳을 바라봐 주세요."),
        NEAR("near", "가까이 보기", "화면을 35cm 이상, 평소보다 조금 더 멀리 두세요."),
        DARK("dark", "어두운 곳에서 보기", "조명을 켜거나 화면 밝기를 낮춰 주세요."),
        FLASH("flash", "번쩍이는 화면", "빠르게 깜빡이는 영상은 잠시 멈추고 쉬어 주세요."),
        LONG("long", "쉬지 않고 오래 보기", "잠깐 화면을 끄고 먼 곳을 바라봐 주세요."),
        BLINK("blink", "깜빡임 감소", "의식적으로 천천히, 끝까지 깜빡여 주세요."),
        STRAIN("strain", "눈 찡그림·감김", "눈을 감고 잠시 쉬어 주세요."),
        LYING("lying", "누워서 보기", "앉아서 화면을 눈높이보다 조금 아래에 두세요.");
        companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
    }

    enum class Stimulus(val label: String, val darkMultiplier: Double) {
        STATIC("정적인 화면", .6), MODERATE("보통 화면", 1.0), INTENSE("자극적인 화면", 1.6)
    }

    enum class Combo(val label: String, val multiplier: Double) {
        NONE("", 1.0),
        DARK_NEAR("어두운 곳에서 가까이", 1.3),
        DARK_NEAR_LONG("어두운 곳에서 가까이, 오래", 1.6)
    }

    /** Values are already quality-gated by the caller; null means "not measured now". */
    data class Inputs(
        val lux: Double? = null,
        val brightness: Double? = null,        // OS brightness setting 0..1
        val distanceCm: Double? = null,
        val habitualCm: Double? = null,        // user's usual distance from earlier days
        val continuousMin: Double? = null,     // viewing since the last ≥5 min screen-off rest
        val screenChange: Double? = null,      // S06 mean luma change
        val largeRatio: Double? = null,        // S08 share of large changes
        val flashPerMin: Double? = null,       // S09
        val scenePerMin: Double? = null,       // S10
        val blinksPerMin: Double? = null,
        val closure: Double? = null,           // F07 share of near-closed frames
        val squint: Double? = null,            // F09
        val tiltDeg: Double? = null,
        val darkMinutes: Double = 0.0,         // continuous dark viewing so far (from the ledger)
        val nearMinutes: Double = 0.0,         // continuous near viewing so far (from the ledger)
    )

    data class Assessment(
        val rates: Map<Factor, Double>,        // points per minute, after the combo multiplier
        val stimulus: Stimulus?,
        val dark: Boolean,
        val nearSeverity: Double,              // 0..1
        val combo: Combo,
        val totalRate: Double,
    ) {
        val near: Boolean get() = nearSeverity >= .2
        val active: List<Factor> get() = rates.filter { it.value >= .01 && it.key != Factor.BASE }
            .entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun ramp(x: Double, low: Double, high: Double) = ((x - low) / (high - low)).coerceIn(0.0, 1.0)

    /** Screen stimulation from frame change, large changes, flashes and scene cuts. */
    fun stimulus(change: Double?, largeRatio: Double?, flashPerMin: Double?, scenePerMin: Double?): Stimulus? {
        val parts = listOfNotNull(change?.let { ramp(it, .03, .12) }, largeRatio?.let { ramp(it, .05, .30) },
            flashPerMin?.let { ramp(it, 1.0, 6.0) }, scenePerMin?.let { ramp(it, 2.0, 12.0) })
        if (parts.isEmpty()) return null
        val level = parts.max()
        return when { level >= .65 -> Stimulus.INTENSE; level >= .25 -> Stimulus.MODERATE; else -> Stimulus.STATIC }
    }

    /** 0..1: closer than 35 cm (absolute), or closer than the user's usual distance (relative). */
    fun nearSeverity(distanceCm: Double?, habitualCm: Double?): Double {
        val d = distanceCm?.takeIf { it.isFinite() && it in 10.0..100.0 } ?: return 0.0
        val absolute = ramp(35.0 - d, 0.0, 15.0)
        val relative = habitualCm?.takeIf { it.isFinite() && it in 15.0..80.0 }?.let { ramp(it - d, 3.0, 11.0) } ?: 0.0
        return max(absolute, relative)
    }

    /** Screen-time rate by continuous viewing: 0.05 → +0.05 at 20 min → +0.1 at 40 → +0.2 at 60 → +0.5 at 90. */
    fun screenTimeRate(continuousMin: Double?): Double {
        val m = continuousMin?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        return when { m >= 90 -> .90; m >= 60 -> .40; m >= 40 -> .20; m >= 20 -> .10; else -> .05 }
    }

    /** Extra rate for viewing without a break beyond 20 minutes. */
    fun longRate(continuousMin: Double?): Double {
        val m = continuousMin?.takeIf { it.isFinite() } ?: return 0.0
        return when { m >= 90 -> .80; m >= 60 -> .50; m >= 40 -> .30; m >= 20 -> .15; else -> 0.0 }
    }

    /** Near viewing weight: ×1 for the first 20 continuous near minutes, then up to ×4 at 60 minutes. */
    fun nearTimeWeight(nearMinutes: Double) = 1 + 3 * ramp(nearMinutes, 20.0, 60.0)

    /** Dark viewing weight: ×1 at the start, ×2 at 20 min, ×3 at 40 min, ×4 from 60 min. */
    fun darkTimeWeight(darkMinutes: Double) = 1 + min(max(darkMinutes, 0.0) / 20.0, 3.0)

    /** Flash, blink, strain and lying weights: ×1 up to 20 continuous minutes, then up to ×2 at 60. */
    fun viewingTimeWeight(continuousMin: Double?) = 1 + ramp(continuousMin ?: 0.0, 20.0, 60.0)

    fun assess(i: Inputs): Assessment {
        val rates = linkedMapOf<Factor, Double>()
        rates[Factor.BASE] = screenTimeRate(i.continuousMin)
        val stim = stimulus(i.screenChange, i.largeRatio, i.flashPerMin, i.scenePerMin)
        val later = viewingTimeWeight(i.continuousMin)
        // Darkness: dim (<50 lx) or dark (<10 lx). Grows with continuous dark viewing (×1 → ×4 over 60 min),
        // with stimulation (static ×0.6, intense ×1.6) and with a brighter OS setting (×0.8 → ×1.2).
        val lux = i.lux?.takeIf { it.isFinite() && it >= 0 }
        val dark = lux != null && lux < 50
        if (dark) {
            val base = if (lux!! < 10) .25 else .15
            val bright = i.brightness?.takeIf { it in 0.0..1.0 }?.let { .8 + .4 * it } ?: 1.0
            rates[Factor.DARK] = base * darkTimeWeight(i.darkMinutes) * (stim?.darkMultiplier ?: 1.0) * bright
        }
        // Near viewing: up to 0.4 pt/min, ×4 after 60 continuous near minutes.
        val near = nearSeverity(i.distanceCm, i.habitualCm)
        if (near > 0) rates[Factor.NEAR] = .4 * near * nearTimeWeight(i.nearMinutes)
        // Flashing / strongly changing content, stronger in the dark.
        val flash = listOfNotNull(i.flashPerMin?.let { ramp(it, 2.0, 8.0) }, i.largeRatio?.let { ramp(it, .15, .45) }).maxOrNull() ?: 0.0
        if (flash > 0) rates[Factor.FLASH] = .35 * flash * (if (dark) 1.2 else 1.0) * later
        // 20-20-20: continuous viewing beyond 20 minutes, in steps.
        longRate(i.continuousMin).takeIf { it > 0 }?.let { rates[Factor.LONG] = it }
        i.blinksPerMin?.takeIf { it.isFinite() && it >= 0 }?.let { b ->
            if (b < 5) rates[Factor.BLINK] = .25 * later else if (b < 8) rates[Factor.BLINK] = .12 * later
        }
        val strain = max(i.closure?.let { ramp(it, .10, .30) } ?: 0.0, i.squint?.let { ramp(it, .40, .70) } ?: 0.0)
        if (strain > 0) rates[Factor.STRAIN] = .10 * strain * later
        if ((i.tiltDeg?.let { kotlin.math.abs(it) } ?: 0.0) >= 60) rates[Factor.LYING] = .05 * later

        val long = (i.continuousMin ?: 0.0) >= 20
        val combo = when {
            dark && near >= .2 && long -> Combo.DARK_NEAR_LONG
            dark && near >= .2 -> Combo.DARK_NEAR
            else -> Combo.NONE
        }
        val raw = rates.values.sum() * combo.multiplier
        val scale = if (raw > MAX_RATE) MAX_RATE / raw else 1.0
        val final = rates.mapValues { (_, r) -> r * combo.multiplier * scale }
        return Assessment(final, stim, dark, near, combo, final.values.sum())
    }

    /** Observed rest restores 1.5 pt/min for the first 5 minutes of a rest, then 1.0 pt/min. */
    fun recoveryPoints(restAlreadyMs: Long, restMs: Long): Double {
        if (restMs <= 0) return 0.0
        val fastEnd = 5 * 60_000L
        val fast = (minOf(restAlreadyMs + restMs, fastEnd) - restAlreadyMs).coerceAtLeast(0)
        val slow = restMs - fast
        return 1.5 * fast / 60_000.0 + 1.0 * slow / 60_000.0
    }
}
