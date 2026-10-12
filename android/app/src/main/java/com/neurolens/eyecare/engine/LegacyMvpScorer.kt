package com.neurolens.eyecare.engine

import kotlin.math.*

/** Research heuristic. Each available measurement contributes with a bounded quality weight. */
object LegacyMvpScorer {
    const val VERSION = "efs-hypothesis-0.7"
    const val LEGACY_VERSION = "efs-hypothesis-0.5"
    // Dark and near exposure now live inside domains; scenario bonuses cannot double count.
    const val SCENARIO_CAP = 0
    fun sentence(score: Int): String = when (score.coerceIn(0, 100)) {
        in 0..24 -> "눈 피로도가 양호해요. 건강한 눈을 만들어가요."
        in 25..49 -> "눈 피로도는 보통 정도예요. 너무 오래 보지 않기를 권장드려요."
        in 50..74 -> "눈이 피곤해지고 있어요. 눈을 쉬게 해주세요."
        else -> "눈이 너무 피곤해요. 오늘은 그만 쉬어주세요."
    }
    private data class Feature(val id: String, val risk: (Double, PersonalBaseline) -> Double)
    private fun ramp(value: Double, low: Double, high: Double) = ((value - low) / (high - low) * 100).coerceIn(0.0, 100.0)
    private fun inverse(value: Double, low: Double, high: Double) = 100 - ramp(value, low, high)
    private val definitions = mapOf(
        "screen" to Pair(.26, listOf(
            Feature("S03.blueRatio") { x, _ -> ramp(x, .25, .43) },
            Feature("S06.change") { x, _ -> ramp(x, .02, .12) },
            Feature("S07.changedArea") { x, _ -> ramp(x, .02, .50) },
            Feature("S08.largeRatio") { x, _ -> ramp(x, .0, .30) },
            Feature("S09.flashPerMin") { x, _ -> ramp(x, 0.0, 6.0) },
            Feature("S10.scenePerMin") { x, _ -> ramp(x, 0.0, 12.0) },
            Feature("S11.scrollPxPerSec") { x, _ -> ramp(x, 0.0, 150.0) },
            Feature("S12.edgeDensity") { x, _ -> ramp(x, .05, .35) },
            Feature("S16.emissionProxy") { x, _ -> ramp(x, .08, .75) }
        )),
        "environment" to Pair(.22, listOf(
            Feature("E08.darkBrightRisk") { x, _ -> x.coerceIn(0.0, 100.0) },
            Feature("E05.thermalStatus") { x, _ -> ramp(x, 1.0, 5.0) },
            Feature("M03.walking") { x, _ -> x * 55 }
        )),
        "posture" to Pair(.08, listOf(
            Feature("M01.tiltDeg") { x, _ -> ramp(abs(x), 15.0, 70.0) },
            Feature("M02.shake") { x, _ -> ramp(x, .2, 3.0) },
            Feature("F12.headMotion") { x, _ -> ramp(x, .02, .25) }
        )),
        "usage" to Pair(.18, listOf(
            Feature("U01.continuousMin") { x, _ -> ramp(x, 10.0, 120.0) },
            Feature("U03.switchesPerMin") { x, _ -> ramp(x, .0, 5.0) },
            Feature("U06.unlocks") { x, _ -> ramp(x, 0.0, 12.0) },
            Feature("U07.microBreaks") { x, _ -> inverse(x, 0.0, 5.0) },
            Feature("D02.nearExposureRisk") { x, _ -> x.coerceIn(0.0, 100.0) },
            // These differences are rule-based, local comparisons with established habits.
            Feature("H01.usageVsBaseline") { x, _ -> ramp(x, 0.0, 60.0) },
            Feature("H04.switchesVsBaseline") { x, _ -> ramp(x, 0.0, 3.0) },
            Feature("H05.unlocksVsBaseline") { x, _ -> ramp(x, 0.0, 5.0) },
            Feature("H06.microBreaksVsBaseline") { x, _ -> ramp(-x, 0.0, 3.0) }
        )),
        "eyes" to Pair(.26, listOf(
            Feature("F02.blinksPerMin") { x, b ->
                if (b.blink.ready) ramp(abs(x - b.blink.mean!!), 2.0, 15.0)
                else ramp(abs(x - 17.0), 5.0, 22.0)
            },
            Feature("F03.longestIbiSec") { x, _ -> ramp(x, 4.0, 25.0) },
            Feature("F05.incompleteRatio") { x, _ -> ramp(x, .05, .55) },
            Feature("F06.openMean") { x, b -> if (b.open.ready)
                ramp((b.open.mean!! - x) / b.open.mean!!.coerceAtLeast(.01), .05, .45) else inverse(x, .08, .30) },
            Feature("F07.closure80") { x, _ -> ramp(x, .05, .45) },
            Feature("F08.longClosures") { x, _ -> ramp(x, 0.0, 5.0) },
            Feature("F09.squint") { x, _ -> ramp(x, .1, .65) },
            // Yawning is a weak supporting cue and never becomes a decisive domain by itself.
            Feature("F10.yawns") { x, _ -> ramp(x, 0.0, 6.0) * .35 },
            Feature("F15.gazeMotion") { x, _ -> ramp(x, .02, .20) }
        ))
    )

