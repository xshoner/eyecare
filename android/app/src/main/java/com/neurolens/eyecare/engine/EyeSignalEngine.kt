package com.neurolens.eyecare.engine

import kotlin.math.*

/** Temporal eye events are candidates until device and manually labelled validation. */
class EyeSignalEngine {
    private data class Frame(val at: Long, val open: Double?, val asymmetry: Double?,
        val squint: Double?, val jaw: Double?, val gazeMotion: Double?, val status: String,
        val headMotion: Double?, val faceIllumination: Double?, val blendshapeCount: Int,
        val camera: CameraFrame = CameraFrame())
    private val frames = ArrayDeque<Frame>()
    private val blinkTimes = ArrayDeque<Long>()
    private val blinkDurations = ArrayDeque<Long>()
    private val incomplete = ArrayDeque<Long>()
    private val longClosures = ArrayDeque<Long>()
    private val yawns = ArrayDeque<Long>()
    private var openReference: Double? = null
    private var closedAt: Long? = null
    private var partialAt: Long? = null
    private var yawnAt: Long? = null
    private var yawnCounted = false
    private var previousAt = 0L
    private var previousHead: List<Float>? = null

    fun reset() {
        frames.clear(); blinkTimes.clear(); blinkDurations.clear(); incomplete.clear()
        longClosures.clear(); yawns.clear()
        openReference = null; closedAt = null; partialAt = null; yawnAt = null
        yawnCounted = false; previousAt = 0L; previousHead = null
    }

    fun add(atMs: Long, openA: Double?, openB: Double?, shapes: Map<String, Float>,
        gazeMotion: Double? = null, status: String = "observed",
        headMatrix: List<Float> = emptyList(), faceIllumination: Double? = null,
        camera: CameraFrame = CameraFrame()) {
        if (atMs <= previousAt) return
        if (previousAt > 0 && atMs - previousAt > 150) {
            // A blink can begin and end between sparse samples; never count that as a measured event.
            closedAt = null; partialAt = null
        }
        if (previousAt > 0 && atMs - previousAt > 1000) {
            yawnAt = null; yawnCounted = false; previousHead = null
        }
        val open = if (openA != null && openB != null && openA.isFinite() && openB.isFinite()) (openA + openB) / 2 else null
        if (open != null && (openReference == null || open > openReference!!))
            openReference = if (openReference == null) open else openReference!! * .98 + open * .02
        val reference = openReference ?: .15
        val ratio = open?.div(reference.coerceAtLeast(.04))
        val blinkShape = listOfNotNull(shapes["eyeBlinkLeft"], shapes["eyeBlinkRight"]).takeIf { it.isNotEmpty() }?.average()
        val closed = (ratio != null && ratio < .45) || (blinkShape != null && blinkShape > .65)
        val partial = ratio != null && ratio in .50..0.85 && !closed
        if (open == null) { closedAt = null; partialAt = null }
        else if (closed) {
            if (closedAt == null) closedAt = atMs
            partialAt = null
        } else {
            closedAt?.let { started ->
                val duration = atMs - started
                if (duration in 60..500) { blinkTimes.addLast(atMs); blinkDurations.addLast(duration) }
                if (duration >= 500) longClosures.addLast(atMs)
            }
            closedAt = null
            if (partial && partialAt == null) partialAt = atMs
            if (!partial && partialAt != null && atMs - partialAt!! in 60..500) incomplete.addLast(atMs)
            if (!partial) partialAt = null
        }
        val jaw = shapes["jawOpen"]?.toDouble()
        if (jaw != null && jaw >= .65) {
            if (yawnAt == null) yawnAt = atMs
            if (!yawnCounted && atMs - yawnAt!! >= 1500) { yawns.addLast(atMs); yawnCounted = true }
        } else { yawnAt = null; yawnCounted = false }
        val squint = listOfNotNull(shapes["eyeSquintLeft"], shapes["eyeSquintRight"],
            shapes["browDownLeft"], shapes["browDownRight"]).takeIf { it.isNotEmpty() }?.average()
        val headMotion = previousHead?.takeIf { it.size >= 11 && headMatrix.size >= 11 }?.let { old ->
            sqrt(listOf(0, 1, 2, 4, 5, 6, 8, 9, 10).sumOf { (headMatrix[it] - old[it]).toDouble().pow(2) })
        }
        previousHead = headMatrix.takeIf { it.size >= 11 }
        frames.addLast(Frame(atMs, open, if (openA != null && openB != null) abs(openA - openB) else null,
            squint, jaw, gazeMotion, status, headMotion, faceIllumination, shapes.size, camera))
        previousAt = atMs
        trim(atMs)
    }

