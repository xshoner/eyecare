package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class EyeGeometryTest {
    private fun face(closed: Boolean = false): List<FacePoint> = MutableList(478) { FacePoint(.5f, .5f) }.apply {
        this[33] = FacePoint(.2f, .4f); this[133] = FacePoint(.4f, .4f)
        this[362] = FacePoint(.6f, .4f); this[263] = FacePoint(.8f, .4f)
        listOf(160,159,158,385,386,387).forEach { this[it] = FacePoint(.5f, if (closed) .4f else .38f) }
        listOf(144,145,153,380,374,373).forEach { this[it] = FacePoint(.5f, if (closed) .4f else .42f) }
        this[468] = FacePoint(.3f, .4f); this[473] = FacePoint(.7f, .4f)
    }

    @Test fun irisPositionIsRelativeToEyeNotScreen() {
        val g = EyeGeometryExtractor.extract(face())!!
        assertEquals(.5, g.meanU!!, .001)
        assertEquals(.5, g.meanV!!, .001)
        assertEquals(.2, g.meanOpen, .001)
    }

    @Test fun closedEyeRetainsOpenMeasurementButMarksIrisMissing() {
        val g = EyeGeometryExtractor.extract(face(closed = true))!!
        assertEquals(0.0, g.meanOpen, .001)
        assertNull(g.meanU)
        assertNull(EyeGeometryExtractor.extract(emptyList()))
    }

    @Test fun stabilityRequiresDurationAndDoesNotCountObservationGapsAsFastMoves() {
        val tracker = GazeStabilityTracker()
        val geometry = EyeGeometry(.2, .2, .5, .5, .5, .5)
        for (i in 0..9) tracker.add(i * 300L, geometry)
        assertEquals("stable_relative", tracker.add(3000, geometry).state)
        val moved = geometry.copy(eyeAU = .8, eyeBU = .8)
        assertEquals("moving_relative", tracker.add(3200, moved).state)
        val later = tracker.add(15000, moved)
        assertEquals("insufficient", later.state)
    }
}
