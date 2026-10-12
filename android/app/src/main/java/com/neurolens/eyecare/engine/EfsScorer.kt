package com.neurolens.eyecare.engine

import kotlin.math.roundToInt

data class EfsResult(
    val score: Int,
    val level: String,
    val confidence: String,
    val inputs: List<String>,
    val usageScore: Int,
    val screenScore: Int?,
    val coverage: Double,
    val scoreAvailable: Boolean = true
)

/** Research baseline from the v0.3 anchors. Missing domains never contribute zero. */
object EfsScorer {
    const val VERSION = "efs-heuristic-0.3"

    private fun anchor(value: Double, points: List<Pair<Double, Double>>): Double {
        if (value <= points.first().first) return points.first().second
        for (i in 1 until points.size) if (value <= points[i].first) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            return y0 + (value - x0) / (x1 - x0) * (y1 - y0)
        }
        return points.last().second
    }

    fun score(continuousMin: Double, bins: List<ExposureBin>, windowStartSec: Int, windowEndSec: Int): EfsResult {
        val usage = anchor(continuousMin, listOf(0.0 to 0.0, 20.0 to 25.0, 40.0 to 50.0,
            60.0 to 70.0, 120.0 to 100.0))
        val current = bins.filter { it.seconds >= windowStartSec && it.seconds < windowEndSec && it.validMs > 0 }
        val validMs = current.sumOf { it.validMs }
        val windowMs = ((windowEndSec - windowStartSec) * 1000L).coerceAtLeast(1)
        val coverage = (validMs.toDouble() / windowMs).coerceIn(0.0, 1.0)
        val screen = if (validMs > 0) {
            val mean = current.sumOf { it.change * it.validMs } / validMs
            val highRatio = current.sumOf { it.highMs }.toDouble() / validMs
            (anchor(mean, listOf(.02 to 0.0, .06 to 50.0, .12 to 100.0)) +
                anchor(highRatio, listOf(0.0 to 0.0, .1 to 50.0, .3 to 100.0))) / 2
        } else null
        val combined = if (screen == null) usage else (usage * .20 + screen * .15) / .35
        val score = combined.roundToInt().coerceIn(0, 100)
        return EfsResult(score, when {
            score < 25 -> "좋음"
            score < 50 -> "보통"
            score < 75 -> "피로 누적"
            else -> "휴식 필요"
        }, "낮음 · 화면·사용 기반", if (screen == null) listOf("U01") else listOf("U01", "S06", "S08"),
            usage.roundToInt(), screen?.roundToInt(), coverage)
    }
}
