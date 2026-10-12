package com.neurolens.eyecare.ui.screens

import com.neurolens.eyecare.data.CareReport
import com.neurolens.eyecare.data.PersonalTrend
import com.neurolens.eyecare.engine.EyeCondition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object Fmt {
    private val zone get() = ZoneId.systemDefault()
    private val dateTime = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
    private val day = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateTime)
    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(time)
    fun today(): String = LocalDate.now(zone).format(day)
    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun dayHeader(date: LocalDate): String = when (date) {
        LocalDate.now(zone) -> "오늘"
        LocalDate.now(zone).minusDays(1) -> "어제"
        else -> date.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN))
    }
    fun weekday(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("E", Locale.KOREAN))

    fun duration(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) / 60_000)
        return when {
            ms in 1..59_999 -> "1분 미만"
            minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
            else -> "${minutes}분"
        }
    }
    fun clock(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)

    fun light(lux: Double?): Pair<String, String>? = when {
        lux == null || !lux.isFinite() || lux < 0 -> null
        lux < 10 -> "매우 어두움" to "어두운 곳에서는 화면 밝기를 낮추고 조명을 켜 주세요."
        lux < 50 -> "어두움" to "주변이 어두워요. 조명을 켜면 눈이 덜 피로해요."
        lux < 300 -> "적당함" to "보기 좋은 밝기예요."
        else -> "밝음" to "밝은 곳이에요. 화면 반사를 피해 주세요."
    }
}

/** What the UI may say about a report's score. Partial evidence is never shown as a reassuring level. */
internal data class ConditionView(val value: Int?, val band: EyeCondition.Band, val partial: Boolean)

internal fun CareReport.conditionView(): ConditionView {
    val value = if (scoreAvailable) EyeCondition.fromBurden(score) else null
    val partial = scoreAvailable && !PersonalTrend.comparable(this)
    return ConditionView(value, if (value == null || partial) EyeCondition.Band.UNKNOWN else EyeCondition.band(value), partial)
}

internal fun CareReport.signal(id: String, minQuality: Double = .3): Double? =
    mvpSignals[id]?.takeIf { it.quality >= minQuality }?.value?.takeIf { it.isFinite() }

internal val DOMAIN_WORDS = linkedMapOf("screen" to "화면 자극", "environment" to "주변 밝기",
    "posture" to "자세·흔들림", "usage" to "사용 시간·거리", "eyes" to "눈 반응")
