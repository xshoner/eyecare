package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.EfsResult
import com.neurolens.eyecare.engine.EfsScorer
import com.neurolens.eyecare.engine.MvpResult
import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.Signal
import com.neurolens.eyecare.engine.ScreenSignalEngine
import com.neurolens.eyecare.engine.EyeSignalEngine
import com.neurolens.eyecare.engine.ScenarioAdjustment
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CareReport(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val createdAt: Long,
    val windowStartAt: Long,
    val intervalMin: Int,
    val score: Int,
    val level: String,
    val confidence: String,
    val inputs: List<String>,
    val usageScore: Int,
    val screenScore: Int?,
    val screenCoverage: Double,
    val eyeDetectionRatio: Double? = null,
    val cameraProcessedFps: Double = 0.0,
    val eyeOpenMean: Double? = null,
    val gazeState: String = "unavailable",
    val nrs: Int? = null,
    val nrsAt: Long? = null,
    val version: String = EfsScorer.VERSION,
    val mvpDomains: Map<String, Int> = emptyMap(),
    val mvpSignals: Map<String, Signal> = emptyMap(),
    val baselineSamples: Int = 0,
    val mvpNote: String? = null,
    val scoreAvailable: Boolean = true,
    val baseScore: Int = 0,
    val nominalWeights: Map<String, Int> = emptyMap(),
    val contributions: Map<String, Double> = emptyMap(),
    val availableWeight: Int = 0,
    val adjustments: List<ScenarioAdjustment> = emptyList(),
    val domainQualities: Map<String, Double> = emptyMap(),
    val subjectiveEventV1: Boolean = false,
    val observedBurden: Int? = null,
    val observedMinutes: Double = 0.0,
    val interactionRate: Double = 0.0
) {
    companion object {
        fun unavailable(sessionId: String, now: Long, reason: String): CareReport {
            val ids = ScreenSignalEngine.METRICS + ScreenSignalEngine.CONTEXT_METRICS + EyeSignalEngine.METRICS
            return CareReport(sessionId = sessionId, createdAt = now, windowStartAt = now,
                intervalMin = 0, score = 0, level = "측정 불가", confidence = "증거 없음",
                inputs = emptyList(), usageScore = 0, screenScore = null, screenCoverage = 0.0,
                version = MvpScorer.VERSION, mvpSignals = ids.associateWith { Signal.missing(reason) },
                mvpNote = "화면 캡처가 승인되지 않아 피로 점수를 계산하지 않았습니다.", scoreAvailable = false)
        }

        fun from(sessionId: String, createdAt: Long, windowStartAt: Long, intervalMin: Int, result: EfsResult,
            eyeDetectionRatio: Double? = null, cameraProcessedFps: Double = 0.0,
            eyeOpenMean: Double? = null, gazeState: String = "unavailable", mvp: MvpResult? = null) =
            CareReport(sessionId = sessionId, createdAt = createdAt, windowStartAt = windowStartAt,
                intervalMin = intervalMin, score = result.score, level = result.level,
                confidence = result.confidence, inputs = result.inputs, usageScore = result.usageScore,
                screenScore = result.screenScore, screenCoverage = result.coverage,
                eyeDetectionRatio = eyeDetectionRatio, cameraProcessedFps = cameraProcessedFps,
                eyeOpenMean = eyeOpenMean, gazeState = gazeState,
                version = if (mvp == null) EfsScorer.VERSION else MvpScorer.VERSION,
                mvpDomains = mvp?.domains.orEmpty(), mvpSignals = mvp?.signals.orEmpty(),
                baselineSamples = mvp?.baselineSamples ?: 0, mvpNote = mvp?.note,
                scoreAvailable = mvp?.scoreAvailable ?: true,
                baseScore = mvp?.baseScore ?: 0, nominalWeights = mvp?.nominalWeights.orEmpty(),
                contributions = mvp?.contributions.orEmpty(), availableWeight = mvp?.availableWeight ?: 0,
                adjustments = mvp?.adjustments.orEmpty(), domainQualities = mvp?.domainQualities.orEmpty(),
                subjectiveEventV1 = true,
                observedBurden = mvp?.observedBurden,
                observedMinutes = mvp?.observedMinutes ?: 0.0,
                interactionRate = mvp?.interactionRate ?: 0.0)
    }
}

