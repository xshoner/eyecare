package com.neurolens.eyecare.data

data class RestEffectSummary(val shown: Int, val dismissed: Int, val snoozed: Int,
    val selected: Int, val confirmed: Int, val declined: Int,
    val pairedNrs: Int, val missingNrs: Int, val meanFollowupNrs: Double?,
    val suggestedCooldownMinutes: Int?)

/** Descriptive local counts only. A selected button or elapsed timer is never proof of rest. */
object RestEffectAnalyzer {
    private const val MAX_FOLLOWUP_MS = 24 * 60 * 60 * 1000L
    fun summarize(events: List<CareEvent>, reports: List<CareReport>,
        responses: List<SubjectiveResponse>, cooldownMinutes: Int): RestEffectSummary {
        val relevant = events.filter { it.type.startsWith("rest_alert_") ||
            it.type in setOf("rest_confirmed", "rest_declined") }
        fun count(type: String) = relevant.count { it.type == type }
        val shown = relevant.filter { it.type == "rest_alert_shown" }.sortedBy { it.at }
        val reportById = reports.associateBy { it.id }
        val firstAnswers = responses.groupBy { it.reportId }.values.mapNotNull { group ->
            group.minWithOrNull(compareBy<SubjectiveResponse> { it.answeredAt }.thenBy { it.id })
        }.mapNotNull { answer -> reportById[answer.reportId]?.let { report -> report to answer } }
            .sortedWith(compareBy<Pair<CareReport, SubjectiveResponse>> { it.second.answeredAt }
                .thenBy { it.second.id })
        val used = hashSetOf<String>()
        var nrsTotal = 0
        var paired = 0
        for (alert in shown) {
            val pair = firstAnswers.firstOrNull { (report, answer) ->
                answer.id !in used && report.sessionId == alert.sessionId &&
                    report.createdAt >= alert.at && answer.answeredAt >= report.createdAt &&
                    answer.answeredAt - alert.at <= MAX_FOLLOWUP_MS
            } ?: continue
            used += pair.second.id; paired++; nrsTotal += pair.second.nrs
        }
        val delayed = count("rest_alert_dismissed") + count("rest_alert_snoozed")
        val suggested = listOf(10, 20, 30, 60).firstOrNull { it > cooldownMinutes }
            .takeIf { shown.size >= 5 && delayed * 2 >= shown.size }
        return RestEffectSummary(shown.size, count("rest_alert_dismissed"),
            count("rest_alert_snoozed"), count("rest_alert_selected"),
            count("rest_confirmed"), count("rest_declined"), paired,
            shown.size - paired, nrsTotal.takeIf { paired > 0 }?.let { it.toDouble() / paired }, suggested)
    }
}
