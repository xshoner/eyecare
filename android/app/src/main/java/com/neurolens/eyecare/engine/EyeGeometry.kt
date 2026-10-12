package com.neurolens.eyecare.engine

import kotlin.math.hypot
import kotlin.math.sqrt

data class FacePoint(val x: Float, val y: Float, val z: Float = 0f)

/** Relative eye measurements in the camera image; u/v are not screen coordinates. */
data class EyeGeometry(
    val eyeAOpen: Double, val eyeBOpen: Double,
    val eyeAU: Double?, val eyeAV: Double?, val eyeBU: Double?, val eyeBV: Double?
) {
    val meanOpen get() = (eyeAOpen + eyeBOpen) / 2
    val openAsymmetry get() = kotlin.math.abs(eyeAOpen - eyeBOpen)
    val meanU get() = if (eyeAU != null && eyeBU != null) (eyeAU + eyeBU) / 2 else null
    val meanV get() = if (eyeAV != null && eyeBV != null) (eyeAV + eyeBV) / 2 else null
}

object EyeGeometryExtractor {
    private data class EyeIndex(val outer: Int, val inner: Int, val top: IntArray,
        val bottom: IntArray, val iris: Int)

    // MediaPipe Face Mesh indices; A/B avoid assuming mirrored preview semantics.
    private val eyeA = EyeIndex(33, 133, intArrayOf(160, 159, 158), intArrayOf(144, 145, 153), 468)
    private val eyeB = EyeIndex(362, 263, intArrayOf(385, 386, 387), intArrayOf(380, 374, 373), 473)

    fun extract(points: List<FacePoint>): EyeGeometry? {
        if (points.size < 478) return null
        val a = eye(points, eyeA) ?: return null
        val b = eye(points, eyeB) ?: return null
        return EyeGeometry(a.first, b.first, a.second.first, a.second.second, b.second.first, b.second.second)
    }

    private fun eye(p: List<FacePoint>, index: EyeIndex): Pair<Double, Pair<Double?, Double?>>? {
        val outer = p[index.outer]; val inner = p[index.inner]; val iris = p[index.iris]
        val width = hypot((outer.x - inner.x).toDouble(), (outer.y - inner.y).toDouble())
        if (!width.isFinite() || width < .025) return null
        val topY = index.top.map { p[it].y.toDouble() }.average()
        val bottomY = index.bottom.map { p[it].y.toDouble() }.average()
        val open = index.top.indices.map { i ->
            hypot((p[index.top[i]].x - p[index.bottom[i]].x).toDouble(),
                (p[index.top[i]].y - p[index.bottom[i]].y).toDouble())
        }.average() / width
        val spanY = bottomY - topY
        if (!open.isFinite() || open !in 0.0..0.8) return null
        if (spanY <= .002 || open < .045) return open to (null to null)
        val minX = minOf(outer.x, inner.x).toDouble()
        val maxX = maxOf(outer.x, inner.x).toDouble()
        val u = (iris.x - minX) / (maxX - minX)
        val v = (iris.y - topY) / spanY
        if (!u.isFinite() || !v.isFinite() || u !in -.25..1.25 || v !in -1.0..2.0)
            return open to (null to null)
        return open to (u to v)
    }
}

data class GazeWindow(val samples: Int, val stdU: Double?, val stdV: Double?,
    val rapidMovesPerMin: Double?, val state: String)

/** Ten-second relative stability proxy. Gaps break movement comparisons. */
class GazeStabilityTracker(private val windowMs: Long = 10_000) {
    private data class Sample(val at: Long, val u: Double, val v: Double)
    private val samples = ArrayDeque<Sample>()
    private val moves = ArrayDeque<Long>()

    fun add(atMs: Long, geometry: EyeGeometry): GazeWindow {
        val u = geometry.meanU; val v = geometry.meanV
        if (u == null || v == null) {
            trim(atMs)
            return snapshot()
        }
        val previous = samples.lastOrNull()
        if (previous != null && atMs > previous.at && atMs - previous.at <= 500 &&
            hypot(u - previous.u, v - previous.v) >= .12) moves.addLast(atMs)
        samples.addLast(Sample(atMs, u, v))
        trim(atMs)
        return snapshot()
    }

    private fun trim(atMs: Long) {
        while (samples.isNotEmpty() && atMs - samples.first().at > windowMs) samples.removeFirst()
        while (moves.isNotEmpty() && atMs - moves.first() > windowMs) moves.removeFirst()
    }

    private fun snapshot(): GazeWindow {
        if (samples.size < 5 || samples.last().at - samples.first().at < 2_000)
            return GazeWindow(samples.size, null, null, null, "insufficient")
        val meanU = samples.map { it.u }.average(); val meanV = samples.map { it.v }.average()
        val stdU = sqrt(samples.map { (it.u - meanU) * (it.u - meanU) }.average())
        val stdV = sqrt(samples.map { (it.v - meanV) * (it.v - meanV) }.average())
        val rate = moves.size * 60_000.0 / (samples.last().at - samples.first().at).coerceAtLeast(1)
        return GazeWindow(samples.size, stdU, stdV, rate,
            if (hypot(stdU, stdV) < .06 && rate < 6) "stable_relative" else "moving_relative")
    }

    fun reset() { samples.clear(); moves.clear() }
}
