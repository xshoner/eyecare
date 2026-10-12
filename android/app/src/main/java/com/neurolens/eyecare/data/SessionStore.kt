package com.neurolens.eyecare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.neurolens.eyecare.capture.EyeObservation
import com.neurolens.eyecare.engine.GazeWindow
import com.neurolens.eyecare.engine.ExposureBin
import com.neurolens.eyecare.engine.ExposureStats
import com.neurolens.eyecare.engine.QualityWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Feedback(val score: Int?, val at: Long, val skipped: Boolean)
data class SessionRecord(
    val id: String = UUID.randomUUID().toString(), val startedAt: Long = System.currentTimeMillis(),
    val elapsedMs: Long = 0, val stats: ExposureStats = ExposureStats(),
    val reason: String = "active", val discomfort: Int? = null,
    val discomfortBefore: Int? = null, val content: String = "unspecified",
    val device: String = "unknown", val sdk: Int = 0,
    val appVersion: String = "0.1.0-alpha", val engineVersion: String = "exposure-0.1",
    val revision: Int = 0, val feedback: Map<String, Feedback> = emptyMap(),
    val restStartedAt: Long? = null,
    val diagnostics: SessionDiagnostics? = null
)
data class EyeLive(val cameraState: String = "not_started", val frames: Int = 0,
    val eyeValidFrames: Int = 0, val gazeValidFrames: Int = 0,
    val lastOpen: Double? = null, val lastGaze: GazeWindow? = null,
    val lastFrameElapsedMs: Long? = null, val frameGapMs: Long? = null,
    val processingMs: Double? = null, val faceIllumination: Double? = null,
    val distanceCm: Double? = null, val distanceQuality: Double = 0.0,
    val distanceReason: String? = null, val distanceCoverage: Double = 0.0,
    val distanceJitterCm: Double? = null, val deliveredFps: Double = 0.0,
    val thermalStatus: Int? = null, val thermalHeadroom: Float? = null,
    val lastObservationStatus: String? = null, val recentEyeCoverage: Double? = null,
    val qualityCode: String? = null, val qualityAction: String? = null,
    /** Latest frame was dark before enhancement (raw median Y < LowLightPolicy.LOW_LIGHT_P50). */
    val lowLight: Boolean = false, val lowLightGain: Double = 1.0,
    val rawLumaP50: Double? = null, val cameraEv100: Double? = null)

data class LiveSession(val record: SessionRecord, val running: Boolean = true, val reminderMuted: Boolean = false,
    val nextReportMs: Long = 0, val efs: com.neurolens.eyecare.engine.EfsResult? = null,
    val screenCaptureActive: Boolean = true, val eye: EyeLive = EyeLive(),
    val observedContext: ObservedContextSummary? = null, val lux: Double? = null,
    val strain: StrainLive? = null)

/** What the live screen shows from the deduction model. Rates are points per minute. */
data class StrainLive(val condition: Int, val ratePerMin: Double,
    val factors: List<Pair<String, Double>>, val comboLabel: String?, val stimulusLabel: String?,
    val nextBandMinutes: Int?, val nextBandLabel: String?, val breakIntervalMin: Int,
    val continuousMin: Double, val tips: List<String>)

