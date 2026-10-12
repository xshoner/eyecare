package com.neurolens.eyecare.engine

import org.junit.Assert.*
import org.junit.Test

class IrisDistanceTest {
    private val open = EyeGeometry(.2, .2, .5, .5, .5, .5)
    private fun points(diameter: Double, rightScale: Double = 1.0): List<FacePoint> {
        val p = MutableList(478) { FacePoint(.5f, .5f) }
        fun eye(center: Double, first: Int, scale: Double) {
            val rX = diameter * scale / 2 / 640
            val rY = diameter * scale / 2 / 480
            p[first] = FacePoint((center + rX).toFloat(), .5f)
            p[first + 1] = FacePoint(center.toFloat(), (.5 - rY).toFloat())
            p[first + 2] = FacePoint((center - rX).toFloat(), .5f)
            p[first + 3] = FacePoint(center.toFloat(), (.5 + rY).toFloat())
        }
        eye(.35, 469, 1.0); eye(.65, 474, rightScale)
        return p
    }
    @Test fun geometryAndCalibrationFollowInversePixelSize() {
        val far = IrisDistance.estimate(points(12.0), 640, 480, 450.0, "camera2_intrinsic", open)
        val near = IrisDistance.estimate(points(24.0), 640, 480, 450.0, "camera2_intrinsic", open)
        assertTrue(far.cm!! > near.cm!!)
        assertEquals(far.cm!! / 2, near.cm!!, 1.0)
        val calibrated = IrisDistance.estimate(points(20.0), 640, 480, null,
            "unavailable", open, calibrationCmPx = 600.0)
        assertEquals(30.0, calibrated.cm!!, .1)
        assertTrue(calibrated.calibrated)
    }
    @Test fun geometryMissingPoseAndOutOfRangeNeverBecomePlausibleCm() {
        assertNull(IrisDistance.estimate(points(20.0), 640, 480, null, "unavailable", open).cm)
        assertNull(IrisDistance.estimate(points(20.0, .5), 640, 480, 450.0,
            "camera2_intrinsic", open).cm)
        assertNull(IrisDistance.estimate(points(20.0), 640, 480, 450.0,
            "camera2_intrinsic", null).cm)
        assertNull(IrisDistance.estimate(points(5.0), 640, 480, 450.0,
            "camera2_intrinsic", open).cm)
    }
    @Test fun weightedMedianNearExposureAndMissingCoverageAreSeparate() {
        val tracker = DistanceTracker()
        for (i in 0..10) tracker.add(i * 1_000L, DistanceEstimate(
            if (i < 7) 25.0 else 45.0, .8, 20.0, "camera2_intrinsic"))
        val report = tracker.snapshot(10_000L, 10_000L)
        assertEquals(25.0, report.medianCm!!, .01)
        assertEquals(.7, report.nearRatio!!, .01)
        assertEquals(1.0, report.coverage, .01)
        tracker.add(15_000L, DistanceEstimate(reason = "no_face"))
        val sparse = tracker.snapshot(15_000L, 15_000L)
        assertTrue(sparse.coverage < 1.0)
        assertTrue(ContextRisk.near(20.0) > ContextRisk.near(35.0))
    }
    @Test fun darkerMeasuredLuxWithSameSettingRaisesRelativeRisk() {
        assertTrue(ContextRisk.darkBright(5.0, .7)!! > ContextRisk.darkBright(40.0, .7)!!)
        assertNull(ContextRisk.darkBright(null, .7))
        assertEquals(0.0, ContextRisk.darkBright(100.0, 1.0)!!, .01)
        assertEquals("good", ContextRisk.band(24))
        assertEquals("ordinary", ContextRisk.band(25))
        assertEquals("caution", ContextRisk.band(50))
    }

    @Test fun rulerDistancesAndJitterFixtureAreOnlySyntheticGeometryChecks() {
        for (cm in listOf(20.0, 25.0, 30.0, 40.0, 50.0, 60.0)) {
            val diameter = 600.0 / cm
            val result = IrisDistance.estimate(points(diameter), 640, 480,
                null, "unavailable", open, calibrationCmPx = 600.0)
            assertEquals(cm, result.cm!!, .15)
        }
        val tracker = DistanceTracker()
        listOf(30.0, 31.0, 29.0, 30.0, 45.0, 30.0, 30.0).forEachIndexed { i, cm ->
            tracker.add(i * 1000L, DistanceEstimate(cm, .8, 20.0, "user_reference", calibrated = true))
        }
        val summary = tracker.snapshot(6000, 6000)
        assertEquals(30.0, summary.medianCm!!, .1)
        assertNotNull(summary.jitterCm)
        assertTrue(summary.jitterCm!! <= 1.0)
    }

    @Test fun consecutiveReportWindowsDoNotReuseExposureDuration() {
        val tracker = DistanceTracker()
        for (second in 0..20) tracker.add(second * 1_000L, DistanceEstimate(
            if (second < 10) 25.0 else 40.0, .8, 20.0, "synthetic"))
        val first = tracker.snapshot(10_000L, 10_000L)
        val second = tracker.snapshot(20_000L, 10_000L)
        assertEquals(10_000L, first.observedMs)
        assertEquals(10_000L, second.observedMs)
        assertEquals(1.0, first.nearRatio!!, .001)
        assertEquals(0.0, second.nearRatio!!, .001)
        assertEquals(25.0, first.medianCm!!, .001)
        assertEquals(40.0, second.medianCm!!, .001)
    }
}
