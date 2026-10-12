package com.neurolens.eyecare.engine

import kotlin.math.abs

data class ExposureBin(val seconds: Int, val validMs: Long, val change: Double, val highMs: Long)
data class QualityWindow(val seconds: Int, val excludedMs: Map<String, Long>)
data class ExposureStats(
    val validMs: Long = 0, val highMs: Long = 0, val frames: Int = 0,
    val meanChange: Double = 0.0, val currentChange: Double = 0.0,
    val contrast: Double = 0.0, val quality: String = "waiting",
    val bins: List<ExposureBin> = emptyList(),
    val excludedMs: Map<String, Long> = emptyMap(),
    val meanIntervalMs: Double = 0.0, val maxIntervalMs: Long = 0,
    val meanGlobalChange: Double = 0.0, val meanChangedArea: Double = 0.0,
    val processingCount: Int = 0, val processingTotalMs: Double = 0.0, val processingMaxMs: Double = 0.0,
    val qualityWindows: List<QualityWindow> = emptyList(),
    val counters: Map<String, Long> = emptyMap(), val intervalBuckets: Map<String, Long> = emptyMap(),
    val captureCounters: Map<String, Long> = emptyMap()
)

/** Sampled digital luma changes. Pending time remains unobserved until a valid pair arrives. */
class ExposureEngine(private val originMs: Long) {
    private var previous: FloatArray? = null
    private var previousAt = 0L
    private var frames = 0
    private var valid = 0L
    private var high = 0L
    private var weightedChange = 0.0
    private var current = 0.0
    private var contrast = 0.0
    private var quality = "waiting"
    private var accountedAt = originMs
    private var lastReceivedAt = -1L
    private val excluded = mutableMapOf<String, Long>()
    private val excludedWindows = sortedMapOf<Int, MutableMap<String, Long>>()
    private val counters = mutableMapOf<String, Long>()
    private val intervals = mutableMapOf<String, Long>()
    private fun count(key: String) { counters[key] = (counters[key] ?: 0) + 1 }
    private fun split(target: MutableMap<Int, MutableMap<String, Long>>, reason: String, from: Long, until: Long) {
        var cursor = from
        while (cursor < until) {
            val index = ((cursor - originMs) / 10000).toInt()
            val end = minOf(until, originMs + (index + 1) * 10000L)
            val window = target.getOrPut(index) { mutableMapOf() }
            window[reason] = (window[reason] ?: 0) + end - cursor
            cursor = end
        }
    }
    private val histogram = IntArray(1024)
    private var previousMean = 0.0
    private var weightedGlobal = 0.0
    private var weightedArea = 0.0
    private var intervalTotal = 0L
    private var intervalCount = 0
    private var intervalMax = 0L
    private data class Bucket(var valid: Long = 0, var weighted: Double = 0.0, var high: Long = 0)
    private val buckets = sortedMapOf<Int, Bucket>()

    private fun missingReason() = when (quality) { "measuring" -> "continuity_reset"; "waiting" -> "baseline_wait"; else -> quality }
    private fun exclude(reason: String, until: Long) {
        val span = (until - accountedAt).coerceAtLeast(0)
        if (span > 0) excluded[reason] = (excluded[reason] ?: 0) + span
        split(excludedWindows, reason, accountedAt, maxOf(accountedAt, until))
        accountedAt = maxOf(accountedAt, until)
    }
    fun resetContinuity(reason: String, atMs: Long = accountedAt) {
        count("reset_$reason")
        exclude(missingReason(), atMs)
        previous = null; quality = reason; current = 0.0
    }

