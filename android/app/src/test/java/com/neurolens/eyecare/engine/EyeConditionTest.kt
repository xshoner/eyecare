package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class EyeConditionTest {
    @Test fun conditionIsTheInverseOfStoredBurden() {
        assertEquals(100, EyeCondition.fromBurden(0))
        assertEquals(0, EyeCondition.fromBurden(100))
        assertEquals(62, EyeCondition.fromBurden(38))
        assertEquals(0, EyeCondition.fromBurden(120))
        assertEquals(100, EyeCondition.fromBurden(-5))
        assertNull(EyeCondition.fromBurden(null as Int?))
    }

    @Test fun fiveBandsCoverZeroToHundredWithoutGaps() {
        val expected = mapOf(0 to EyeCondition.Band.RISK, 19 to EyeCondition.Band.RISK,
            20 to EyeCondition.Band.CAUTION, 39 to EyeCondition.Band.CAUTION,
            40 to EyeCondition.Band.FAIR, 59 to EyeCondition.Band.FAIR,
            60 to EyeCondition.Band.GOOD, 79 to EyeCondition.Band.GOOD,
            80 to EyeCondition.Band.EXCELLENT, 100 to EyeCondition.Band.EXCELLENT)
        expected.forEach { (value, band) -> assertEquals("condition $value", band, EyeCondition.band(value)) }
        assertEquals(EyeCondition.Band.UNKNOWN, EyeCondition.band(null))
        for (value in 0..100) {
            val band = EyeCondition.band(value)
            assertTrue("$value in ${band.label}", value in EyeCondition.range(band)!!)
        }
        assertEquals(listOf("위험", "주의", "보통", "양호", "매우 양호"), EyeCondition.SCALE.map { it.label })
    }

    @Test fun restAlertStartsInsideTheFairOrLowerBands() {
        // RestAlertPolicy starts at stored burden >= 50, i.e. condition <= 50.
        assertEquals(EyeCondition.Band.FAIR, EyeCondition.band(EyeCondition.fromBurden(50)))
        assertTrue(EyeCondition.fromBurden(50) <= 50)
    }

    @Test fun lowerBurdenReadsAsImprovement() {
        assertTrue(EyeCondition.deltaSentence(-7).contains("7점 좋아졌어요"))
        assertTrue(EyeCondition.deltaSentence(4).contains("4점 낮아졌어요"))
        assertEquals("지난번과 같아요", EyeCondition.deltaSentence(0))
    }

    @Test fun eachBandHasItsOwnSentence() {
        val sentences = listOf(100, 70, 50, 30, 10).map { EyeCondition.sentence(it) }
        assertEquals(5, sentences.toSet().size)
        assertEquals(EyeCondition.sentence(100), EyeCondition.sentence(130))
    }
}