    private fun trim(now: Long) {
        while (frames.isNotEmpty() && now - frames.first().at > 300_000) frames.removeFirst()
        for (queue in listOf(blinkTimes, incomplete, longClosures, yawns))
            while (queue.isNotEmpty() && now - queue.first() > 300_000) queue.removeFirst()
    }

    fun snapshot(now: Long, unavailableReason: String? = null): Map<String, Signal> {
        trim(now)
        val current = frames.filter { it.at in (now - 300_000)..now }
        val reason = unavailableReason ?: if (current.isEmpty()) "camera_unavailable" else null
        if (reason != null) return METRICS.associateWith { Signal.missing(reason) }
        if (!ObservationFreshness.current(now, current.last().at))
            return METRICS.associateWith { Signal.missing("stale_eye_sample") }
        val cameraSignals = cameraSignals(current)
        val valid = current.filter { it.open != null }
        if (valid.isEmpty()) {
            val reason = if (lowLightDominant(current)) LOW_LIGHT_FACE_NOT_FOUND else "eye_geometry_unavailable"
            return METRICS.associateWith { Signal.missing(reason) } + cameraSignals
        }
        if (!ObservationFreshness.current(now, valid.last().at))
            return METRICS.associateWith { Signal.missing("stale_eye_geometry") }
        val validGaps = current.zipWithNext { a, b ->
            if (a.open != null && b.open != null && b.at - a.at in 1..1000) b.at - a.at else 0L
        }.filter { it > 0 }.sorted()
        val validMs = validGaps.sum()
        val elapsedMs = (now - current.first().at).coerceAtLeast(1)
        val coverage = (validMs.toDouble() / elapsedMs).coerceIn(0.0, 1.0)
        val minutes = validMs / 60_000.0
        // Landmarks found on digitally brightened frames are noisier; their quality is scaled down.
        val enhancementQuality = valid.map { it.camera.landmarkQuality.coerceIn(0.0, 1.0) }.average()
        val effectiveQuality = coverage * minutes.coerceIn(.05, 1.0) * enhancementQuality
        val result = linkedMapOf<String, Signal>()
        result["F01.coverage"] = Signal.observed(coverage)
        result["F01.detectRatio"] = Signal.observed(valid.size.toDouble() / current.size)
        result["F01.observedMin"] = Signal.observed(minutes)
        if (valid.size < 3 || minutes <= 0) {
            for (id in listOf("F02.blinksPerMin", "F03.longestIbiSec", "F04.blinkDurationMs", "F05.incompleteRatio",
                "F06.openMean", "F07.closure80", "F08.longClosures", "F09.squint", "F10.yawns", "F15.gazeMotion", "F16.asymmetry"))
                result[id] = Signal.missing(if (lowLightDominant(current)) LOW_LIGHT_FACE_NOT_FOUND
                    else "insufficient_eye_observation")
        } else {
            fun signal(value: Double, quality: Double = effectiveQuality) = Signal.observed(value, quality)
            result["F02.blinksPerMin"] = signal(blinkTimes.size / minutes)
            val intervals = blinkTimes.zipWithNext { a, b -> (b - a) / 1000.0 }.sorted()
            result["F03.ibiP90Sec"] = if (intervals.isEmpty()) Signal.missing("insufficient_blinks")
                else signal(intervals[((intervals.size - 1) * .9).toInt()])
            result["F03.longestIbiSec"] = signal((intervals.maxOrNull() ?: (minutes * 60)).coerceAtMost(300.0))
            result["F04.blinkDurationMs"] = if (blinkDurations.isEmpty()) Signal.missing("insufficient_blinks")
                else signal(blinkDurations.sorted()[blinkDurations.size / 2].toDouble())
            result["F05.incompleteRatio"] = signal(incomplete.size.toDouble() / (blinkTimes.size + incomplete.size).coerceAtLeast(1), effectiveQuality * .6)
            result["F06.openMean"] = signal(valid.mapNotNull { it.open }.average())
            result["F06.openVariance"] = signal(valid.mapNotNull { it.open }.let { v -> val m = v.average(); v.map { (it - m).pow(2) }.average() })
            val ref = openReference ?: .15
            result["F07.closure80"] = signal(valid.count { it.open!! / ref < .2 }.toDouble() / valid.size)
            result["F07.closure50"] = signal(valid.count { it.open!! / ref < .5 }.toDouble() / valid.size)
            result["F08.longClosures"] = signal(longClosures.size.toDouble())
            result["F09.squint"] = current.mapNotNull { it.squint }.takeIf { it.isNotEmpty() }
                ?.let { signal(it.average(), effectiveQuality * .7) } ?: Signal.missing("blendshape_unavailable")
            result["F10.yawns"] = if (current.any { it.jaw != null }) Signal.observed(yawns.size.toDouble(), effectiveQuality * .4, estimated = true)
                else Signal.missing("jaw_blendshape_unavailable")
            result["F15.gazeMotion"] = current.mapNotNull { it.gazeMotion }.takeIf { it.isNotEmpty() }
                ?.let { signal(it.average()) } ?: Signal.missing("iris_unavailable")
            result["F16.asymmetry"] = signal(valid.mapNotNull { it.asymmetry }.average())
        }
        if (validGaps.isEmpty() || validGaps[validGaps.size / 2] > 150) {
            for (id in listOf("F02.blinksPerMin", "F03.ibiP90Sec", "F03.longestIbiSec",
                "F04.blinkDurationMs", "F05.incompleteRatio", "F08.longClosures"))
                result[id] = Signal.missing("insufficient_temporal_sampling")
        }
        result["F12.headMotion"] = current.mapNotNull { it.headMotion }.takeIf { it.isNotEmpty() }
            ?.let { Signal.observed(it.average(), effectiveQuality * .6, estimated = true) }
            ?: Signal.missing("head_matrix_unavailable")
        result["F13.distanceCm"] = Signal.missing("camera_calibration_unavailable")
        result["F14.noFaceRatio"] = Signal.observed(current.count { it.status in NO_FACE_STATUSES }.toDouble() / current.size,
            estimated = true)
        result["F14.offscreenRest"] = Signal.missing("gaze_screen_calibration_unavailable")
        result["F19.faceIllumination"] = current.mapNotNull { it.faceIllumination }.takeIf { it.isNotEmpty() }
            ?.let { Signal.observed(it.average(), effectiveQuality * .7, estimated = true) }
            ?: Signal.missing("face_roi_unavailable")
        result["F20.blendshapeCount"] = Signal.observed(current.maxOf { it.blendshapeCount }.toDouble())
        result.putAll(cameraSignals)
        for (id in METRICS) if (id !in result) result[id] = Signal.missing("insufficient_eye_observation")
        return result
    }