    fun frame(luma: FloatArray, atMs: Long) {
        count("input_calls")
        if (atMs < accountedAt || atMs <= lastReceivedAt) return
        if (lastReceivedAt >= 0) {
            val interval = atMs - lastReceivedAt
            intervalTotal += interval; intervalCount++; intervalMax = maxOf(intervalMax, interval)
            val key = when { interval <= 550 -> "le550"; interval <= 1000 -> "le1000"; interval <= 1500 -> "le1500"; else -> "gt1500" }
            intervals[key] = (intervals[key] ?: 0) + 1
        }
        lastReceivedAt = atMs
        if (luma.isEmpty() || luma.any { !it.isFinite() || it < 0f || it > 1f }) {
            count("invalid_frames")
            exclude("invalid", atMs); resetContinuity("invalid"); return
        }
        frames++
        histogram.fill(0)
        var sumLuma = 0.0
        for (value in luma) { histogram[(value * 1023).toInt()]++; sumLuma += value }
        fun percentile(rank: Int): Double {
            var count = 0
            for (i in histogram.indices) { count += histogram[i]; if (count > rank) return i / 1023.0 }
            return 1.0
        }
        val mean = sumLuma / luma.size
        contrast = percentile((luma.size - 1) * 9 / 10) - percentile((luma.size - 1) / 10)
        if (mean < 0.012 && contrast < 0.025) {
            count("dark_frames")
            exclude("dark_or_protected", atMs); resetContinuity("dark_or_protected"); return
        }
        val prior = previous
        val dt = atMs - previousAt
        if (prior != null && prior.size == luma.size && dt in 1..1500) {
            count("valid_pairs")
            var sum = 0.0
            var changed = 0
            for (i in luma.indices) {
                val delta = abs(luma[i] - prior[i]).toDouble()
                sum += delta
                if (delta >= 0.15) changed++
            }
            current = sum / luma.size
            val large = current >= 0.10 && changed.toDouble() / luma.size >= 0.25
            valid += dt
            weightedChange += current * dt
            weightedGlobal += abs(mean - previousMean) * dt
            weightedArea += changed.toDouble() / luma.size * dt
            if (large) high += dt
            accountedAt = atMs
            // Split intervals across 10-second boundaries; never count a missing interval.
            var cursor = previousAt
            while (cursor < atMs) {
                val index = ((cursor - originMs) / 10000).toInt()
                val end = minOf(atMs, originMs + (index + 1) * 10000L)
                val span = end - cursor
                val bucket = buckets.getOrPut(index) { Bucket() }
                bucket.valid += span; bucket.weighted += current * span
                if (large) bucket.high += span
                cursor = end
            }
        } else {
            val reason = if (prior != null && dt > 1500) { count("long_gaps"); "sample_gap" } else missingReason()
            exclude(reason, atMs); current = 0.0
        }
        quality = "measuring"
        if (previous?.size != luma.size) previous = FloatArray(luma.size)
        luma.copyInto(previous!!)
        previousMean = mean
        previousAt = atMs
    }

    fun snapshot(nowMs: Long, finalized: Boolean = false): ExposureStats {
        val status = if (quality == "measuring" && nowMs - previousAt > 2000) "waiting_frames" else quality
        val missing = excluded.toMutableMap()
        val tail = (nowMs - accountedAt).coerceAtLeast(0)
        val reason = if (quality == "measuring") { if (finalized) "end_tail" else "pending" } else missingReason()
        val windows = excludedWindows.mapValues { it.value.toMutableMap() }.toMutableMap()
        if (tail > 0) { missing[reason] = (missing[reason] ?: 0) + tail; split(windows, reason, accountedAt, nowMs) }
        return ExposureStats(valid, high, frames, if (valid > 0) weightedChange / valid else 0.0,
            current, contrast, status,
            buckets.map { (key, value) -> ExposureBin(key * 10, value.valid, value.weighted / value.valid, value.high) },
            missing, if (intervalCount > 0) intervalTotal.toDouble() / intervalCount else 0.0, intervalMax,
            if (valid > 0) weightedGlobal / valid else 0.0, if (valid > 0) weightedArea / valid else 0.0,
            qualityWindows = windows.toSortedMap().map { (key, value) -> QualityWindow(key * 10, value.toMap()) },
            counters = counters.toMap(), intervalBuckets = intervals.toMap())
    }
}

