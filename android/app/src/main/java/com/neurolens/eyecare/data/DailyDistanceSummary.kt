package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/**
 * [meanCm] is the time-weighted average of each report's typical (median) eye–camera distance,
 * weighted by that report's valid distance minutes. [typicalCm] keeps the weighted median.
 */
data class DailyDistanceSummary(val typicalCm: Double? = null, val nearRatio: Double? = null,
    val observedMinutes: Double = 0.0, val coverage: Double = 0.0,
    val calibrated: Boolean = false, val reports: Int = 0, val meanCm: Double? = null,
    val days: Int = 1)

object DailyDistance {
    private val VERSIONS = setOf(com.neurolens.eyecare.engine.StrainModel.VERSION, com.neurolens.eyecare.engine.StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION, MvpScorer.PREVIOUS_VERSION)

    fun summarize(reports: List<CareReport>, dayAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): DailyDistanceSummary {
        val day = Instant.ofEpochMilli(dayAtMs).atZone(zone).toLocalDate()
        return over(reports.filter { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == day }, 1)
    }

    /** The last [days] local days ending on the day of [dayAtMs]. */
    fun recent(reports: List<CareReport>, dayAtMs: Long, days: Int = 7, zone: ZoneId = ZoneId.systemDefault()): DailyDistanceSummary {
        val last = Instant.ofEpochMilli(dayAtMs).atZone(zone).toLocalDate()
        val first = last.minusDays((days - 1).toLong())
        return over(reports.filter {
            val d = Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate()
            !d.isBefore(first) && !d.isAfter(last)
        }, days)
    }

    private fun over(selected: List<CareReport>, days: Int): DailyDistanceSummary {
        val window = selected.filter { it.version in VERSIONS }
        val measured = window.mapNotNull { report ->
            val cm = report.mvpSignals["D01.distanceEstimateCm"]?.value
            val minutes = report.mvpSignals["D05.validMinutes"]?.value
            if (cm == null || minutes == null || cm !in 15.0..80.0 || minutes <= 0 ||
                !cm.isFinite() || !minutes.isFinite()) null else Triple(report, cm, minutes)
        }
        val valid = measured.sumOf { it.third }
        val span = window.sumOf { report ->
            report.mvpSignals["D08.windowMinutes"]?.value?.takeIf { it.isFinite() && it > 0 }
                ?: ((report.createdAt - report.windowStartAt).coerceAtLeast(0L)) / 60_000.0
        }
        val coverage = if (span > 0) (valid / span).coerceIn(0.0, 1.0) else 0.0
        if (valid < .25) return DailyDistanceSummary(observedMinutes = valid, coverage = coverage,
            reports = window.size, days = days)
        val ordered = measured.sortedBy { it.second }
        var cumulative = 0.0
        val median = ordered.first { cumulative += it.third; cumulative * 2 >= valid }.second
        val mean = measured.sumOf { it.second * it.third } / valid
        val near = measured.sumOf { (r, _, minutes) ->
            (r.mvpSignals["D03.nearRatio"]?.value ?: 0.0).coerceIn(0.0, 1.0) * minutes
        } / valid
        return DailyDistanceSummary(median, near, valid, coverage,
            measured.all { it.first.mvpSignals["D06.calibrated"]?.value == 1.0 }, window.size, mean, days)
    }
}

/** Plain-language viewing-distance coaching. The 35 cm target is the app's guideline, not a medical limit. */
object DistanceGuide {
    const val TARGET_CM = 35
    const val TOO_CLOSE_CM = 25

    enum class Level { GOOD, CLOSE, TOO_CLOSE, UNKNOWN }
    data class Advice(val level: Level, val moveCm: Int?, val headline: String, val detail: String)

    fun advise(cm: Double?): Advice {
        if (cm == null || !cm.isFinite() || cm !in 10.0..100.0)
            return Advice(Level.UNKNOWN, null, "거리 측정 전", "측정 중 얼굴이 카메라에 보이면 평소 보는 거리를 알려 드려요.")
        val shown = cm.toInt()
        if (cm >= TARGET_CM) return Advice(Level.GOOD, null, "좋은 거리예요",
            "평균 ${shown}cm에서 보고 있어요. 지금 거리를 유지해 주세요.")
        val move = ceil(TARGET_CM - cm).toInt().coerceAtLeast(1)
        return if (cm < TOO_CLOSE_CM) Advice(Level.TOO_CLOSE, move, "너무 가까워요",
            "평균 ${shown}cm예요. ${move}cm 더 멀리, ${TARGET_CM}cm 이상 떨어뜨려 보세요.")
        else Advice(Level.CLOSE, move, "조금 가까워요",
            "평균 ${shown}cm예요. ${move}cm만 더 멀리 두면 눈이 편해요.")
    }
}
