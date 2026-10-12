package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.EfsScorer
import com.neurolens.eyecare.engine.Signal
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Explicit aggregate-only contract for a future, consented and authenticated research upload.
 * This class does not send data. No raw image, screen text, app name, or face trace is accepted. */
data class ResearchObservation(
    val id: UUID, val reportId: UUID, val devicePseudonym: UUID,
    val observedAtMs: Long, val consentVersion: String, val algorithmVersion: String,
    val score: Int?, val confidenceCode: String,
    val domainScores: Map<String, Int?>, val domainQualities: Map<String, Double?>,
    val screenChange: Double?, val ambientLux: Double?, val continuousMinutes: Double?,
    val todayMinutes: Double?, val blinkPerMin: Double?, val eyeOpenMean: Double?,
    val darkStimulusMinutes: Double?, val calibratedDistanceCm: Double?,
    val subjectiveFatigueNrs: Int?, val estimatedFields: Set<String>,
    val missingDomainCodes: Map<String, String?>,
    val distanceEstimateCm: Double? = null,
    val distanceEstimateQuality: Double? = null,
    val distanceCoverage: Double? = null,
    val distanceCalibrated: Boolean? = null,
    val distanceValidMinutes: Double? = null,
    val distanceWindowMinutes: Double? = null
)

object ResearchObservationFactory {
    private val domainNames = listOf("screen", "environment", "posture", "usage", "eyes")

    /** Old completed sessions may predate care_reports. Export only a measured screen aggregate.
     * A session duration or 0..4 discomfort answer is not a fatigue score or 0..10 NRS. */
    fun fromLegacySession(session: SessionRecord, devicePseudonym: UUID,
        consentVersion: String): ResearchObservation? {
        if (consentVersion != "research-v1" || session.reason == "active" || session.startedAt <= 0L)
            return null
        val reportId = UUID.nameUUIDFromBytes(
            "eyecare-legacy-session-report-v1:${session.id}".toByteArray(StandardCharsets.UTF_8))
        val change = session.stats.meanChange.takeIf {
            session.stats.validMs > 0 && session.stats.frames > 0 && it.isFinite() && it in 0.0..1.0
        }
        return ResearchObservation(
            id = UUID.nameUUIDFromBytes(
                "eyecare-research-v1:$reportId".toByteArray(StandardCharsets.UTF_8)),
            reportId = reportId, devicePseudonym = devicePseudonym,
            observedAtMs = session.startedAt + session.elapsedMs.coerceAtLeast(0L),
            consentVersion = consentVersion, algorithmVersion = "legacy-session-aggregate-v1",
            score = null, confidenceCode = if (change == null) "unavailable" else "partial",
            domainScores = domainNames.associateWith { null },
            domainQualities = domainNames.associateWith { null },
            screenChange = change, ambientLux = null, continuousMinutes = null,
            todayMinutes = null, blinkPerMin = null, eyeOpenMean = null,
            darkStimulusMinutes = null, calibratedDistanceCm = null,
            subjectiveFatigueNrs = null, estimatedFields = emptySet(),
            missingDomainCodes = domainNames.associateWith { null })
    }

