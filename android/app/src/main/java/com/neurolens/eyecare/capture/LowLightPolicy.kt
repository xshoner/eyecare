package com.neurolens.eyecare.capture

import kotlin.math.ln
import kotlin.math.pow

/** Subsampled raw-luma statistics of one camera frame (Y bytes, 0–255). */
data class LumaStats(val p50: Int, val p95: Int)

/** Digital enhancement applied to Y before face landmarks. Identity when [gain] is 1. */
data class Enhancement(val gain: Double = 1.0, val gamma: Double = 1.0, val grayscale: Boolean = false) {
    val active get() = gain > 1.001 || gamma < .999 || grayscale
}

/**
 * Pure low-light policies for the front camera. No Android types, so every rule is unit tested.
 * All thresholds are engineering choices for landmark detectability, not exposure standards.
 */
object LowLightPolicy {
    /** Raw median Y below this is "low light" for coaching and the C05 share. */
    const val LOW_LIGHT_P50 = 40
    private const val TARGET_P50 = 100.0
    private const val MAX_GAIN = 4.0

    /**
     * AE target fps range. Blink timing needs at least [minFps] delivered frames, so candidates
     * keep their lower bound >= [minFps]. Among them prefer an upper bound <= 30 (60 fps halves the
     * possible exposure for no gain: landmark inference is the bottleneck), then the highest upper
     * bound, then the lowest lower bound (lets AE lengthen exposure in the dark without dropping
     * under [minFps]). If no range reaches [minFps], take the highest lower bound available.
     */
    fun chooseFpsRange(ranges: List<Pair<Int, Int>>, minFps: Int = 15): Pair<Int, Int>? {
        val valid = ranges.filter { (low, high) -> low > 0 && high >= low }
        if (valid.isEmpty()) return null
        val candidates = valid.filter { it.first >= minFps }
        val order = compareByDescending<Pair<Int, Int>> { it.second <= 30 }
            .thenByDescending { it.second }.thenBy { it.first }
        return if (candidates.isNotEmpty()) candidates.sortedWith(order).first()
        else valid.sortedWith(compareByDescending<Pair<Int, Int>> { it.first }
            .thenByDescending { it.second <= 30 }.thenByDescending { it.second }).first()
    }

    /** Percentiles from every [step]-th pixel of the Y plane. Null when nothing was sampled. */
    fun lumaStats(y: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        step: Int = 8): LumaStats? {
        if (width <= 0 || height <= 0 || step <= 0) return null
        val histogram = IntArray(256)
        var count = 0
        var row = 0
        while (row < height) {
            var col = 0
            val base = row * rowStride
            while (col < width) {
                val index = base + col * pixelStride
                if (index in y.indices) { histogram[y[index].toInt() and 255]++; count++ }
                col += step
            }
            row += step
        }
        if (count == 0) return null
        fun percentile(fraction: Double): Int {
            val rank = (count - 1) * fraction
            var seen = 0
            for (value in 0..255) { seen += histogram[value]; if (seen > rank) return value }
            return 255
        }
        return LumaStats(percentile(.5), percentile(.95))
    }

    /**
     * Gain that brings the raw median toward ~100/255, capped at 4x. Below a median of 70 nothing
     * changes for ordinary scenes; strong gain adds a mild shadow gamma, and very dark frames drop
     * chroma (mostly noise there) to grayscale.
     */
    fun enhancementFor(stats: LumaStats?): Enhancement {
        if (stats == null || stats.p50 >= 70) return Enhancement()
        val gain = (TARGET_P50 / stats.p50.coerceAtLeast(4)).coerceIn(1.0, MAX_GAIN)
        if (gain < 1.15) return Enhancement()
        return Enhancement(gain = gain, gamma = if (gain >= 2.0) .85 else 1.0, grayscale = stats.p50 < 25)
    }

