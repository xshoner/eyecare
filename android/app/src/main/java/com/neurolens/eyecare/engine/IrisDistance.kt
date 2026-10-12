package com.neurolens.eyecare.engine

import kotlin.math.abs
import kotlin.math.hypot

/** Camera-axis distance proxy, never visual acuity or a prescription. */
data class DistanceEstimate(val cm: Double? = null, val quality: Double = 0.0,
    val diameterPx: Double? = null, val source: String = "unavailable",
    val reason: String? = null, val calibrated: Boolean = false)

object IrisDistance {
    private const val IRIS_DIAMETER_MM = 11.7 // Population assumption; individual variation remains.
    private fun span(a: FacePoint, b: FacePoint, width: Int, height: Int): Double =
        hypot((a.x - b.x) * width.toDouble(), (a.y - b.y) * height.toDouble())

    fun estimate(points: List<FacePoint>, width: Int, height: Int, focalPx: Double?,
        focalSource: String, geometry: EyeGeometry?, calibrationCmPx: Double? = null): DistanceEstimate {
        if (points.size < 478 || width < 100 || height < 100 || geometry == null ||
            geometry.meanOpen < .10 || geometry.meanU == null)
            return DistanceEstimate(reason = "iris_or_open_eye_unavailable")
        val leftH = span(points[469], points[471], width, height)
        val leftV = span(points[470], points[472], width, height)
        val rightH = span(points[474], points[476], width, height)
        val rightV = span(points[475], points[477], width, height)
        val diameters = listOf(leftH, leftV, rightH, rightV)
        if (diameters.any { !it.isFinite() || it < 3.0 })
            return DistanceEstimate(reason = "iris_too_small_or_occluded")
        val left = (leftH + leftV) / 2; val right = (rightH + rightV) / 2
        val difference = abs(left - right) / maxOf(left, right)
        val roundness = maxOf(abs(leftH - leftV) / maxOf(leftH, leftV),
            abs(rightH - rightV) / maxOf(rightH, rightV))
        if (difference > .25 || roundness > .30)
            return DistanceEstimate(reason = "pose_or_iris_geometry_unstable")
        val diameter = (left + right) / 2
        val calibrated = calibrationCmPx != null && calibrationCmPx.isFinite() && calibrationCmPx > 0
        if (!calibrated && (focalPx == null || !focalPx.isFinite() || focalPx <= 0))
            return DistanceEstimate(diameterPx = diameter, reason = "camera_geometry_or_calibration_needed")
        val cm = if (calibrated) calibrationCmPx!! / diameter else focalPx!! * IRIS_DIAMETER_MM / diameter / 10.0
        if (!cm.isFinite() || cm !in 15.0..80.0)
            return DistanceEstimate(diameterPx = diameter, reason = "outside_estimation_range")
        val source = if (calibrated) "user_reference" else focalSource
        val quality = (if (calibrated) .85 else if (focalSource == "camera2_intrinsic") .65 else .45) *
            (1.0 - difference) * (1.0 - roundness) * (diameter / 10.0).coerceIn(.5, 1.0)
        return DistanceEstimate(cm, quality.coerceIn(0.0, 1.0), diameter, source,
            calibrated = calibrated)
    }
}

data class DistanceSummary(val medianCm: Double? = null, val closeRisk: Double? = null,
    val nearRatio: Double? = null, val observedMs: Long = 0, val coverage: Double = 0.0,
    val quality: Double = 0.0, val calibrated: Boolean = false, val source: String = "unavailable",
    val jitterCm: Double? = null)

/** Intervals are capped to actual nearby frames; missing frames never mean a safe distance. */
class DistanceTracker {
    private data class Frame(val at: Long, val cm: Double?, val quality: Double,
        val calibrated: Boolean, val source: String)
    private val frames = ArrayDeque<Frame>()
    fun add(atMs: Long, value: DistanceEstimate) {
        if (frames.lastOrNull()?.at?.let { atMs <= it } == true) return
        frames.addLast(Frame(atMs, value.cm?.takeIf { value.quality >= .35 }, value.quality,
            value.calibrated, value.source))
        while (frames.isNotEmpty() && atMs - frames.first().at > 7_200_000) frames.removeFirst()
    }
    fun snapshot(now: Long, windowMs: Long): DistanceSummary {
        val window = windowMs.coerceIn(1, 7_200_000)
        val selected = frames.filter { it.at >= now - window && it.at <= now }
        val weighted = selected.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.at - a.at
            if (a.cm == null || dt !in 1..2_000) null else Triple(a, dt, a.quality)
        }
        val validMs = weighted.sumOf { it.second }
        if (validMs < 5_000) return DistanceSummary(coverage = validMs.toDouble() / window)
        val ordered = weighted.sortedBy { it.first.cm }
        var cumulative = 0L
        val median = ordered.first { cumulative += it.second; cumulative * 2 >= validMs }.first.cm!!
        val deviations = weighted.map { abs(it.first.cm!! - median) }.sorted()
        val jitter = deviations[deviations.size / 2]
        val nearMs = weighted.filter { it.first.cm!! < 30.0 }.sumOf { it.second }
        val risk = weighted.sumOf { (f, dt, _) -> ContextRisk.near(f.cm!!) * dt } / validMs
        val q = weighted.sumOf { it.third * it.second } / validMs
        val best = weighted.maxByOrNull { it.second }!!.first
        return DistanceSummary(median, risk, nearMs.toDouble() / validMs, validMs,
            validMs.toDouble() / window, q, weighted.all { it.first.calibrated }, best.source, jitter)
    }
    fun clear() = frames.clear()
}