object CareReportCodec {
    fun encode(r: CareReport) = JSONObject().apply {
        put("id", r.id); put("sessionId", r.sessionId); put("createdAt", r.createdAt)
        put("windowStartAt", r.windowStartAt); put("intervalMin", r.intervalMin)
        put("score", if (r.scoreAvailable) r.score else JSONObject.NULL)
        put("scoreAvailable", r.scoreAvailable)
        put("level", r.level); put("confidence", r.confidence)
        put("inputs", JSONArray(r.inputs)); put("usageScore", r.usageScore)
        put("screenScore", r.screenScore ?: JSONObject.NULL); put("screenCoverage", r.screenCoverage)
        put("eyeDetectionRatio", r.eyeDetectionRatio ?: JSONObject.NULL)
        put("cameraProcessedFps", r.cameraProcessedFps)
        put("eyeOpenMean", r.eyeOpenMean ?: JSONObject.NULL); put("gazeState", r.gazeState)
        put("nrs", r.nrs ?: JSONObject.NULL); put("nrsAt", r.nrsAt ?: JSONObject.NULL)
        put("subjectiveEventV1", r.subjectiveEventV1)
        put("version", r.version)
        put("mvpDomains", JSONObject(r.mvpDomains))
        put("mvpSignals", JSONObject().apply { r.mvpSignals.forEach { (id, signal) ->
            put(id, JSONObject().put("value", signal.value ?: JSONObject.NULL)
                .put("quality", signal.quality).put("reason", signal.reason ?: JSONObject.NULL)
                .put("estimated", signal.estimated))
        } })
        put("baselineSamples", r.baselineSamples)
        put("mvpNote", r.mvpNote ?: JSONObject.NULL)
        put("baseScore", r.baseScore); put("nominalWeights", JSONObject(r.nominalWeights))
        put("contributions", JSONObject(r.contributions)); put("availableWeight", r.availableWeight)
        put("domainQualities", JSONObject(r.domainQualities))
        put("observedBurden", r.observedBurden ?: JSONObject.NULL)
        put("observedMinutes", r.observedMinutes)
        put("interactionRate", r.interactionRate)
        put("adjustments", JSONArray().apply { r.adjustments.forEach { adjustment ->
            put(JSONObject().put("id", adjustment.id).put("points", adjustment.points)
                .put("evidence", JSONArray(adjustment.evidence)).put("rationale", adjustment.rationale)
                .put("estimated", adjustment.estimated))
        } })
    }

    fun decode(j: JSONObject) = CareReport(j.getString("id"), j.getString("sessionId"),
        j.getLong("createdAt"), j.getLong("windowStartAt"), j.getInt("intervalMin"),
        if (j.isNull("score")) 0 else j.getInt("score"), j.getString("level"), j.getString("confidence"),
        (j.optJSONArray("inputs") ?: JSONArray()).let { a -> (0 until a.length()).map(a::getString) },
        j.getInt("usageScore"), if (j.isNull("screenScore")) null else j.getInt("screenScore"),
        j.getDouble("screenCoverage"),
        if (j.isNull("eyeDetectionRatio")) null else j.getDouble("eyeDetectionRatio"),
        j.optDouble("cameraProcessedFps", 0.0),
        if (j.isNull("eyeOpenMean")) null else j.getDouble("eyeOpenMean"),
        j.optString("gazeState", "unavailable"),
        if (j.isNull("nrs")) null else j.getInt("nrs"),
        if (j.isNull("nrsAt")) null else j.getLong("nrsAt"), j.getString("version"),
        j.optJSONObject("mvpDomains")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } ?: emptyMap(),
        j.optJSONObject("mvpSignals")?.let { o -> o.keys().asSequence().associateWith { id ->
            val s = o.getJSONObject(id)
            Signal(if (s.isNull("value")) null else s.getDouble("value"), s.optDouble("quality", 0.0),
                if (s.isNull("reason")) null else s.getString("reason"), s.optBoolean("estimated", false))
        } } ?: emptyMap(),
        j.optInt("baselineSamples", 0), if (j.isNull("mvpNote")) null else j.optString("mvpNote"),
        j.optBoolean("scoreAvailable", !j.isNull("score")),
        j.optInt("baseScore", 0),
        j.optJSONObject("nominalWeights")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } ?: emptyMap(),
        j.optJSONObject("contributions")?.let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } } ?: emptyMap(),
        j.optInt("availableWeight", 0),
        j.optJSONArray("adjustments")?.let { a -> (0 until a.length()).map { index ->
            val item = a.getJSONObject(index)
            val evidence = item.optJSONArray("evidence") ?: JSONArray()
            ScenarioAdjustment(item.getString("id"), item.getInt("points"),
                (0 until evidence.length()).map(evidence::getString), item.optString("rationale"),
                item.optBoolean("estimated", true))
        } } ?: emptyList(),
        j.optJSONObject("domainQualities")?.let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } } ?: emptyMap(),
        j.optBoolean("subjectiveEventV1", false),
        if (j.isNull("observedBurden")) null else j.optInt("observedBurden"),
        j.optDouble("observedMinutes", 0.0), j.optDouble("interactionRate", 0.0))
}
