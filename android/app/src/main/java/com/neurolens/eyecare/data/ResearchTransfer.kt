package com.neurolens.eyecare.data

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/** A deliberately narrow, aggregate-only interchange format. It never reads raw captures. */
object ResearchTransfer {
    private val domainNames = listOf("screen", "environment", "posture", "usage", "eyes")
    private val numericNames = mapOf(
        "screen_change" to ResearchObservation::screenChange,
        "ambient_lux" to ResearchObservation::ambientLux,
        "continuous_minutes" to ResearchObservation::continuousMinutes,
        "today_minutes" to ResearchObservation::todayMinutes,
        "blink_per_min" to ResearchObservation::blinkPerMin,
        "eye_open_mean" to ResearchObservation::eyeOpenMean,
        "dark_stimulus_minutes" to ResearchObservation::darkStimulusMinutes,
        "calibrated_distance_cm" to ResearchObservation::calibratedDistanceCm,
        "distance_estimate_cm" to ResearchObservation::distanceEstimateCm,
        "distance_estimate_quality" to ResearchObservation::distanceEstimateQuality,
        "distance_coverage" to ResearchObservation::distanceCoverage,
        "distance_valid_minutes" to ResearchObservation::distanceValidMinutes,
        "distance_window_minutes" to ResearchObservation::distanceWindowMinutes
    )

    fun row(item: ResearchObservation, ownerId: UUID? = null): JSONObject = JSONObject().apply {
        put("id", item.id.toString())
        if (ownerId != null) put("owner_id", ownerId.toString())
        put("report_id", item.reportId.toString())
        put("device_pseudonym", item.devicePseudonym.toString())
        put("observed_at", Instant.ofEpochMilli(item.observedAtMs).toString())
        put("consent_version", item.consentVersion)
        put("algorithm_version", item.algorithmVersion)
        put("score", item.score ?: JSONObject.NULL)
        put("confidence", item.confidenceCode)
        for (domain in domainNames) {
            put("${domain}_score", item.domainScores[domain] ?: JSONObject.NULL)
            put("${domain}_quality", item.domainQualities[domain] ?: JSONObject.NULL)
            put("${domain}_missing_code", item.missingDomainCodes[domain] ?: JSONObject.NULL)
        }
        for ((name, getter) in numericNames) put(name, getter(item) ?: JSONObject.NULL)
        put("distance_calibrated", item.distanceCalibrated ?: JSONObject.NULL)
        put("subjective_fatigue_nrs", item.subjectiveFatigueNrs ?: JSONObject.NULL)
        put("estimated_fields", JSONArray(item.estimatedFields.sorted()))
    }

    fun responseRow(item: SubjectiveResponse, ownerId: UUID): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("owner_id", ownerId.toString())
        put("report_id", item.reportId)
        put("subjective_fatigue_nrs", item.nrs)
        put("consent_version", "research-v1")
        put("answered_at", Instant.ofEpochMilli(item.answeredAt).toString())
        if (item.previousResponseId != null) put("previous_response_id", item.previousResponseId)
        // received_at belongs to the server and is intentionally never sent.
    }

    fun prepare(context: Context, report: CareReport, devicePseudonym: UUID): File {
        val item = requireNotNull(ResearchObservationFactory.from(report, devicePseudonym, "research-v1")) {
            "This report cannot be represented by the research-v1 aggregate schema."
        }
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(folder, "eyecare-research-${item.reportId}.json")
        val document = JSONObject().put("format", "eyecare-research-aggregate")
            .put("schemaVersion", 1).put("observation", row(item))
        file.writeText(document.toString(2), Charsets.UTF_8)
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("EyeCare research aggregate", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

class ResearchHttpException(val status: Int) : Exception("Research HTTP $status")

/** Narrow HTTPS client. Response bodies and bearer credentials are never logged or surfaced in errors. */
class SupabaseResearchTransport(private val projectUrl: String, private val publishableKey: String) {
    init {
        val url = URL(projectUrl)
        require(url.protocol == "https" && url.host.endsWith(".supabase.co") &&
            (url.path.isEmpty() || url.path == "/") && url.port == -1 &&
            url.userInfo == null && url.query == null && url.ref == null)
        require(publishableKey.isNotBlank())
    }

    fun request(method: String, path: String, accessToken: String?, body: JSONObject? = null): String {
        require(method in setOf("GET", "POST", "PATCH"))
        require(path.startsWith("/auth/v1/") || path.startsWith("/rest/v1/research_consent") ||
            path.startsWith("/rest/v1/research_observations") ||
            path.startsWith("/rest/v1/research_subjective_responses") ||
            path.startsWith("/rest/v1/research_browser_grants"))
        val connection = (URL(projectUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("apikey", publishableKey)
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (path.startsWith("/rest/v1/")) setRequestProperty("Prefer", "return=minimal")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw ResearchHttpException(status)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun insert(ownerId: UUID, accessToken: String, item: ResearchObservation): Int {
        require(accessToken.isNotBlank())
        require(item.consentVersion == "research-v1")
        request("POST", "/rest/v1/research_observations", accessToken, ResearchTransfer.row(item, ownerId))
        return 201
    }
}
