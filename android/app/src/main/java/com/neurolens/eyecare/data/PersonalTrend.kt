package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.StrainModel
import kotlin.math.roundToInt

data class PersonalTrendSummary(
    val latest: CareReport?, val previousComparable: CareReport?,
    val previousDelta: Int?, val priorMedian: Int?, val priorSamples: Int
)

/** Prior-only comparison. This does not train on the current score or NRS label. */
object PersonalTrend {
    const val VERSION = "personal-trend-v3"
    fun comparable(report: CareReport): Boolean = report.scoreAvailable && report.score in 0..100 &&
        report.version in setOf(StrainModel.VERSION, StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION,
            MvpScorer.PREVIOUS_VERSION, MvpScorer.LEGACY_VERSION) &&
        (if (StrainModel.isDeduction(report.version) || report.version == MvpScorer.VERSION)
            report.availableWeight >= (if (StrainModel.isDeduction(report.version)) 50 else 90) && report.confidence.startsWith("보통")
        else report.availableWeight >= 30)
    fun previous(report: CareReport, reports: List<CareReport>): CareReport? = reports
        .asSequence().filter { comparable(report) && it.createdAt < report.createdAt &&
            it.version == report.version && comparable(it) }
        .maxWithOrNull(compareBy<CareReport> { it.createdAt }.thenBy { it.id })
    fun summarize(reports: List<CareReport>): PersonalTrendSummary {
        val ordered = reports.sortedWith(compareByDescending<CareReport> { it.createdAt }.thenByDescending { it.id })
        val latest = ordered.firstOrNull() ?: return PersonalTrendSummary(null, null, null, null, 0)
        val earlier = ordered.drop(1)
        val prior = previous(latest, earlier)
        val trustedPrior = earlier.filter { it.version == latest.version && comparable(it) }.take(7)
        val reference = if (latest.version in StrainModel.VERSIONS + MvpScorer.VERSION && comparable(latest) && trustedPrior.size >= 3) {
            val scores = trustedPrior.map { it.score }.sorted()
            if (scores.size % 2 == 1) scores[scores.size / 2]
            else ((scores[scores.size / 2 - 1] + scores[scores.size / 2]) / 2.0).roundToInt()
        } else null
        return PersonalTrendSummary(latest, prior,
            if (comparable(latest) && prior != null) latest.score - prior.score else null,
            reference, trustedPrior.size)
    }
}
