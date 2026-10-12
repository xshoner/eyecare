package com.neurolens.eyecare.engine

import com.neurolens.eyecare.data.CareReport
import org.junit.Assert.*
import org.junit.Test

class MvpSignalsTest {
    private fun stimulating() = mapOf(
        "E01.lux" to Signal.observed(15.0),
        "S16.emissionProxy" to Signal.observed(.65, .7, estimated = true),
        "S06.change" to Signal.observed(.08))

    @Test fun darkBrightIsAnEnvironmentFeatureWithoutScenarioBonus() {
        val low = MvpScorer.score(mapOf("E08.darkBrightRisk" to Signal.observed(20.0),
            "E05.thermalStatus" to Signal.observed(1.0)))
        val high = MvpScorer.score(mapOf("E08.darkBrightRisk" to Signal.observed(90.0),
            "E05.thermalStatus" to Signal.observed(1.0)))
        assertTrue(high.domains.getValue("environment") > low.domains.getValue("environment"))
        assertTrue(high.adjustments.isEmpty())
    }

    @Test fun restMissingInputAndNewDayResetExposure() {
        val tracker = ScenarioExposureTracker()
        for (minute in 0..6) tracker.observe(minute * 60_000L, 123, true, stimulating())
        assertEquals("screen_off_or_rest", tracker.observe(420_000L, 123, false, stimulating()).reason)
        assertEquals(0.0, tracker.observe(480_000L, 123, true, stimulating()).value!!, .001)
        assertNull(tracker.observe(540_000L, 123, true,
            stimulating() + ("E01.lux" to Signal.missing("sensor_absent"))).value)
        assertEquals(0.0, tracker.observe(600_000L, 123, true, stimulating()).value!!, .001)
        for (minute in 11..16) tracker.observe(minute * 60_000L, 123, true, stimulating())
        assertEquals(0.0, tracker.observe(1_020_000L, 124, true, stimulating()).value!!, .001)
    }

    @Test fun weightsAndNearDistanceOnlyCountInsideUsageDomain() {
        val inputs = mapOf("U01.continuousMin" to Signal.observed(30.0),
            "D02.nearExposureRisk" to Signal.observed(80.0, .6, estimated = true))
        val result = MvpScorer.score(inputs)
        assertEquals(listOf(26, 22, 8, 18, 26), result.nominalWeights.values.toList())
        assertEquals(100, result.nominalWeights.values.sum())
        assertEquals(0, result.adjustments.sumOf { it.points })
        assertTrue(result.domains.getValue("usage") > MvpScorer.score(mapOf(
            "U01.continuousMin" to Signal.observed(30.0))).domains.getValue("usage"))
    }

    @Test fun dailyUseDoesNotCreateOverlappingScenarioBonus() {
        val below = MvpScorer.score(mapOf("U01.continuousMin" to Signal.observed(30.0),
            "U02.todayMin" to Signal.observed(239.0)))
        val above = MvpScorer.score(mapOf("U01.continuousMin" to Signal.observed(30.0),
            "U02.todayMin" to Signal.observed(480.0)))
        assertEquals(below.score, above.score)
        assertTrue(above.adjustments.isEmpty())
    }

    @Test fun fatigueSentencesCoverBandBoundaries() {
        assertEquals(MvpScorer.sentence(0), MvpScorer.sentence(24))
        assertNotEquals(MvpScorer.sentence(24), MvpScorer.sentence(25))
        assertNotEquals(MvpScorer.sentence(49), MvpScorer.sentence(50))
        assertNotEquals(MvpScorer.sentence(74), MvpScorer.sentence(75))
        assertEquals(MvpScorer.sentence(100), MvpScorer.sentence(120))
    }
    @Test fun missingIsNeverConvertedToMeasuredZero() {
        val score = MvpScorer.score(mapOf("U01.continuousMin" to Signal.observed(60.0),
            "S06.change" to Signal.missing("screen_off")))
        assertEquals(setOf("usage"), score.domains.keys)
        assertEquals(1, score.inputs.size)
        assertNull(score.signals["S06.change"]?.value)
        assertEquals("screen_off", score.signals["S06.change"]?.reason)
    }

    @Test fun lowQualityObservationContributesLessButIsNotErased() {
        val strong = MvpScorer.score(mapOf("S06.change" to Signal.observed(.12),
            "S08.largeRatio" to Signal.observed(0.0)))
        val weak = MvpScorer.score(mapOf("S06.change" to Signal.observed(.12, .1),
            "S08.largeRatio" to Signal.observed(0.0)))
        assertTrue(weak.score < strong.score)
        assertEquals(.12, weak.signals.getValue("S06.change").value!!, .0001)
        assertFalse("S06.change" in weak.inputs)
    }

