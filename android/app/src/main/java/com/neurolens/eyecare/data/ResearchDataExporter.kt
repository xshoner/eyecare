package com.neurolens.eyecare.data

import android.content.Context
import com.neurolens.eyecare.engine.EyeCondition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Whole local research dataset for offline validation: every report's stored score, domain
 * aggregates and full signal vector (value, quality, missing reason), with every NRS answer.
 * It is written only to the app cache and leaves the phone only when the user saves or shares it.
 * Screen and camera frames are never stored, so they cannot be included.
 */
object ResearchDataExporter {
    const val FORMAT = "neurolens-eyecare-research-dataset"
    const val FORMAT_VERSION = 1
    private val DOMAINS = listOf("screen", "environment", "posture", "usage", "eyes")

    fun csv(reports: List<CareReport>, responses: List<SubjectiveResponse>,
        zone: ZoneId = ZoneId.systemDefault()): String {
        val ordered = reports.sortedWith(compareBy<CareReport> { it.createdAt }.thenBy { it.id })
        val signalIds = ordered.flatMap { it.mvpSignals.keys }.toSortedSet()
        val answers = responses.groupBy { it.reportId }
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        fun at(ms: Long?) = ms?.let { time.format(Instant.ofEpochMilli(it)) }
        val header = listOf("report_id", "session_id", "created_at", "window_start_at", "interval_min",
            "algorithm_version", "score_available", "burden_score", "condition_score", "comparable",
            "confidence", "available_weight", "base_score", "interaction_rate", "observed_burden",
            "observed_minutes", "screen_coverage", "eye_detection_ratio", "camera_fps",
            "nrs_first", "nrs_first_answered_at", "nrs_latest", "nrs_latest_answered_at", "nrs_count") +
            DOMAINS.flatMap { listOf("${it}_risk", "${it}_quality", "${it}_contribution") } +
            signalIds.flatMap { listOf(it, "$it.quality", "$it.reason") }
        val rows = ordered.map { r ->
            val own = answers[r.id].orEmpty().sortedWith(compareBy<SubjectiveResponse> { it.answeredAt }.thenBy { it.id })
            // Answers before 0.7.3 lived on the report itself; keep them as a single answer.
            val first = own.firstOrNull()?.let { it.nrs to it.answeredAt } ?: r.nrs?.let { it to r.nrsAt }
            val latest = own.lastOrNull()?.let { it.nrs to it.answeredAt } ?: r.nrs?.let { it to r.nrsAt }
            val score = r.score.takeIf { r.scoreAvailable }
            listOf(r.id, r.sessionId, at(r.createdAt), at(r.windowStartAt), r.intervalMin,
                r.version, r.scoreAvailable, score, EyeCondition.fromBurden(score), PersonalTrend.comparable(r),
                r.confidence, r.availableWeight, r.baseScore, r.interactionRate, r.observedBurden,
                r.observedMinutes, r.screenCoverage, r.eyeDetectionRatio, r.cameraProcessedFps,
                first?.first, at(first?.second), latest?.first, at(latest?.second),
                own.size.takeIf { it > 0 } ?: if (r.nrs != null) 1 else 0) +
                DOMAINS.flatMap { listOf(r.mvpDomains[it], r.domainQualities[it], r.contributions[it]) } +
                signalIds.flatMap { id ->
                    val s = r.mvpSignals[id]
                    listOf(s?.value, if (s?.value != null) s.quality else null, s?.reason)
                }
        }
        return (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }

    fun json(sessions: List<SessionRecord>, reports: List<CareReport>, responses: List<SubjectiveResponse>,
        events: List<CareEvent>, appVersion: String, exportedAt: Long = System.currentTimeMillis()) = JSONObject().apply {
        put("format", FORMAT); put("formatVersion", FORMAT_VERSION)
        put("appVersion", appVersion); put("exportedAt", exportedAt)
        put("note", "score는 저장 부담 점수(높을수록 부담)입니다. 화면 표시용 눈 컨디션은 100 - score입니다.")
        put("sessions", JSONArray().apply { sessions.filter { it.reason != "active" }.forEach { put(SessionCodec.encode(it)) } })
        put("careReports", JSONArray().apply { reports.forEach { put(CareReportCodec.encode(it)) } })
        put("subjectiveResponses", JSONArray().apply { responses.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("reportId", s.reportId); put("nrs", s.nrs); put("answeredAt", s.answeredAt)
            put("previousResponseId", s.previousResponseId ?: JSONObject.NULL)
            put("receivedAt", s.receivedAt ?: JSONObject.NULL)
        }) } })
        put("careEvents", JSONArray().apply { events.forEach { put(CareEventCodec.encode(it)) } })
    }

    /** Reads the whole local store in pages and writes both files to the share cache. */
    fun prepare(context: Context, store: SessionStore, appVersion: String): Pair<File, File> {
        val reports = generateSequence(0) { it + 500 }
            .map { store.reportPage(it, 500) }.takeWhile { it.isNotEmpty() }.flatten().toList()
        require(reports.isNotEmpty()) { "내보낼 리포트가 아직 없어요." }
        val responses = store.subjectiveResponses(100_000)
        val sessions = store.all(10_000)
        val events = sessions.map { it.id }.chunked(500).flatMap { store.events(it.toSet()) }
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val stem = "eyecare-research-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val csvFile = File(folder, "$stem.csv")
        // The BOM lets spreadsheet apps read Korean text as UTF-8.
        csvFile.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            csv(reports, responses).toByteArray(Charsets.UTF_8))
        val jsonFile = File(folder, "$stem.json")
        jsonFile.writeText(json(sessions, reports, responses, events, appVersion).toString(2), Charsets.UTF_8)
        return csvFile to jsonFile
    }

    private fun cell(value: Any?): String {
        val text = when (value) {
            null -> ""
            is Double -> if (value.isFinite()) value.toBigDecimal().stripTrailingZeros().toPlainString() else ""
            else -> value.toString()
        }
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
