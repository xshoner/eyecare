package com.neurolens.eyecare.capture

import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.engine.StrainModel
import kotlin.math.ceil

/**
 * When to interrupt the user. Inputs come from [StrainModel] / [com.neurolens.eyecare.engine.StrainLedger]
 * once per tick while viewing is observed. All times are monotonic milliseconds.
 *
 * Kinds, highest priority first:
 *  RISK     condition < 20 for [sustainMs]; repeats every cooldown/2
 *  CAUTION  condition < 40 for [sustainMs]; repeats every cooldown
 *  FORECAST deduction ≥ 0.8 pt/min and the next lower band is ≤ 10 minutes away; once per band per day
 *  BREAK    continuous viewing reaches the break interval: 20 min normally, 15 dark or near,
 *           10 dark+near, 7 dark+near with intense content; repeats after another interval
 *  DARK_FLASH intense content in the dark for 2 minutes; every 15 minutes
 *  TOO_CLOSE nearer than 25 cm for 1 minute; every 10 minutes
 *  FAIR     condition first drops below 60 (보통) today; once
 * Any two alerts are at least 3 minutes apart (RISK excepted); a snooze silences all for 10 minutes.
 */
class StrainAlertPolicy {
    enum class Kind { RISK, CAUTION, FORECAST, BREAK, DARK_FLASH, TOO_CLOSE, FAIR }
    data class Alert(val kind: Kind, val title: String, val body: String)

    data class Tick(val nowMs: Long, val condition: Int, val assessment: StrainModel.Assessment,
        val continuousMin: Double, val distanceCm: Double?, val darkMinutes: Double,
        val cooldownMinutes: Int, val sustainMinutes: Int)

    private val lastShown = mutableMapOf<Kind, Long>()
    private var lastAny = Long.MIN_VALUE / 2
    private var snoozedUntil = Long.MIN_VALUE / 2
    private var belowSince = mutableMapOf<Kind, Long>()
    private var intenseDarkSince: Long? = null
    private var tooCloseSince: Long? = null
    private val forecastBands = mutableSetOf<EyeCondition.Band>()
    private var fairShown = false
    private var lastBreakAtContinuous = 0.0

    fun snooze(nowMs: Long) { snoozedUntil = nowMs + 10 * 60_000L }
    fun clearDaily() { forecastBands.clear(); fairShown = false }

    /** Break interval in minutes for the current conditions. */
    fun breakInterval(a: StrainModel.Assessment): Int = when {
        a.dark && a.near && a.stimulus == StrainModel.Stimulus.INTENSE -> 7
        a.dark && a.near -> 10
        a.dark || a.near -> 15
        else -> 20
    }

    /** Minutes until the condition crosses into the next lower band at the current rate, or null. */
    fun minutesToNextBand(condition: Int, rate: Double): Int? {
        if (rate <= 0) return null
        val floor = when { condition >= 80 -> 79; condition >= 60 -> 59; condition >= 40 -> 39; condition >= 20 -> 19; else -> return null }
        return ceil((condition - floor) / rate).toInt().coerceAtLeast(1)
    }

