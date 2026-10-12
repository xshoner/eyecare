package com.neurolens.eyecare.engine

/**
 * User-facing "eye condition" display: 100 is 매우 양호 and lower values mean 보통/주의/위험.
 * It is a pure display transform of the stored burden `score` (0 = low burden), which
 * stays unchanged in local records, exports and uploads for version continuity.
 * The five 20-point bands are display bands chosen for readability, not clinical cutoffs.
 */
object EyeCondition {
    enum class Band(val label: String) {
        EXCELLENT("매우 양호"), GOOD("양호"), FAIR("보통"), CAUTION("주의"), RISK("위험"), UNKNOWN("판정 보류")
    }
    /** Bands in drawing order on the five-step scale (left = 위험, right = 매우 양호). */
    val SCALE = listOf(Band.RISK, Band.CAUTION, Band.FAIR, Band.GOOD, Band.EXCELLENT)

    fun fromBurden(burden: Int): Int = 100 - burden.coerceIn(0, 100)
    fun fromBurden(burden: Int?): Int? = burden?.let { fromBurden(it) }

    fun band(condition: Int?): Band = when {
        condition == null -> Band.UNKNOWN
        condition >= 80 -> Band.EXCELLENT
        condition >= 60 -> Band.GOOD
        condition >= 40 -> Band.FAIR
        condition >= 20 -> Band.CAUTION
        else -> Band.RISK
    }

    /** Inclusive condition range of a band, for legends. */
    fun range(band: Band): IntRange? = when (band) {
        Band.EXCELLENT -> 80..100; Band.GOOD -> 60..79; Band.FAIR -> 40..59
        Band.CAUTION -> 20..39; Band.RISK -> 0..19; Band.UNKNOWN -> null
    }

    fun sentence(condition: Int): String = when (band(condition.coerceIn(0, 100))) {
        Band.EXCELLENT -> "눈이 아주 편안한 상태예요. 지금 리듬을 유지해 주세요."
        Band.GOOD -> "눈 상태가 좋아요. 틈틈이 먼 곳을 바라봐 주세요."
        Band.FAIR -> "눈에 부담이 쌓이기 시작했어요. 잠깐 쉬어 가면 좋아요."
        Band.CAUTION -> "눈이 피곤해지고 있어요. 화면에서 눈을 떼고 쉬어 주세요."
        else -> "눈 부담이 매우 커요. 지금은 화면을 내려놓고 충분히 쉬어 주세요."
    }

    /** [burdenDelta] is the stored-score difference; a lower burden reads as an improvement. */
    fun deltaSentence(burdenDelta: Int): String = when {
        burdenDelta < 0 -> "${-burdenDelta}점 좋아졌어요"
        burdenDelta > 0 -> "${burdenDelta}점 낮아졌어요"
        else -> "지난번과 같아요"
    }
}