    private fun lowLightDominant(frames: List<Frame>): Boolean {
        val known = frames.filter { it.camera.rawLumaP50 != null || it.status == LOW_LIGHT_NO_FACE }
        return known.isNotEmpty() &&
            known.count { it.camera.lowLight || it.status == LOW_LIGHT_NO_FACE } * 2 >= known.size
    }

    /** Camera capture conditions for the window. Recorded for validation only; never scored. */
    private fun cameraSignals(frames: List<Frame>): Map<String, Signal> {
        fun median(values: List<Double>) = values.sorted().let { sorted ->
            if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 }
        val out = linkedMapOf<String, Signal>()
        val gaps = frames.zipWithNext { a, b -> (b.at - a.at).toDouble() }.filter { it in 1.0..1000.0 }
        out["C01.cameraFps"] = if (gaps.size >= 2) Signal.observed(1000.0 / median(gaps))
            else Signal.missing("insufficient_camera_frames")
        val gains = frames.map { it.camera.gain }.filter { it.isFinite() && it >= 1.0 }
        out["C02.lowLightGain"] = if (gains.isNotEmpty()) Signal.observed(median(gains))
            else Signal.missing("insufficient_camera_frames")
        out["C03.enhancedShare"] = if (gains.isNotEmpty()) Signal.observed(gains.count { it > 1.05 }.toDouble() / gains.size)
            else Signal.missing("insufficient_camera_frames")
        val ev = frames.mapNotNull { it.camera.ev100?.takeIf(Double::isFinite) }
        out["C04.cameraEv100"] = if (ev.isNotEmpty()) Signal.observed(median(ev), .6, estimated = true)
            else Signal.missing("camera_exposure_metadata_unavailable")
        val luma = frames.mapNotNull { it.camera.rawLumaP50?.takeIf { v -> v.isFinite() && v in 0.0..1.0 } }
        out["C05.lowLightShare"] = if (luma.isNotEmpty())
            Signal.observed(frames.count { it.camera.rawLumaP50 != null && it.camera.lowLight }.toDouble() / luma.size)
            else Signal.missing("raw_luma_unavailable")
        out["C06.rawLumaP50"] = if (luma.isNotEmpty()) Signal.observed(median(luma))
            else Signal.missing("raw_luma_unavailable")
        out["C07.aeTargetFpsMin"] = frames.lastOrNull { it.camera.aeFpsMin != null }?.camera?.aeFpsMin
            ?.let { Signal.observed(it.toDouble()) } ?: Signal.missing("fps_range_unavailable")
        return out
    }