    @Test fun yawnAloneCannotCreateEyeFatigueDomain() {
        val score = MvpScorer.score(mapOf("F10.yawns" to Signal.observed(5.0),
            "U01.continuousMin" to Signal.observed(10.0)))
        assertFalse(score.domains.containsKey("eyes"))
        assertEquals("낮음 · 부분 관측", score.confidence)
    }

    @Test fun baselineAdjustsOpenEyeRiskOnlyWhenEstablished() {
        val inputs = mapOf("F02.blinksPerMin" to Signal.observed(17.0),
            "F06.openMean" to Signal.observed(.18),
            "F07.closure80" to Signal.observed(.1), "F09.squint" to Signal.observed(.2))
        val fresh = MvpScorer.score(inputs, PersonalBaseline(samples = 5,
            open = BaselineMetric(.3, 1, 1.0)))
        val established = MvpScorer.score(inputs, PersonalBaseline(samples = 5,
            open = BaselineMetric(.3, 3, 3.0)))
        assertTrue(established.domains.getValue("eyes") > fresh.domains.getValue("eyes"))
    }

    @Test fun eachBaselineMetricCountsOnlyItsOwnValidObservations() {
        val first = PersonalBaseline().updated(Signal.observed(.2), null, Signal.observed(30.0),
            null, null, null, null)
        val second = first.updated(null, Signal.observed(12.0), Signal.missing("permission_missing"),
            null, null, null, null)
        val third = second.updated(Signal.observed(.3), null, null, null, null, null, null)
        assertEquals(3, third.samples)
        assertEquals(2, third.open.count)
        assertEquals(.25, third.open.mean!!, .0001)
        assertEquals(1, third.usage.count)
        assertEquals(30.0, third.usage.mean!!, .0001)
        assertEquals(1, third.blink.count)
        assertFalse(third.open.ready)
    }

    @Test fun baselineQualityWeightsAverageAndBlocksWeakHistory() {
        val metric = BaselineMetric().updated(Signal.observed(10.0, 1.0))
            .updated(Signal.observed(20.0, .2)).updated(Signal.observed(30.0, .2))
        assertEquals(3, metric.count)
        assertEquals(1.4, metric.qualitySum, .0001)
        assertEquals(14.2857, metric.mean!!, .001)
        assertFalse(metric.ready)
        assertEquals(metric, metric.updated(Signal.missing("sensor_absent")))
    }

    @Test fun habitualSwitchesAndBreaksDoNotDoubleCountObservedTime() {
        val ordinary = MvpScorer.score(mapOf("U01.continuousMin" to Signal.observed(30.0)))
        val unusual = MvpScorer.score(mapOf("U01.continuousMin" to Signal.observed(30.0),
            "H04.switchesVsBaseline" to Signal.observed(3.0, estimated = true),
            "H06.microBreaksVsBaseline" to Signal.observed(-3.0, estimated = true)))
        val missing = MvpScorer.score(mapOf("U01.continuousMin" to Signal.observed(30.0),
            "H04.switchesVsBaseline" to Signal.missing("baseline_gathering")))
        assertEquals(ordinary.domains.getValue("usage"), unusual.domains.getValue("usage"))
        assertEquals(ordinary.domains, missing.domains)
        assertFalse("H04.switchesVsBaseline" in missing.inputs)
    }

    @Test fun sparseDataCannotBeHighConfidence() {
        val result = MvpScorer.score(mapOf("F01.coverage" to Signal.observed(1.0),
            "F01.observedMin" to Signal.observed(5.0), "F06.openMean" to Signal.observed(.1),
            "F07.closure80" to Signal.observed(.3), "F09.squint" to Signal.observed(.4)))
        assertEquals(MvpScorer.score(emptyMap()).confidence, result.confidence)
    }

    @Test fun eyeEventsNeedObservedStartAndEnd() {
        val eyes = EyeSignalEngine()
        eyes.add(1_000, .25, .25, mapOf("jawOpen" to 0f))
        eyes.add(1_100, .24, .24, mapOf("jawOpen" to 0f))
        eyes.add(1_200, .05, .05, mapOf("eyeBlinkLeft" to .8f, "eyeBlinkRight" to .8f))
        eyes.add(1_300, .24, .24, mapOf("jawOpen" to .8f))
        eyes.add(2_000, .24, .24, mapOf("jawOpen" to .8f))
        eyes.add(2_900, .24, .24, mapOf("jawOpen" to .8f))
        val snapshot = eyes.snapshot(2_900)
        assertTrue(snapshot.getValue("F02.blinksPerMin").value!! > 0)
        assertEquals(1.0, snapshot.getValue("F10.yawns").value!!, 0.001)
    }