    fun score(signals: Map<String, Signal>, baseline: PersonalBaseline = PersonalBaseline()): MvpResult {
        val domains = linkedMapOf<String, Int>()
        val effectiveWeights = linkedMapOf<String, Double>()
        val domainQualities = linkedMapOf<String, Double>()
        val used = mutableListOf<String>()
        var weighted = 0.0; var total = 0.0
        for ((domain, definition) in definitions) {
            val observed = definition.second.mapNotNull { feature ->
                val signal = signals[feature.id] ?: return@mapNotNull null
                val value = signal.value ?: return@mapNotNull null
                if (signal.quality <= 0) return@mapNotNull null
                val risk = feature.risk(value, baseline).coerceIn(0.0, 100.0)
                used += feature.id
                risk to signal.quality.coerceIn(.05, 1.0)
            }
            if (observed.isEmpty() || (domain == "eyes" && observed.size < 3)) continue
            val domainScore = observed.sumOf { it.first * it.second } / observed.sumOf { it.second }
            domains[domain] = domainScore.roundToInt()
            val domainQuality = (observed.sumOf { it.second } / definition.second.size).coerceIn(0.0, 1.0)
            domainQualities[domain] = domainQuality
            val weight = definition.first * domainQuality
            effectiveWeights[domain] = weight
            weighted += domainScore * weight; total += weight
        }
        val base = if (total > 0) (weighted / total).roundToInt().coerceIn(0, 100) else 0
        val adjustments = emptyList<ScenarioAdjustment>()
        val applied = mutableListOf<ScenarioAdjustment>()
        var remaining = SCENARIO_CAP
        for (item in adjustments) {
            val points = item.points.coerceAtMost(remaining)
            if (points > 0) applied += item.copy(points = points)
            remaining -= points
        }
        val contributions = effectiveWeights.mapValues { (name, weight) ->
            if (total > 0) weight / total * domains.getValue(name) else 0.0
        }
        val scoreAvailable = domains.size >= 2 && used.size >= 3 && total >= .15
        val confidence = when {
            total >= .6 && domains.size >= 3 && used.size >= 8 && domains.containsKey("eyes") &&
                (signals["S17.coverage"]?.value ?: 0.0) >= .5 &&
                (signals["F01.coverage"]?.value ?: 0.0) >= .5 &&
                (signals["F01.observedMin"]?.value ?: 0.0) >= 1.0 -> "보통 · 연구용"
            else -> "낮음 · 부분 관측"
        }
        return MvpResult((base + applied.sumOf { it.points }).coerceIn(0, 100),
            confidence, domains, signals, used + applied.flatMap { it.evidence }.distinct(), baseline.samples,
            baseScore = base,
            nominalWeights = definitions.mapValues { (_, definition) -> (definition.first * 100).roundToInt() },
            contributions = contributions, availableWeight = (total * 100).roundToInt(),
            domainQualities = domainQualities,
            adjustments = applied, scoreAvailable = scoreAvailable,
            note = "User-selected research weights. Dark and near exposure are counted once inside domains; no scenario bonus. Not a diagnosis.")
    }
}

