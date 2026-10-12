package com.neurolens.eyecare.engine

import kotlin.math.*

/** RGB feature extraction only. No pixels or screenshots are retained or exported. */
class ScreenSignalEngine {
    private data class Sample(val at: Long, val fields: Map<String, Double>, val quality: Double,
        val flash: Boolean?, val scene: Boolean?)
    private val samples = ArrayDeque<Sample>()
    private var previousLuma: DoubleArray? = null
    private var previousRows: DoubleArray? = null
    private var previousApl: Double? = null
    private var previousAt = 0L
    private var previousWidth = 0
    private var previousHeight = 0
    private val missing = mutableMapOf<String, Long>()

    fun frame(pixels: IntArray, width: Int, height: Int, atMs: Long): Boolean {
        if (width < 4 || height < 4 || pixels.size != width * height || atMs <= previousAt) {
            missing["invalid_frame"] = (missing["invalid_frame"] ?: 0) + 1; return false
        }
        val n = pixels.size
        val hist = IntArray(256)
        val luma = DoubleArray(n)
        val rows = DoubleArray(height)
        var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var satSum = 0.0; var aplSum = 0.0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16 and 255) / 255.0
            val g = (p ushr 8 and 255) / 255.0
            val b = (p and 255) / 255.0
            val y = .2126 * r + .7152 * g + .0722 * b
            rSum += r; gSum += g; bSum += b; aplSum += y
            satSum += if (maxOf(r, g, b) > 0) (maxOf(r, g, b) - minOf(r, g, b)) / maxOf(r, g, b) else 0.0
            luma[i] = y; rows[i / width] += y / width
            hist[(y * 255).toInt().coerceIn(0, 255)]++
        }
        fun percentile(p: Double): Double {
            val rank = (n * p).toInt(); var seen = 0
            for (i in hist.indices) { seen += hist[i]; if (seen >= rank) return i / 255.0 }
            return 1.0
        }
        val apl = aplSum / n
        val p10 = percentile(.10); val p50 = percentile(.50); val p90 = percentile(.90)
        val prior = previousLuma?.takeIf { previousWidth == width && previousHeight == height && atMs - previousAt <= 1500 }
        var change = 0.0; var area = 0.0
        if (prior != null) for (i in luma.indices) {
            val delta = abs(luma[i] - prior[i]); change += delta
            if (delta >= .15) area++
        }
        if (prior != null) { change /= n; area /= n }
        var edge = 0; var sampled = 0; var horizontal = 0
        for (y in 1 until height step 2) for (x in 1 until width step 2) {
            val i = y * width + x
            val dx = abs(luma[i] - luma[i - 1]); val dy = abs(luma[i] - luma[i - width])
            if (dx + dy > .12) edge++
            if (dy > .10 && dy > dx * 1.5) horizontal++
            sampled++
        }
        var scrollPxPerSec: Double? = null
        val oldRows = previousRows?.takeIf { prior != null }
        if (oldRows != null) {
            var best = Double.POSITIVE_INFINITY; var shift = 0
            for (d in -minOf(32, height / 4)..minOf(32, height / 4)) {
                var error = 0.0; var count = 0
                for (y in 0 until height step 3) if (y + d in oldRows.indices) {
                    error += abs(rows[y] - oldRows[y + d]); count++
                }
                if (count > 0 && error / count < best) { best = error / count; shift = d }
            }
            if (best < .13) scrollPxPerSec = abs(shift) * 1000.0 / (atMs - previousAt).coerceAtLeast(1)
        }
        val blue = bSum / (rSum + gSum + bSum).coerceAtLeast(1e-6)
        val fields = linkedMapOf(
            "S01.apl" to apl, "S02.p10" to p10, "S02.p50" to p50,
            "S02.p90" to p90, "S02.contrast" to (p90 - p10),
            "S03.red" to rSum / n, "S03.green" to gSum / n, "S03.blue" to bSum / n,
            "S03.blueRatio" to blue, "S04.saturation" to satSum / n,
            "S05.darkUi" to if (apl < .2 && p90 - p10 > .025) 1.0 else 0.0,
            "S12.edgeDensity" to edge.toDouble() / sampled.coerceAtLeast(1),
            "S13.readingProbability" to ((horizontal.toDouble() / sampled.coerceAtLeast(1)) *
                (1.0 - (change / .1).coerceIn(0.0, 1.0)) * 4).coerceIn(0.0, 1.0)
        )
        colorTemperature(rSum / n, gSum / n, bSum / n)?.let { fields["S03.cctK"] = it }
        if (prior != null) {
            fields["S06.change"] = change; fields["S07.changedArea"] = area
            fields["S08.largeRatio"] = if (change >= .10 && area >= .25) 1.0 else 0.0
            scrollPxPerSec?.let { fields["S11.scrollPxPerSec"] = it }
        } else missing["no_frame_pair"] = (missing["no_frame_pair"] ?: 0) + 1
        val quality = if (apl < .012 && p90 - p10 < .025) .20 else 1.0
        if (quality < 1) missing["dark_or_protected"] = (missing["dark_or_protected"] ?: 0) + 1
        samples.addLast(Sample(atMs, fields, quality,
            if (prior != null) previousApl?.let { abs(apl - it) >= .2 } else null,
            if (prior != null) area >= .6 else null))
        previousLuma = luma; previousRows = rows; previousApl = apl
        previousAt = atMs; previousWidth = width; previousHeight = height
        while (samples.isNotEmpty() && atMs - samples.first().at > 300_000) samples.removeFirst()
        return true
    }

    fun reset(reason: String) {
        samples.clear() // A locked, resized, or stopped capture must not report stale frames.
        previousLuma = null; previousRows = null; previousApl = null
        missing[reason] = (missing[reason] ?: 0) + 1
    }

    fun snapshot(nowMs: Long, unavailableReason: String? = null): Map<String, Signal> {
        val window = samples.filter { it.at in (nowMs - 300_000)..nowMs }
        val reason = unavailableReason ?: if (window.isEmpty()) "no_capture" else null
        if (reason != null) return METRICS.associateWith { id ->
            if (id == "S17.excludedFrames") Signal.observed(missing.values.sum().toDouble())
            else Signal.missing(reason)
        }
        val keys = window.flatMap { it.fields.keys }.distinct()
        val result = keys.associateWith { key ->
            val values = window.mapNotNull { sample -> sample.fields[key]?.let { it to sample.quality } }
            Signal.observed(values.sumOf { it.first * it.second } / values.sumOf { it.second },
                values.sumOf { it.second } / window.size,
                estimated = key in setOf("S03.cctK", "S11.scrollPxPerSec", "S13.readingProbability"))
        }.toMutableMap()
        val minutes = ((window.last().at - window.first().at) / 60_000.0).coerceAtLeast(1.0 / 60)
        val paired = window.filter { it.flash != null && it.scene != null }
        val pairQuality = paired.takeIf { it.isNotEmpty() }?.map { it.quality }?.average() ?: 0.0
        result["S09.flashPerMin"] = if (paired.isNotEmpty())
            Signal.observed(paired.count { it.flash == true } / minutes, pairQuality) else Signal.missing("no_frame_pair")
        result["S10.scenePerMin"] = if (paired.isNotEmpty())
            Signal.observed(paired.count { it.scene == true } / minutes, pairQuality) else Signal.missing("no_frame_pair")
        result["S17.coverage"] = Signal.observed((window.size / (minutes * 120)).coerceIn(0.0, 1.0))
        result["S17.excludedFrames"] = Signal.observed(missing.values.sum().toDouble())
        for (id in METRICS) if (id !in result) result[id] = Signal.missing(when (id) {
            "S11.scrollPxPerSec" -> "row_match_unavailable"
            "S03.cctK" -> "color_estimate_unavailable"
            "S06.change", "S07.changedArea", "S08.largeRatio" -> "no_frame_pair"
            else -> "no_valid_capture"
        })
        return result
    }

    companion object {
        val METRICS = listOf("S01.apl", "S02.p10", "S02.p50", "S02.p90", "S02.contrast",
            "S03.red", "S03.green", "S03.blue", "S03.blueRatio", "S03.cctK", "S04.saturation",
            "S05.darkUi", "S06.change", "S07.changedArea", "S08.largeRatio", "S09.flashPerMin",
            "S10.scenePerMin", "S11.scrollPxPerSec", "S12.edgeDensity", "S13.readingProbability",
            "S17.coverage", "S17.excludedFrames")
        val CONTEXT_METRICS = listOf("S14.rotation", "S14.refreshHz", "S14.widthPx", "S14.heightPx",
            "S15.brightness", "S15.autoBrightness", "S15.darkMode", "S15.fontScale",
            "S15.densityDpi", "S15.nightLight", "S15.extraDim", "S16.emissionProxy",
            "S17.validMs", "S17.excludedMs", "S17.captureActive", "S18.timeCoverage")
    }

    private fun colorTemperature(r: Double, g: Double, b: Double): Double? {
        fun linear(c: Double) = if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        val rr = linear(r); val gg = linear(g); val bb = linear(b)
        val x = .4124 * rr + .3576 * gg + .1805 * bb
        val y = .2126 * rr + .7152 * gg + .0722 * bb
        val z = .0193 * rr + .1192 * gg + .9505 * bb
        val sum = x + y + z
        if (sum < .01) return null
        val cx = x / sum; val cy = y / sum
        val divisor = cy - .1858
        if (abs(divisor) < .005) return null
        val n = (cx - .3320) / divisor
        val kelvin = 449 * n.pow(3) + 3525 * n.pow(2) + 6823.3 * n + 5520.33
        return kelvin.takeIf { it.isFinite() && it in 1000.0..40000.0 }
    }
}