class SessionStore(context: Context, databaseName: String = "eyecare.db") : SQLiteOpenHelper(context, databaseName, null, 5) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE care_reports (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE care_events (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE eye_dense (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX eye_dense_session_time ON eye_dense(session_id, created)")
        db.execSQL("CREATE TABLE subjective_responses (id TEXT PRIMARY KEY, report_id TEXT NOT NULL, nrs INTEGER NOT NULL, answered INTEGER NOT NULL, previous_id TEXT, received INTEGER)")
        db.execSQL("CREATE INDEX subjective_report_time ON subjective_responses(report_id, answered)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE care_reports (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        if (oldVersion < 3) db.execSQL("CREATE TABLE care_events (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE eye_dense (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
            db.execSQL("CREATE INDEX eye_dense_session_time ON eye_dense(session_id, created)")
        }
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE subjective_responses (id TEXT PRIMARY KEY, report_id TEXT NOT NULL, nrs INTEGER NOT NULL, answered INTEGER NOT NULL, previous_id TEXT, received INTEGER)")
            db.execSQL("CREATE INDEX subjective_report_time ON subjective_responses(report_id, answered)")
        }
    }
    @Synchronized fun save(record: SessionRecord) {
        val values = ContentValues().apply {
            put("id", record.id); put("started", record.startedAt); put("data", encode(record).toString())
        }
        writableDatabase.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun all(limit: Int = 100): List<SessionRecord> = readableDatabase.rawQuery("SELECT data FROM sessions ORDER BY started DESC LIMIT ?", arrayOf(limit.coerceIn(1, 10000).toString())).use { c ->
        buildList { while (c.moveToNext()) runCatching { SessionCodec.decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
    }
    @Synchronized fun sessionPage(offset: Int, limit: Int = 200): List<SessionRecord> = readableDatabase.rawQuery(
        "SELECT data FROM sessions ORDER BY started ASC, id ASC LIMIT ? OFFSET ?",
        arrayOf(limit.coerceIn(1, 500).toString(), offset.coerceAtLeast(0).toString())).use { c ->
        buildList { while (c.moveToNext()) runCatching { SessionCodec.decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
    }
    @Synchronized fun saveReport(report: CareReport) {
        val values = ContentValues().apply {
            put("id", report.id); put("session_id", report.sessionId)
            put("created", report.createdAt); put("data", CareReportCodec.encode(report).toString())
        }
        writableDatabase.insertWithOnConflict("care_reports", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun reports(limit: Int = 1000): List<CareReport> = readableDatabase.rawQuery(
        "SELECT data FROM care_reports ORDER BY created DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
        buildList { while (c.moveToNext()) runCatching { CareReportCodec.decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
    }
    @Synchronized fun reportPage(offset: Int, limit: Int = 200): List<CareReport> = readableDatabase.rawQuery(
        "SELECT data FROM care_reports ORDER BY created ASC, id ASC LIMIT ? OFFSET ?",
        arrayOf(limit.coerceIn(1, 500).toString(), offset.coerceAtLeast(0).toString())).use { c ->
        buildList { while (c.moveToNext()) runCatching { CareReportCodec.decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
    }
    @Synchronized fun addSubjectiveResponse(response: SubjectiveResponse) {
        val values = ContentValues().apply {
            put("id", response.id); put("report_id", response.reportId); put("nrs", response.nrs)
            put("answered", response.answeredAt); put("previous_id", response.previousResponseId)
            putNull("received")
        }
        writableDatabase.insertWithOnConflict("subjective_responses", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun subjective(c: android.database.Cursor) = SubjectiveResponse(
        c.getString(0), c.getString(1), c.getInt(2), c.getLong(3),
        c.getString(4), if (c.isNull(5)) null else c.getLong(5))
    @Synchronized fun subjectiveResponses(limit: Int = 10000): List<SubjectiveResponse> =
        readableDatabase.rawQuery("SELECT id,report_id,nrs,answered,previous_id,received FROM subjective_responses ORDER BY answered ASC,id ASC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100000).toString())).use { c ->
            buildList { while (c.moveToNext()) add(subjective(c)) }
        }
    @Synchronized fun pendingSubjectiveResponses(limit: Int = 500): List<SubjectiveResponse> =
        readableDatabase.rawQuery("SELECT id,report_id,nrs,answered,previous_id,received FROM subjective_responses WHERE received IS NULL ORDER BY answered ASC,id ASC LIMIT ?",
            arrayOf(limit.coerceIn(1, 1000).toString())).use { c ->
            buildList { while (c.moveToNext()) add(subjective(c)) }
        }
    @Synchronized fun latestSubjectiveResponse(reportId: String): SubjectiveResponse? =
        readableDatabase.rawQuery("SELECT id,report_id,nrs,answered,previous_id,received FROM subjective_responses WHERE report_id=? ORDER BY answered DESC,id DESC LIMIT 1",
            arrayOf(reportId)).use { c -> if (c.moveToFirst()) subjective(c) else null }
    @Synchronized fun markSubjectiveReceived(id: String, atMs: Long) {
        require(atMs > 0)
        val values = ContentValues().apply { put("received", atMs) }
        writableDatabase.update("subjective_responses", values, "id=? AND received IS NULL", arrayOf(id))
    }
    @Synchronized fun saveEvent(event: CareEvent) {
        val values = ContentValues().apply {
            put("id", event.id); put("session_id", event.sessionId)
            put("created", event.at); put("data", CareEventCodec.encode(event).toString())
        }
        writableDatabase.insertWithOnConflict("care_events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun saveEyeObservation(sessionId: String, observation: EyeObservation) {
        val encoded = JSONObject().apply {
            put("at", observation.wallAt); put("capturedElapsedMs", observation.capturedAtMs)
            put("cameraTimestampNs", observation.cameraTimestampNs); put("status", observation.status)
            observation.geometry?.let { g ->
                put("openA", g.eyeAOpen); put("openB", g.eyeBOpen)
                put("uA", g.eyeAU ?: JSONObject.NULL); put("vA", g.eyeAV ?: JSONObject.NULL)
                put("uB", g.eyeBU ?: JSONObject.NULL); put("vB", g.eyeBV ?: JSONObject.NULL)
            }
            observation.gaze?.let { g ->
                put("gazeState", g.state); put("stdU", g.stdU ?: JSONObject.NULL)
                put("stdV", g.stdV ?: JSONObject.NULL); put("rapidMovesPerMin", g.rapidMovesPerMin ?: JSONObject.NULL)
            }
            put("landmarks", JSONArray().apply { observation.landmarks.forEach { (index, point) ->
                put(JSONArray().put(index).put(point.x.toDouble()).put(point.y.toDouble()).put(point.z.toDouble()))
            } })
            put("blendshapes", JSONObject(observation.blendshapes))
            put("headMatrix", JSONArray(observation.headMatrix))
            put("faceIllumination", observation.faceIllumination ?: JSONObject.NULL)
        }
        val values = ContentValues().apply {
            put("session_id", sessionId); put("created", observation.wallAt); put("data", encoded.toString())
        }
        writableDatabase.insert("eye_dense", null, values)
    }
    @Synchronized fun saveEyeObservations(sessionId: String, observations: List<EyeObservation>) {
        if (observations.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            observations.forEach { saveEyeObservation(sessionId, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun eyeDense(sessionId: String, limit: Int = 100000): List<String> = readableDatabase.rawQuery(
        "SELECT data FROM eye_dense WHERE session_id=? ORDER BY created LIMIT ?",
        arrayOf(sessionId, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun eachEyeObservation(sessionId: String, consume: (String) -> Unit): Int {
        var count = 0
        readableDatabase.rawQuery("SELECT data FROM eye_dense WHERE session_id=? ORDER BY created",
            arrayOf(sessionId)).use { c -> while (c.moveToNext()) { consume(c.getString(0)); count++ } }
        return count
    }
    @Synchronized fun events(sessionIds: Set<String>): List<CareEvent> {
        if (sessionIds.isEmpty()) return emptyList()
        val args = sessionIds.toTypedArray()
        return readableDatabase.rawQuery("SELECT data FROM care_events WHERE session_id IN (${args.joinToString { "?" }}) ORDER BY created", args).use { c ->
            buildList { while (c.moveToNext()) runCatching { CareEventCodec.decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
        }
    }
    /** Bounded recent local events for the descriptive rest dashboard; no remote upload. */
    @Synchronized fun recentRestEvents(limit: Int = 2000): List<CareEvent> = readableDatabase.rawQuery(
        "SELECT data FROM care_events ORDER BY created DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 2000).toString())).use { c -> buildList {
            while (c.moveToNext()) runCatching { CareEventCodec.decode(JSONObject(c.getString(0))) }
                .getOrNull()?.takeIf { it.type.startsWith("rest_alert_") ||
                    it.type in setOf("rest_confirmed", "rest_declined") }?.let(::add)
        } }
    @Synchronized fun clear() {
        writableDatabase.delete("subjective_responses", null, null)
        writableDatabase.delete("eye_dense", null, null)
        writableDatabase.delete("care_events", null, null)
        writableDatabase.delete("care_reports", null, null)
        writableDatabase.delete("sessions", null, null)
    }
    @Synchronized fun prune() {
        // Research alpha retains source rows for longitudinal analysis.
    }
    private fun encode(r: SessionRecord) = SessionCodec.encode(r)
}

object SessionCodec {
    private fun longs(j: JSONObject?) = j?.keys()?.asSequence()?.associateWith { j.getLong(it) } ?: emptyMap()
    fun encode(r: SessionRecord) = JSONObject().apply {
        put("id", r.id); put("started", r.startedAt); put("elapsed", r.elapsedMs); put("reason", r.reason)
        put("discomfort", r.discomfort ?: JSONObject.NULL); put("engine", r.engineVersion)
        put("before", r.discomfortBefore ?: JSONObject.NULL); put("content", r.content)
        put("device", r.device); put("sdk", r.sdk); put("appVersion", r.appVersion); put("revision", r.revision)
        put("scope", "user_selected"); put("valid", r.stats.validMs); put("high", r.stats.highMs)
        put("frames", r.stats.frames); put("mean", r.stats.meanChange); put("quality", r.stats.quality)
        put("bins", JSONArray().apply { r.stats.bins.forEach { b -> put(JSONArray(listOf(b.seconds, b.validMs, b.change, b.highMs))) } })
        put("excludedMs", JSONObject(r.stats.excludedMs))
        put("meanIntervalMs", r.stats.meanIntervalMs); put("maxIntervalMs", r.stats.maxIntervalMs)
        put("meanGlobalChange", r.stats.meanGlobalChange); put("meanChangedArea", r.stats.meanChangedArea)
        put("processingCount", r.stats.processingCount); put("processingTotalMs", r.stats.processingTotalMs)
        put("processingMaxMs", r.stats.processingMaxMs)
        if (r.engineVersion.startsWith("exposure-0.2.1")) {
            put("diagnosticsVersion", 1)
            put("qualityWindows", JSONArray().apply { r.stats.qualityWindows.forEach { w -> put(JSONArray().put(w.seconds).put(JSONObject(w.excludedMs))) } })
            put("counters", JSONObject(r.stats.counters)); put("intervalBuckets", JSONObject(r.stats.intervalBuckets))
            put("captureCounters", JSONObject(r.stats.captureCounters))
        }
        put("feedback", JSONObject().apply { r.feedback.forEach { (stage, f) -> put(stage, JSONObject().put("score", f.score ?: JSONObject.NULL).put("at", f.at).put("skipped", f.skipped)) } })
        put("restStartedAt", r.restStartedAt ?: JSONObject.NULL)
        r.diagnostics?.let { put("diagnostics", it.encode()) }
    }
    fun decode(j: JSONObject): SessionRecord {
        val a = j.optJSONArray("bins") ?: JSONArray()
        val bins = (0 until a.length()).map { a.getJSONArray(it).let { b -> ExposureBin(b.getInt(0), b.getLong(1), b.getDouble(2), b.getLong(3)) } }
        val excluded = j.optJSONObject("excludedMs")
        val windows = j.optJSONArray("qualityWindows") ?: JSONArray()
        val feedback = j.optJSONObject("feedback")
        return SessionRecord(j.getString("id"), j.getLong("started"), j.getLong("elapsed"),
            ExposureStats(j.getLong("valid"), j.getLong("high"), j.getInt("frames"), j.getDouble("mean"),
                quality = j.optString("quality", "finished"), bins = bins,
                excludedMs = excluded?.keys()?.asSequence()?.associateWith { excluded.getLong(it) } ?: emptyMap(),
                meanIntervalMs = j.optDouble("meanIntervalMs", 0.0), maxIntervalMs = j.optLong("maxIntervalMs"),
                meanGlobalChange = j.optDouble("meanGlobalChange", 0.0), meanChangedArea = j.optDouble("meanChangedArea", 0.0),
                processingCount = j.optInt("processingCount"), processingTotalMs = j.optDouble("processingTotalMs", 0.0),
                processingMaxMs = j.optDouble("processingMaxMs", 0.0),
                qualityWindows = (0 until windows.length()).map { windows.getJSONArray(it).let { w -> QualityWindow(w.getInt(0), longs(w.getJSONObject(1))) } },
                counters = longs(j.optJSONObject("counters")), intervalBuckets = longs(j.optJSONObject("intervalBuckets")),
                captureCounters = longs(j.optJSONObject("captureCounters"))), j.getString("reason"),
            if (j.isNull("discomfort")) null else j.getInt("discomfort"),
            if (j.isNull("before")) null else j.getInt("before"), j.optString("content", "unspecified"),
            j.optString("device", "unknown"), j.optInt("sdk"), j.optString("appVersion", "0.1.0-alpha"),
            j.optString("engine", "exposure-0.1"), j.optInt("revision"),
            feedback?.keys()?.asSequence()?.associateWith { stage -> feedback.getJSONObject(stage).let { f -> Feedback(if (f.isNull("score")) null else f.getInt("score"), f.getLong("at"), f.getBoolean("skipped")) } } ?: emptyMap(),
            if (j.isNull("restStartedAt")) null else j.getLong("restStartedAt"),
            j.optJSONObject("diagnostics")?.let(SessionDiagnostics::decode))
    }
}

/** One process; capture is local. Only whitelisted report aggregates enter the encrypted sync queue. */
object EyeCareRepository {
    lateinit var store: SessionStore
        private set
    private lateinit var baselineStore: PersonalBaselineRepository
    private lateinit var appContext: Context
    val live = MutableStateFlow<LiveSession?>(null)
    val history = MutableStateFlow<List<SessionRecord>>(emptyList())
    val reports = MutableStateFlow<List<CareReport>>(emptyList())
    val subjectiveResponses = MutableStateFlow<List<SubjectiveResponse>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    @Synchronized fun init(context: Context) {
        if (::store.isInitialized) return
        appContext = context.applicationContext
        store = SessionStore(context.applicationContext)
        baselineStore = PersonalBaselineRepository(context.applicationContext)
        store.prune()
        store.all(10000).filter { it.reason == "active" }.forEach { store.save(it.copy(reason = "interrupted", revision = it.revision + 1)) }
        refresh()
    }
    fun refresh() { history.value = store.all().filter { it.reason != "active" }; reports.value = store.reports(); refreshSubjective() }
    fun refreshSubjective() { subjectiveResponses.value = store.subjectiveResponses() }
    fun addReport(report: CareReport) {
        store.saveReport(report)
        reports.value = store.reports()
        // This local-only write runs before returning from report creation, even when offline.
        runCatching {
            runBlocking { ResearchUploadManager.get(appContext).enqueueReports(listOf(report)) }
            ResearchSyncScheduler.schedule(appContext)
        }.onFailure { error.value = "보고서는 저장됐지만 웹 동기화 대기열을 만들지 못했습니다. 앱을 다시 열어 재시도해 주세요." }
    }
    @Synchronized fun answerReport(id: String, nrs: Int) {
        if (nrs !in 0..10) return
        store.reports().find { it.id == id }?.let {
            val previous = store.latestSubjectiveResponse(id)
            if (previous?.nrs == nrs) return
            val answeredAt = maxOf(System.currentTimeMillis(), (previous?.answeredAt ?: 0L) + 1)
            store.addSubjectiveResponse(SubjectiveResponse(reportId = id, nrs = nrs,
                answeredAt = answeredAt, previousResponseId = previous?.id))
            val updated = it.copy(nrs = nrs, nrsAt = answeredAt, subjectiveEventV1 = true)
            store.saveReport(updated)
            store.saveEvent(CareEvent(sessionId = it.sessionId, at = answeredAt,
                type = "report_answered", detail = "delay_ms=${(answeredAt - it.createdAt).coerceAtLeast(0)}"))
            reports.value = store.reports()
            refreshSubjective()
            runCatching { runBlocking { ResearchUploadManager.get(appContext).enqueueReports(listOf(updated)) } }
            ResearchSyncScheduler.schedule(appContext)
        }
    }
    fun report(id: String, score: Int) {
        if (score !in 0..4) return
        store.all().find { it.id == id && it.reason != "active" }?.let { store.save(it.copy(discomfort = score, revision = it.revision + 1)) }
        refresh()
    }
    @Synchronized fun feedback(id: String, stage: String, score: Int?) {
        if (stage !in listOf("afterUse", "afterRest") || (score != null && score !in 0..4)) return
        store.all().find { it.id == id && it.reason != "active" }?.let { r ->
            if (stage == "afterRest" && r.restStartedAt == null) return
            store.save(r.copy(feedback = r.feedback + (stage to Feedback(score, System.currentTimeMillis(), score == null)), revision = r.revision + 1))
        }
        refresh()
    }
    @Synchronized fun beginRest(id: String) {
        store.all().find { it.id == id && it.reason != "active" }?.let {
            val at = System.currentTimeMillis()
            store.save(it.copy(restStartedAt = at, feedback = it.feedback - "afterRest", revision = it.revision + 1))
            store.saveEvent(CareEvent(sessionId = it.id, at = at, type = "rest_timer_opened"))
        }
        refresh()
    }
    @Synchronized fun confirmRest(id: String, didRest: Boolean) {
        val session = store.all(10000).firstOrNull { it.id == id } ?: return
        if (session.restStartedAt == null) return
        if (store.events(setOf(id)).any { it.at >= session.restStartedAt &&
                it.type in setOf("rest_confirmed", "rest_declined") }) return
        store.saveEvent(CareEvent(sessionId = id, at = System.currentTimeMillis(),
            type = if (didRest) "rest_confirmed" else "rest_declined"))
    }
    fun clear() { if (live.value?.running != true) { store.clear(); baselineStore.clear(); refresh() } }
}
