package com.neurolens.eyecare.data

import java.util.UUID
import kotlin.math.roundToInt

/** One immutable local answer. Server receipt is an acknowledgement, not a changed answer. */
data class SubjectiveResponse(
    val id: String = UUID.randomUUID().toString(),
    val reportId: String,
    val nrs: Int,
    val answeredAt: Long,
    val previousResponseId: String? = null,
    val receivedAt: Long? = null
) {
    init { require(nrs in 0..10 && answeredAt > 0) }
}

data class PersonalCalibration(val rawScore: Int, val personalizedScore: Int?,
    val offset: Int?, val samples: Int, val version: String = "personal-offset-v1")

/** Prior-only, bounded personal alignment. NRS is a subjective label, not clinical truth. */
object PersonalFatigueCalibration {
    private const val MIN_SAMPLES = 5
    private const val MAX_DELAY_MS = 24 * 60 * 60 * 1000L
    fun forReport(target: CareReport, reports: List<CareReport>, responses: List<SubjectiveResponse>): PersonalCalibration {
        if (!PersonalTrend.comparable(target)) return PersonalCalibration(target.score, null, null, 0)
        val reportById = reports.associateBy { it.id }
        val latest = responses.filter { it.receivedAt != null && it.receivedAt < target.createdAt }
            .groupBy { it.reportId }
            .mapValues { (_, entries) -> entries.maxWithOrNull(
                compareBy<SubjectiveResponse> { it.receivedAt }.thenBy { it.id })!! }
        val offsets = latest.values.mapNotNull { answer ->
            val prior = reportById[answer.reportId] ?: return@mapNotNull null
            if (prior.createdAt >= target.createdAt || prior.version != target.version ||
                !PersonalTrend.comparable(prior) || answer.nrs !in 0..10 ||
                answer.answeredAt < prior.createdAt ||
                answer.answeredAt - prior.createdAt > MAX_DELAY_MS ||
                answer.receivedAt!! >= target.createdAt) null
            else answer.nrs * 10 - prior.score
        }.sorted()
        if (offsets.size < MIN_SAMPLES) return PersonalCalibration(target.score, null, null, offsets.size)
        val middle = offsets.size / 2
        val median = if (offsets.size % 2 == 1) offsets[middle].toDouble()
            else (offsets[middle - 1] + offsets[middle]) / 2.0
        val shrink = offsets.size.toDouble() / (offsets.size + 10.0)
        val offset = (median * shrink).roundToInt().coerceIn(-12, 12)
        return PersonalCalibration(target.score, (target.score + offset).coerceIn(0, 100), offset, offsets.size)
    }
}
