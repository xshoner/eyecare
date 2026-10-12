package com.neurolens.eyecare.data

import org.json.JSONObject

/** Local-only, bounded session measurements. No raw frames, account identifiers or credentials. */
data class SessionDiagnostics(
    val cameraFps: Double? = null,
    val frameGapMeanMs: Double? = null,
    val frameGapMaxMs: Long? = null,
    val cameraProcessingMeanMs: Double? = null,
    val cameraProcessingMaxMs: Double? = null,
    val screenFps: Double? = null,
    val screenProcessingMeanMs: Double? = null,
    val screenProcessingMaxMs: Double? = null,
    val eyeCoverage: Double? = null,
    val distanceCoverage: Double? = null,
    val cameraFrames: Int = 0,
    val distanceSamples: Int = 0,
    val maxThermalStatus: Int? = null,
    val thermalPausedMs: Long = 0,
    val syncPending: Int? = null,
    val syncFailed: Int? = null,
    val syncReasonCode: String? = null,
    val appVersion: String? = null,
    val engineVersion: String? = null
) {
    fun encode() = JSONObject().apply {
        fun optional(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
        optional("cameraFps", cameraFps); optional("frameGapMeanMs", frameGapMeanMs)
        optional("frameGapMaxMs", frameGapMaxMs); optional("cameraProcessingMeanMs", cameraProcessingMeanMs)
        optional("cameraProcessingMaxMs", cameraProcessingMaxMs); optional("screenFps", screenFps)
        optional("screenProcessingMeanMs", screenProcessingMeanMs)
        optional("screenProcessingMaxMs", screenProcessingMaxMs)
        optional("eyeCoverage", eyeCoverage); optional("distanceCoverage", distanceCoverage)
        put("cameraFrames", cameraFrames); put("distanceSamples", distanceSamples)
        optional("maxThermalStatus", maxThermalStatus); put("thermalPausedMs", thermalPausedMs)
        optional("syncPending", syncPending); optional("syncFailed", syncFailed)
        optional("syncReasonCode", syncReasonCode); optional("appVersion", appVersion)
        optional("engineVersion", engineVersion)
    }

    companion object {
        fun decode(j: JSONObject) = SessionDiagnostics(
            j.optDouble("cameraFps").takeIf { j.has("cameraFps") && !j.isNull("cameraFps") },
            j.optDouble("frameGapMeanMs").takeIf { j.has("frameGapMeanMs") && !j.isNull("frameGapMeanMs") },
            j.optLong("frameGapMaxMs").takeIf { j.has("frameGapMaxMs") && !j.isNull("frameGapMaxMs") },
            j.optDouble("cameraProcessingMeanMs").takeIf { j.has("cameraProcessingMeanMs") && !j.isNull("cameraProcessingMeanMs") },
            j.optDouble("cameraProcessingMaxMs").takeIf { j.has("cameraProcessingMaxMs") && !j.isNull("cameraProcessingMaxMs") },
            j.optDouble("screenFps").takeIf { j.has("screenFps") && !j.isNull("screenFps") },
            j.optDouble("screenProcessingMeanMs").takeIf { j.has("screenProcessingMeanMs") && !j.isNull("screenProcessingMeanMs") },
            j.optDouble("screenProcessingMaxMs").takeIf { j.has("screenProcessingMaxMs") && !j.isNull("screenProcessingMaxMs") },
            j.optDouble("eyeCoverage").takeIf { j.has("eyeCoverage") && !j.isNull("eyeCoverage") },
            j.optDouble("distanceCoverage").takeIf { j.has("distanceCoverage") && !j.isNull("distanceCoverage") },
            j.optInt("cameraFrames"), j.optInt("distanceSamples"),
            j.optInt("maxThermalStatus").takeIf { j.has("maxThermalStatus") && !j.isNull("maxThermalStatus") },
            j.optLong("thermalPausedMs"),
            j.optInt("syncPending").takeIf { j.has("syncPending") && !j.isNull("syncPending") },
            j.optInt("syncFailed").takeIf { j.has("syncFailed") && !j.isNull("syncFailed") },
            j.optString("syncReasonCode").takeIf { j.has("syncReasonCode") && !j.isNull("syncReasonCode") },
            j.optString("appVersion").takeIf { j.has("appVersion") && !j.isNull("appVersion") },
            j.optString("engineVersion").takeIf { j.has("engineVersion") && !j.isNull("engineVersion") })
    }
}

/** Constant-memory counters; snapshots are persisted with the existing low-frequency session save. */
class SessionDiagnosticsAccumulator(private val startedAtMs: Long) {
    private var frames = 0
    private var lastCameraAt = -1L
    private var previousEyeValid = false
    private var gapCount = 0
    private var gapTotal = 0L
    private var gapMax = 0L
    private var activeGapCount = 0
    private var activeGapTotal = 0L
    private var processingTotal = 0.0
    private var processingMax = 0.0
    private var eyeValidMs = 0L
    private var distanceSamples = 0
    private var lastDistanceAt = -1L
    private var previousDistanceValid = false
    private var distanceValidMs = 0L
    private var thermalAt = -1L
    private var previousThermalPaused = false
    private var thermalPausedMs = 0L
    private var maxThermal: Int? = null

    fun camera(atMs: Long, eyeValid: Boolean, processingMs: Double) {
        if (lastCameraAt >= atMs) return
        val gap = atMs - lastCameraAt
        if (lastCameraAt >= 0) {
            gapCount++; gapTotal += gap; gapMax = maxOf(gapMax, gap)
            if (gap in 1..1000) {
                activeGapCount++; activeGapTotal += gap
                if (eyeValid && previousEyeValid) eyeValidMs += gap
            }
        }
        lastCameraAt = atMs; previousEyeValid = eyeValid; frames++
        if (processingMs.isFinite() && processingMs >= 0) {
            processingTotal += processingMs; processingMax = maxOf(processingMax, processingMs)
        }
    }

    fun distance(atMs: Long, valid: Boolean) {
        if (lastDistanceAt >= atMs) return
        val gap = atMs - lastDistanceAt
        if (lastDistanceAt >= 0 && gap in 1..2000 && valid && previousDistanceValid)
            distanceValidMs += gap
        lastDistanceAt = atMs; previousDistanceValid = valid; distanceSamples++
    }

    fun thermal(atMs: Long, status: Int, paused: Boolean) {
        if (thermalAt >= 0 && atMs > thermalAt && previousThermalPaused)
            thermalPausedMs += atMs - thermalAt
        thermalAt = atMs; previousThermalPaused = paused
        maxThermal = maxOf(maxThermal ?: status, status)
    }

    fun snapshot(nowMs: Long, screenFrames: Int, screenProcessingTotalMs: Double,
        screenProcessingMaxMs: Double, sync: ResearchUploadState?, appVersion: String,
        engineVersion: String): SessionDiagnostics {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(1)
        val paused = thermalPausedMs + if (previousThermalPaused && thermalAt >= 0)
            (nowMs - thermalAt).coerceAtLeast(0) else 0L
        return SessionDiagnostics(
            cameraFps = activeGapTotal.takeIf { it > 0 }?.let { activeGapCount * 1000.0 / it },
            frameGapMeanMs = gapCount.takeIf { it > 0 }?.let { gapTotal.toDouble() / it },
            frameGapMaxMs = gapMax.takeIf { gapCount > 0 },
            cameraProcessingMeanMs = frames.takeIf { it > 0 }?.let { processingTotal / it },
            cameraProcessingMaxMs = processingMax.takeIf { frames > 0 },
            screenFps = screenFrames.takeIf { it > 0 }?.let { it * 1000.0 / elapsed },
            screenProcessingMeanMs = screenFrames.takeIf { it > 0 }?.let { screenProcessingTotalMs / it },
            screenProcessingMaxMs = screenProcessingMaxMs.takeIf { screenFrames > 0 },
            eyeCoverage = frames.takeIf { it > 1 }?.let { (eyeValidMs.toDouble() / elapsed).coerceIn(0.0, 1.0) },
            distanceCoverage = distanceSamples.takeIf { it > 1 }?.let { (distanceValidMs.toDouble() / elapsed).coerceIn(0.0, 1.0) },
            cameraFrames = frames, distanceSamples = distanceSamples, maxThermalStatus = maxThermal,
            thermalPausedMs = paused, syncPending = sync?.pending, syncFailed = sync?.failed,
            syncReasonCode = sync?.reasonCode, appVersion = appVersion, engineVersion = engineVersion)
    }
}