    fun from(report: CareReport, devicePseudonym: UUID, consentVersion: String): ResearchObservation? {
        if (report.version !in setOf(com.neurolens.eyecare.engine.StrainModel.VERSION, com.neurolens.eyecare.engine.StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION, MvpScorer.PREVIOUS_VERSION,
                MvpScorer.LEGACY_VERSION, EfsScorer.VERSION) ||
            consentVersion != "research-v1") return null
        val reportId = runCatching { UUID.fromString(report.id) }.getOrNull() ?: return null
        if (report.version == EfsScorer.VERSION) {
            // Historical EFS reports have no quality or raw feature evidence. Preserve only
            // their actual aggregate scores; missing modern domains remain null.
            return ResearchObservation(
                UUID.nameUUIDFromBytes("eyecare-research-v1:$reportId".toByteArray(StandardCharsets.UTF_8)),
                reportId, devicePseudonym, report.createdAt, consentVersion, report.version,
                report.score.takeIf { report.scoreAvailable && it in 0..100 },
                if (report.scoreAvailable) "partial" else "unavailable",
                domainNames.associateWith { domain -> when (domain) {
                    "screen" -> report.screenScore?.takeIf { it in 0..100 }
                    "usage" -> report.usageScore.takeIf { it in 0..100 }
                    else -> null
                } }, domainNames.associateWith { null },
                null, null, null, null, null, null, null, null,
                report.nrs?.takeIf { !report.subjectiveEventV1 && it in 0..10 }, emptySet(), domainNames.associateWith { null })
        }
        fun value(id: String): Double? = report.mvpSignals[id]?.takeIf {
            it.value?.isFinite() == true && it.quality > 0
        }?.value
        fun bounded(id: String, low: Double, high: Double): Double? =
            value(id)?.takeIf { it in low..high }
        fun estimated(id: String) = report.mvpSignals[id]?.estimated == true && value(id) != null
        fun code(signal: Signal?, allowed: Set<String>): String? {
            if (signal?.value != null) return null
            val reason = signal?.reason ?: return "unknown"
            return reason.takeIf { it in allowed } ?: "unknown"
        }
        val sources = mapOf("screen" to "S17.coverage", "environment" to "E01.lux",
            "posture" to "M01.tiltDeg", "usage" to "U02.todayMin", "eyes" to "F01.coverage")
        val allowedReasons = mapOf(
            "screen" to setOf("no_capture", "permission_missing", "low_quality"),
            "environment" to setOf("sensor_absent", "permission_missing", "low_quality"),
            "posture" to setOf("sensor_absent", "permission_missing", "low_quality"),
            "usage" to setOf("permission_missing", "no_events", "low_quality"),
            "eyes" to setOf("camera_permission_denied", "camera_unavailable", "low_quality"))
        val selected = linkedMapOf(
            "screen_change" to "S06.change", "ambient_lux" to "E01.lux",
            "continuous_minutes" to "U01.continuousMin", "today_minutes" to "U02.todayMin",
            "blink_per_min" to "F02.blinksPerMin", "eye_open_mean" to "F06.openMean",
            "dark_stimulus_minutes" to "X01.darkStimulusMin", "calibrated_distance_cm" to "F13.distanceCm")
        val distanceWindow = if (report.version in setOf(com.neurolens.eyecare.engine.StrainModel.VERSION, com.neurolens.eyecare.engine.StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION, MvpScorer.PREVIOUS_VERSION))
            bounded("D08.windowMinutes", 0.0, 120.0) else null
        val distanceValid = if (distanceWindow != null)
            bounded("D05.validMinutes", 0.0, distanceWindow) else null
        val distanceCoverage = if (report.version in setOf(com.neurolens.eyecare.engine.StrainModel.VERSION, com.neurolens.eyecare.engine.StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION, MvpScorer.PREVIOUS_VERSION))
            bounded("D04.coverage", 0.0, 1.0) else null
        val candidateDistance = if (report.version in setOf(com.neurolens.eyecare.engine.StrainModel.VERSION, com.neurolens.eyecare.engine.StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION, MvpScorer.PREVIOUS_VERSION) &&
            report.mvpSignals["D01.distanceEstimateCm"]?.estimated == true)
            bounded("D01.distanceEstimateCm", 15.0, 80.0) else null
        // D01.quality already includes the frame quality multiplied by time coverage.
        // Do not multiply by D04 again when exporting or analysing this value.
        val candidateQuality = report.mvpSignals["D01.distanceEstimateCm"]?.quality
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val calibrated = when (value("D06.calibrated")) {
            1.0 -> true
            0.0 -> false
            else -> null
        }
        val distanceCm = candidateDistance?.takeIf { candidateQuality != null && calibrated != null }
        return ResearchObservation(
            UUID.nameUUIDFromBytes("eyecare-research-v1:$reportId".toByteArray(StandardCharsets.UTF_8)),
            reportId, devicePseudonym, report.createdAt, consentVersion, report.version,
            report.score.takeIf { report.scoreAvailable },
            when { !report.scoreAvailable -> "unavailable"; report.confidence.startsWith("보통") -> "adequate"; else -> "partial" },
            domainNames.associateWith { report.mvpDomains[it] },
            domainNames.associateWith { report.domainQualities[it] },
            bounded("S06.change", 0.0, 1.0), bounded("E01.lux", 0.0, 200000.0),
            bounded("U01.continuousMin", 0.0, 1440.0), bounded("U02.todayMin", 0.0, 1440.0),
            bounded("F02.blinksPerMin", 0.0, 120.0), bounded("F06.openMean", 0.0, 1.0),
            bounded("X01.darkStimulusMin", 0.0, 10.0), bounded("F13.distanceCm", 1.0, 200.0)
                .takeIf { (report.mvpSignals["F13.distanceCm"]?.quality ?: 0.0) >= .75 },
            report.nrs?.takeIf { !report.subjectiveEventV1 && it in 0..10 },
            selected.filter { estimated(it.value) }.keys.toSet() +
                if (distanceCm != null) setOf("distance_estimate_cm") else emptySet(),
            domainNames.associateWith { domain ->
                if (report.mvpDomains[domain] != null) null
                else code(report.mvpSignals[sources.getValue(domain)], allowedReasons.getValue(domain))
            }, distanceCm, candidateQuality?.takeIf { distanceCm != null }, distanceCoverage,
            calibrated?.takeIf { distanceCm != null }, distanceValid, distanceWindow)
    }
}
