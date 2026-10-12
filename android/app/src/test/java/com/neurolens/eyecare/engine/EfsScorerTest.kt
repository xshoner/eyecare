package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class EfsScorerTest {
    @Test fun missingScreenDoesNotBecomeZeroScore() {
        val result = EfsScorer.score(40.0, emptyList(), 0, 300)
        assertEquals(50, result.score)
        assertNull(result.screenScore)
        assertEquals(listOf("U01"), result.inputs)
        assertEquals(0.0, result.coverage, 0.001)
    }

    @Test fun screenWindowUsesObservedTimeOnly() {
        val result = EfsScorer.score(0.0,
            listOf(ExposureBin(0, 5000, .12, 5000), ExposureBin(300, 5000, 0.0, 0)), 0, 10)
        assertEquals(100, result.screenScore)
        assertEquals(listOf("U01", "S06", "S08"), result.inputs)
        assertEquals(.5, result.coverage, 0.001)
    }
}