    companion object {
        const val LOW_LIGHT_FACE_NOT_FOUND = "low_light_face_not_found"
        const val LOW_LIGHT_NO_FACE = "low_light_no_face"
        val NO_FACE_STATUSES = setOf("no_face", LOW_LIGHT_NO_FACE)
        val CAMERA_METRICS = listOf("C01.cameraFps", "C02.lowLightGain", "C03.enhancedShare",
            "C04.cameraEv100", "C05.lowLightShare", "C06.rawLumaP50", "C07.aeTargetFpsMin")
        val METRICS = CAMERA_METRICS + listOf("F01.coverage", "F01.detectRatio", "F01.observedMin", "F02.blinksPerMin",
            "F03.ibiP90Sec", "F03.longestIbiSec", "F04.blinkDurationMs", "F05.incompleteRatio",
            "F06.openMean", "F06.openVariance", "F07.closure80", "F07.closure50", "F08.longClosures",
            "F09.squint", "F10.yawns", "F12.headMotion", "F13.distanceCm", "F14.noFaceRatio",
            "F14.offscreenRest", "F15.gazeMotion", "F16.asymmetry", "F19.faceIllumination",
            "F20.blendshapeCount")
    }
}

/**
 * Per-frame camera conditions. [gain] is the digital Y gain applied before landmarks,
 * [landmarkQuality] the caller's quality multiplier for an enhanced frame (1 = none),
 * [rawLumaP50] the pre-enhancement median Y (0–1) and [ev100] the exposure-metadata brightness.
 */
data class CameraFrame(val gain: Double = 1.0, val landmarkQuality: Double = 1.0,
    val rawLumaP50: Double? = null, val ev100: Double? = null, val lowLight: Boolean = false,
    val aeFpsMin: Int? = null)