    fun evaluate(t: Tick): Alert? {
        val a = t.assessment
        val sustainMs = t.sustainMinutes.coerceIn(1, 5) * 60_000L
        val cooldownMs = t.cooldownMinutes.coerceIn(10, 60) * 60_000L
        fun held(kind: Kind, condition: Boolean): Boolean {
            if (!condition) { belowSince.remove(kind); return false }
            val since = belowSince.getOrPut(kind) { t.nowMs }
            return t.nowMs - since >= sustainMs
        }
        fun due(kind: Kind, every: Long) = t.nowMs - (lastShown[kind] ?: Long.MIN_VALUE / 2) >= every
        intenseDarkSince = if (a.dark && a.stimulus == StrainModel.Stimulus.INTENSE) intenseDarkSince ?: t.nowMs else null
        tooCloseSince = if ((t.distanceCm ?: 99.0) < 25.0) tooCloseSince ?: t.nowMs else null
        if (t.continuousMin < lastBreakAtContinuous) lastBreakAtContinuous = 0.0   // a rest reset the streak

        val next = minutesToNextBand(t.condition, a.totalRate)
        val nextBand = EyeCondition.band(when { t.condition >= 80 -> 79; t.condition >= 60 -> 59; t.condition >= 40 -> 39; else -> 19 })
        val candidates = buildList {
            if (held(Kind.RISK, t.condition < 20) && due(Kind.RISK, cooldownMs / 2))
                add(Alert(Kind.RISK, "눈 컨디션 ${t.condition}점 · 위험", "지금은 화면을 내려놓고 5분 이상 쉬어 주세요."))
            if (held(Kind.CAUTION, t.condition in 20..39) && due(Kind.CAUTION, cooldownMs))
                add(Alert(Kind.CAUTION, "눈 컨디션 ${t.condition}점 · 주의", "눈이 피곤해지고 있어요. 화면을 끄고 먼 곳을 바라봐 주세요."))
            if (a.totalRate >= .8 && next != null && next <= 10 && nextBand !in forecastBands &&
                nextBand in setOf(EyeCondition.Band.CAUTION, EyeCondition.Band.RISK, EyeCondition.Band.FAIR))
                add(Alert(Kind.FORECAST, "이대로면 약 ${next}분 뒤 '${nextBand.label}'",
                    (a.active.firstOrNull()?.let { "${it.label} 때문에 컨디션이 빠르게 내려가고 있어요. " } ?: "") +
                        (a.active.firstOrNull()?.tip ?: "잠깐 쉬어 주세요.")))
            val interval = breakInterval(a)
            if (t.continuousMin - lastBreakAtContinuous >= interval)
                add(Alert(Kind.BREAK, "${t.continuousMin.toInt()}분째 보고 있어요",
                    if (a.combo != StrainModel.Combo.NONE) "${a.combo.label} 보는 중이라 평소보다 빨리 알려 드려요. 20초만 먼 곳을 바라봐 주세요."
                    else "20초만 먼 곳(6m 이상)을 바라봐 주세요."))
            if (intenseDarkSince?.let { t.nowMs - it >= 120_000 } == true && due(Kind.DARK_FLASH, 15 * 60_000L))
                add(Alert(Kind.DARK_FLASH, "어두운 곳에서 자극적인 화면을 보고 있어요", "조명을 켜거나 화면 밝기를 낮춰 주세요."))
            if (tooCloseSince?.let { t.nowMs - it >= 60_000 } == true && due(Kind.TOO_CLOSE, 10 * 60_000L))
                add(Alert(Kind.TOO_CLOSE, "화면이 너무 가까워요", "지금 약 ${t.distanceCm?.toInt()}cm예요. ${(35 - (t.distanceCm ?: 35.0)).toInt().coerceAtLeast(1)}cm 더 멀리 두세요."))
            if (!fairShown && held(Kind.FAIR, t.condition in 40..59))
                add(Alert(Kind.FAIR, "눈 컨디션 ${t.condition}점 · 보통", "부담이 쌓이기 시작했어요. 잠깐 쉬어 가면 좋아요."))
        }
        val pick = candidates.firstOrNull() ?: return null
        if (t.nowMs < snoozedUntil && pick.kind != Kind.RISK) return null
        if (pick.kind != Kind.RISK && t.nowMs - lastAny < 3 * 60_000L) return null
        lastShown[pick.kind] = t.nowMs; lastAny = t.nowMs
        when (pick.kind) {
            Kind.FORECAST -> forecastBands += nextBand
            Kind.BREAK -> lastBreakAtContinuous = t.continuousMin
            Kind.FAIR -> fairShown = true
            else -> Unit
        }
        return pick
    }
}
