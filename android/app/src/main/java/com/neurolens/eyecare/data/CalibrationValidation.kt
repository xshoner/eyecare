package com.neurolens.eyecare.data

import kotlin.math.abs

data class CalibrationValidation(val evaluated: Int, val rawMae: Double?,
    val personalMae: Double?, val eligibleLabels: Int, val algorithmVersion: String?,
    val sameVersionReports: Int = 0, val firstEvaluatedAt: Long? = null,
    val lastEvaluatedAt: Long? = null) {
    val sufficientEvidence get() = evaluated >= 5
}

/** Prequential evaluation: each held-out report is predicted using receipts strictly before it. */
object CalibrationValidator {
    fun evaluate(reports: List<CareReport>, answers: List<SubjectiveResponse>): CalibrationValidation {
        val newestVersion = reports.maxByOrNull { it.createdAt }?.version
        val candidates = reports.filter { it.version == newestVersion && PersonalTrend.comparable(it) }
            .sortedWith(compareBy<CareReport> { it.createdAt }.thenBy { it.id })
        // The first recorded label is the outcome. Later revisions never rewrite earlier validation.
        val first = answers.filter { it.receivedAt != null }.groupBy { it.reportId }
            .mapValues { (_, values) -> values.minWithOrNull(
                compareBy<SubjectiveResponse> { it.receivedAt }.thenBy { it.id })!! }
        var raw = 0.0; var personal = 0.0; var evaluated = 0; var eligible = 0
        var firstEvaluatedAt: Long? = null; var lastEvaluatedAt: Long? = null
        for (report in candidates) {
            val answer = first[report.id] ?: continue
            if (answer.answeredAt < report.createdAt || answer.answeredAt - report.createdAt > 86_400_000L) continue
            eligible++
            val calibrated = PersonalFatigueCalibration.forReport(report, candidates, answers)
            val prediction = calibrated.personalizedScore ?: continue
            raw += abs(report.score - answer.nrs * 10)
            personal += abs(prediction - answer.nrs * 10)
            evaluated++
            if (firstEvaluatedAt == null) firstEvaluatedAt = report.createdAt
            lastEvaluatedAt = report.createdAt
        }
        return CalibrationValidation(evaluated, if (evaluated > 0) raw / evaluated else null,
            if (evaluated > 0) personal / evaluated else null, eligible, newestVersion,
            candidates.size, firstEvaluatedAt, lastEvaluatedAt)
    }
}