    @Test fun eyeGapCannotFabricateYawnOrHighConfidence() {
        val eyes = EyeSignalEngine()
        eyes.add(1_000, .25, .25, mapOf("jawOpen" to .9f))
        eyes.add(4_000, .25, .25, mapOf("jawOpen" to .9f))
        eyes.add(4_100, .25, .25, mapOf("jawOpen" to 0f))
        val signals = eyes.snapshot(4_100)
        assertEquals(0.0, signals.getValue("F10.yawns").value!!, 0.001)
        assertTrue(signals.getValue("F01.coverage").value!! < .05)
        val stale = eyes.snapshot(300_000)
        assertNull(stale.getValue("F10.yawns").value)
        assertEquals("stale_eye_sample", stale.getValue("F10.yawns").reason)
    }

    @Test fun rgbMetricsContainColorAndChangeWithoutKeepingPixels() {
        val screen = ScreenSignalEngine()
        screen.frame(IntArray(64) { 0xFF202020.toInt() }, 8, 8, 1_000)
        screen.frame(IntArray(64) { 0xFF2020FF.toInt() }, 8, 8, 1_500)
        val snapshot = screen.snapshot(1_500)
        assertTrue(snapshot.getValue("S03.blueRatio").value!! > .5)
        assertTrue(snapshot.getValue("S06.change").value!! > 0)
        assertNotNull(snapshot["S04.saturation"]?.value)
        assertNotNull(snapshot["S12.edgeDensity"]?.value)
        assertNotNull(snapshot["S17.coverage"]?.value)
    }

    @Test fun everyScreenMetricIsExplicitlyMissingWithoutCapture() {
        val signals = ScreenSignalEngine().snapshot(1_000)
        assertEquals(ScreenSignalEngine.METRICS.toSet(), signals.keys)
        assertEquals("no_capture", signals.getValue("S03.blueRatio").reason)
        assertEquals("no_capture", signals.getValue("S06.change").reason)
        assertNull(signals.getValue("S06.change").value)
    }

    @Test fun lockAndCaptureStopCannotReuseOldPixels() {
        val screen = ScreenSignalEngine()
        screen.frame(IntArray(64) { 0xFF808080.toInt() }, 8, 8, 1_000)
        screen.frame(IntArray(64) { 0xFF909090.toInt() }, 8, 8, 1_500)
        screen.reset("screen_off")
        assertEquals("screen_off", screen.snapshot(2_000, "screen_off").getValue("S01.apl").reason)
        assertEquals("no_capture", screen.snapshot(2_000).getValue("S01.apl").reason)
        assertEquals("capture_stopped", screen.snapshot(2_000, "capture_stopped").getValue("S01.apl").reason)
    }

    @Test fun oneFrameDoesNotInventZeroChangeOrFlash() {
        val screen = ScreenSignalEngine()
        screen.frame(IntArray(64) { 0xFF808080.toInt() }, 8, 8, 1_000)
        val result = screen.snapshot(1_000)
        for (id in listOf("S06.change", "S07.changedArea", "S08.largeRatio", "S09.flashPerMin", "S10.scenePerMin")) {
            assertNull(result.getValue(id).value)
            assertEquals("no_frame_pair", result.getValue(id).reason)
        }
    }

    @Test fun deniedCameraListsEveryEyeMetricAsMissing() {
        val result = EyeSignalEngine().snapshot(1_000, "camera_permission_denied")
        assertEquals(EyeSignalEngine.METRICS.toSet(), result.keys)
        assertEquals("camera_permission_denied", result.getValue("F06.openMean").reason)
    }

    @Test fun deniedScreenCaptureCreatesAvailabilityReportWithoutScore() {
        val report = CareReport.unavailable("session-1", 1_000, "screen_capture_not_authorized")
        assertFalse(report.scoreAvailable)
        assertTrue(report.mvpDomains.isEmpty())
        for (id in ScreenSignalEngine.METRICS + ScreenSignalEngine.CONTEXT_METRICS + EyeSignalEngine.METRICS) {
            assertNull(report.mvpSignals.getValue(id).value)
            assertEquals("screen_capture_not_authorized", report.mvpSignals.getValue(id).reason)
        }
    }

    @Test fun shortScreenOffCountsMicroRestOnce() {
        val scheduler = ReportScheduler(startMs = 0)
        scheduler.update(10_000, false)
        scheduler.update(30_000, true)
        scheduler.update(30_000, true)
        assertEquals(1, scheduler.microRestCount)
        assertEquals(0, scheduler.restCount)
    }
}