    /** Y-byte lookup table: out = 255 * min(1, y * gain / 255) ^ gamma. */
    fun lut(enhancement: Enhancement): IntArray = IntArray(256) { y ->
        if (!enhancement.active) y
        else (255.0 * (y * enhancement.gain / 255.0).coerceAtMost(1.0).pow(enhancement.gamma))
            .toInt().coerceIn(0, 255)
    }

    /** Quality multiplier for landmarks found on an enhanced frame: 1 up to 1.5x, 0.5 at 4x. */
    fun enhancementQuality(gain: Double): Double =
        if (!gain.isFinite() || gain <= 1.5) 1.0 else (1.0 - (gain - 1.5) / 2.5 * .5).coerceIn(.5, 1.0)

    /**
     * Camera scene brightness, EV100 = log2(N^2 / t) - log2(ISO / 100). The ISO includes any
     * post-RAW digital boost (100 = none). Null for missing or impossible metadata.
     */
    fun ev100(aperture: Double?, exposureNs: Long?, iso: Int?, postRawBoost: Int? = null): Double? {
        if (aperture == null || exposureNs == null || iso == null) return null
        if (!aperture.isFinite() || aperture <= 0 || exposureNs <= 0 || iso <= 0) return null
        val seconds = exposureNs / 1e9
        val effectiveIso = iso * ((postRawBoost ?: 100).coerceAtLeast(1) / 100.0)
        return log2(aperture * aperture / seconds) - log2(effectiveIso / 100.0)
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}

/** Frame time on the elapsed-realtime clock. */
object CameraClock {
    /**
     * The sensor start-of-exposure time, when the camera declares a REALTIME timestamp source and
     * the value is plausible (0–1 s before now). Otherwise the callback arrival time. Sensor time
     * removes processing/queue jitter from blink durations; arrival time stays the safe fallback.
     */
    fun frameTimeMs(sensorTimestampNs: Long, realtimeSource: Boolean, callbackMs: Long): Long {
        if (!realtimeSource || sensorTimestampNs <= 0) return callbackMs
        val sensorMs = sensorTimestampNs / 1_000_000L
        return if (callbackMs - sensorMs in 0L..1_000L) sensorMs else callbackMs
    }
}

/**
 * AE exposure-compensation steps with hysteresis. Raises one EV after the raw median stays dark
 * for [holdMs]; lowers one EV after it stays bright for twice as long. Never goes below 0 (the
 * device default) and changes at most once per [minIntervalMs], so the request is re-issued only
 * on a real state change.
 */
class ExposureCompensator(private val maxIndex: Int, stepsPerEv: Int,
    private val darkP50: Int = 55, private val brightP50: Int = 120,
    private val holdMs: Long = 1_000, private val minIntervalMs: Long = 1_500) {
    private val step = stepsPerEv.coerceAtLeast(1)
    var index = 0
        private set
    private var darkSince = -1L
    private var brightSince = -1L
    private var changedAt = Long.MIN_VALUE / 2

    /** Returns the new compensation index when it should change, otherwise null. */
    fun update(nowMs: Long, p50: Int?): Int? {
        if (p50 == null || maxIndex <= 0) { darkSince = -1; brightSince = -1; return null }
        if (p50 < darkP50) { if (darkSince < 0) darkSince = nowMs; brightSince = -1 }
        else if (p50 > brightP50) { if (brightSince < 0) brightSince = nowMs; darkSince = -1 }
        else { darkSince = -1; brightSince = -1 }
        if (nowMs - changedAt < minIntervalMs) return null
        val next = when {
            darkSince >= 0 && nowMs - darkSince >= holdMs && index < maxIndex -> (index + step).coerceAtMost(maxIndex)
            brightSince >= 0 && nowMs - brightSince >= holdMs * 2 && index > 0 -> (index - step).coerceAtLeast(0)
            else -> return null
        }
        index = next; changedAt = nowMs; darkSince = -1; brightSince = -1
        return next
    }

    fun reset() { index = 0; darkSince = -1; brightSince = -1; changedAt = Long.MIN_VALUE / 2 }
}
