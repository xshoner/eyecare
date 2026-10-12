package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class ExposureEngineTest {
    @Test fun finalizedTailAndEveryQualityWindowPartitionWithoutMutation() {
        val e = ExposureEngine(0)
        e.frame(solid(.3f), 50)
        e.frame(solid(.7f), 550)
        e.snapshot(9000)
        e.frame(solid(.4f), 10950)
        e.frame(solid(.6f), 11450)
        val s = e.snapshot(11700, true)
        assertEquals(250L, s.excludedMs["end_tail"])
        assertEquals(10400L, s.excludedMs["sample_gap"])
        assertEquals(1L, s.counters["long_gaps"])
        assertEquals(2L, s.counters["valid_pairs"])
        assertEquals(3L, s.intervalBuckets.values.sum())
        for (sec in listOf(0, 10)) {
            val valid = s.bins.find { it.seconds == sec }?.validMs ?: 0
            val excluded = s.qualityWindows.find { it.seconds == sec }?.excludedMs?.values?.sum() ?: 0
            assertEquals(minOf(10000L, 11700L-sec*1000), valid+excluded)
        }
        assertEquals(s, e.snapshot(11700, true))
        assertEquals(250L, e.snapshot(11700).excludedMs["pending"])
    }
    @Test fun noFramesAndHiddenTailRemainUnknownInsteadOfSafe() {
        val e = ExposureEngine(0)
        assertEquals(12000L, e.snapshot(12000,true).excludedMs["baseline_wait"])
        e.frame(solid(.4f), 10)
        e.resetContinuity("hidden", 20)
        val s = e.snapshot(21000,true)
        assertEquals(20980L,s.excludedMs["hidden"])
        assertEquals(0L,s.validMs)
        assertEquals(21000L,s.qualityWindows.sumOf { it.excludedMs.values.sum() })
    }
    private fun solid(value: Float) = FloatArray(64) { value }
    @Test fun constantScreenHasCoverageButNoChange() {
        val e = ExposureEngine(0)
        for (i in 0..20) e.frame(solid(.5f), i * 500L)
        val s = e.snapshot(10000)
        assertEquals(10000, s.validMs)
        assertEquals(0, s.highMs)
        assertEquals(0.0, s.meanChange, 0.0001)
    }
    @Test fun knownFullScreenTransitionsAccumulateOnlyObservedTime() {
        val e = ExposureEngine(0)
        e.frame(solid(.2f), 0)
        e.frame(solid(.8f), 500)
        e.frame(solid(.2f), 1000)
        assertEquals(1000, e.snapshot(1000).highMs)
        assertEquals(.6, e.snapshot(1000).meanChange, .0001)
    }
    @Test fun longGapIsNotInterpolatedAndBreaksBaseline() {
        val e = ExposureEngine(0)
        e.frame(solid(.2f), 0)
        e.frame(solid(.8f), 500)
        e.frame(solid(.2f), 6000)
        e.frame(solid(.2f), 6500)
        assertEquals(1000, e.snapshot(6500).validMs)
        assertEquals(500, e.snapshot(6500).highMs)
        assertEquals("waiting_frames", e.snapshot(10000).quality)
    }
    @Test fun darkProtectedOrMissingInputNeverCountsAsSafeOrCreatesTransition() {
        val e = ExposureEngine(0)
        e.frame(solid(.8f), 0)
        e.frame(solid(0f), 500)
        assertEquals("dark_or_protected", e.snapshot(500).quality)
        e.frame(solid(.8f), 1000)
        assertEquals(0, e.snapshot(1000).validMs)
        e.frame(floatArrayOf(Float.NaN), 1500)
        assertEquals("invalid", e.snapshot(1500).quality)
    }
    @Test fun windowBoundaryPartitionsWithoutDoubleCounting() {
        val e = ExposureEngine(0)
        e.frame(solid(.2f), 9750)
        e.frame(solid(.8f), 10250)
        val s = e.snapshot(10250)
        assertEquals(listOf(250L, 250L), s.bins.map { it.validMs })
        assertEquals(s.validMs, s.bins.sumOf { it.validMs })
        assertEquals(s.highMs, s.bins.sumOf { it.highMs })
    }
    @Test fun rotationOrVisibilityResetDoesNotCreateFakeCut() {
        val e = ExposureEngine(0)
        e.frame(solid(.2f), 0)
        e.resetContinuity("hidden")
        e.frame(FloatArray(32) { .8f }, 500)
        assertEquals(0, e.snapshot(500).highMs)
    }
    @Test fun repeatedOrBackwardsTimestampsAreIgnored() {
        val e = ExposureEngine(0)
        e.frame(solid(.2f), 500)
        e.frame(solid(.8f), 500)
        e.frame(solid(.8f), 400)
        e.frame(solid(.2f), 1000)
        assertEquals(500, e.snapshot(1000).validMs)
        assertEquals(0, e.snapshot(1000).highMs)
    }
    @Test fun exclusionsPartitionElapsedTimeWithoutSnapshotDoubleCounting() {
        val e = ExposureEngine(0)
        e.frame(solid(.2f), 500)
        e.frame(solid(.8f), 1000)
        e.snapshot(1300)
        e.frame(solid(.2f), 1500)
        e.resetContinuity("hidden", 1600)
        e.resetContinuity("waiting", 3000)
        e.frame(solid(.8f), 3500)
        e.frame(solid(.2f), 4000)
        val s = e.snapshot(4500)
        assertEquals(4500, s.validMs + s.excludedMs.values.sum())
        assertEquals(1500, s.validMs)
        assertEquals(1400L, s.excludedMs["hidden"])
        assertEquals(100L, s.excludedMs["continuity_reset"])
        assertEquals(500L, s.excludedMs["pending"])
        assertEquals(s, e.snapshot(4500))
    }
    @Test fun histogramContrastAndBufferReusePreserveFeatures() {
        val e = ExposureEngine(0)
        val frame = FloatArray(100) { it / 100f }
        e.frame(frame, 0)
        assertEquals(.8, e.snapshot(0).contrast, .002)
        frame.fill(.5f)
        e.frame(frame, 500)
        assertTrue(e.snapshot(500).meanChange > .2)
        assertTrue(e.snapshot(500).meanGlobalChange < .01)
        assertTrue(e.snapshot(500).meanChangedArea > .6)
    }
    @Test fun darkInvalidAndLongGapRemainDistinctAndTotalled() {
        val e = ExposureEngine(0)
        e.frame(solid(.5f), 0)
        e.frame(solid(0f), 500)
        e.frame(solid(.5f), 1000)
        e.frame(solid(.5f), 4000)
        e.frame(floatArrayOf(Float.NaN), 4500)
        val s = e.snapshot(5000)
        assertEquals(5000, s.validMs + s.excludedMs.values.sum())
        assertEquals(1000L, s.excludedMs["dark_or_protected"])
        assertEquals(3000L, s.excludedMs["sample_gap"])
        assertEquals(1000L, s.excludedMs["invalid"])
    }
}
