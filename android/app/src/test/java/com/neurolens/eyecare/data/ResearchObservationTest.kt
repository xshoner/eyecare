package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.EfsScorer
import com.neurolens.eyecare.engine.Signal
import com.neurolens.eyecare.engine.ExposureStats
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ResearchObservationTest {
    private val reportId = "9b7f15f5-66c4-43e2-88b6-7db8930f8c17"
    private val pseudonym = UUID.fromString("6a66086a-b1a0-4d3b-919c-14b64a896b41")

    @Test fun whitelistDropsFreeFormContentAndKeepsMissingAsNull() {
        val report = CareReport(id = reportId, sessionId = "private-session", createdAt = 1234,
            windowStartAt = 1000, intervalMin = 30, score = 57, level = "private screen text",
            confidence = "partial", inputs = listOf("private-app-id"), usageScore = 25,
            screenScore = 40, screenCoverage = .7, version = MvpScorer.VERSION,
            mvpDomains = mapOf("screen" to 40, "usage" to 25),
            mvpSignals = mapOf("S06.change" to Signal.observed(.09),
                "E01.lux" to Signal.missing("sensor_absent"),
                "F13.distanceCm" to Signal.missing("camera_calibration_unavailable"),
                "D01.distanceEstimateCm" to Signal.observed(23.0, .6, estimated = true),
                "D02.nearExposureRisk" to Signal.observed(85.0, .6, estimated = true),
                "D04.coverage" to Signal.observed(.75),
                "D05.validMinutes" to Signal.observed(7.5),
                "D06.calibrated" to Signal.observed(0.0),
                "D08.windowMinutes" to Signal.observed(10.0),
                "U02.todayMin" to Signal.observed(300.0)),
            domainQualities = mapOf("screen" to .7, "usage" to .5), nrs = 6)
        val record = ResearchObservationFactory.from(report, pseudonym, "research-v1")!!
        assertEquals(57, record.score)
        assertNull(record.ambientLux)
        assertNull(record.calibratedDistanceCm)
        assertEquals(23.0, record.distanceEstimateCm!!, .001)
        assertEquals(.6, record.distanceEstimateQuality!!, .001)
        assertEquals(.75, record.distanceCoverage!!, .001)
        assertEquals(false, record.distanceCalibrated)
        assertEquals(7.5, record.distanceValidMinutes!!, .001)
        assertEquals(10.0, record.distanceWindowMinutes!!, .001)
        assertTrue(record.estimatedFields.contains("distance_estimate_cm"))
        assertEquals(MvpScorer.VERSION, record.algorithmVersion)
        assertEquals(300.0, record.todayMinutes!!, .001)
        assertEquals(6, record.subjectiveFatigueNrs)
        assertFalse(record.toString().contains("private screen text"))
        assertFalse(record.toString().contains("private-app-id"))
        assertEquals(record.id, ResearchObservationFactory.from(report, pseudonym, "research-v1")!!.id)
    }

    @Test fun oldAndNewModelVersionsRemainUploadableWithoutInventedDistance() {
        val report = CareReport(id = reportId, sessionId = "fixture", createdAt = 1234,
            windowStartAt = 1000, intervalMin = 30, score = 40, level = "test",
            confidence = "partial", inputs = emptyList(), usageScore = 20,
            screenScore = 30, screenCoverage = .5, version = MvpScorer.LEGACY_VERSION)
        assertNotNull(ResearchObservationFactory.from(report, pseudonym, "research-v1"))
        assertNotNull(ResearchObservationFactory.from(report.copy(version = MvpScorer.VERSION),
            pseudonym, "research-v1"))
        assertNull(ResearchObservationFactory.from(report, pseudonym, "research-v1")!!.distanceEstimateCm)
        assertNull(ResearchObservationFactory.from(report, pseudonym, "research-v1")!!.distanceCalibrated)
    }

    @Test fun missingOrInvalidDistanceKeepsEstimateAndCalibrationNull() {
        val basic = CareReport(id = reportId, sessionId = "synthetic", createdAt = 1234,
            windowStartAt = 1000, intervalMin = 30, score = 42, level = "test",
            confidence = "partial", inputs = emptyList(), usageScore = 20,
            screenScore = 30, screenCoverage = .5, version = MvpScorer.VERSION)
        val missing = ResearchObservationFactory.from(basic.copy(mvpSignals = mapOf(
            "D01.distanceEstimateCm" to Signal.missing("no_face"),
            "D04.coverage" to Signal.observed(0.0),
            "D05.validMinutes" to Signal.observed(0.0),
            "D08.windowMinutes" to Signal.observed(10.0),
            "D06.calibrated" to Signal.observed(0.0))), pseudonym, "research-v1")!!
        assertNull(missing.distanceEstimateCm)
        assertNull(missing.distanceEstimateQuality)
        assertNull(missing.distanceCalibrated)
        assertEquals(0.0, missing.distanceCoverage!!, .001)
        assertEquals(0.0, missing.distanceValidMinutes!!, .001)
        assertEquals(10.0, missing.distanceWindowMinutes!!, .001)
        assertFalse(missing.estimatedFields.contains("distance_estimate_cm"))
        val inconsistent = ResearchObservationFactory.from(basic.copy(mvpSignals = mapOf(
            "D01.distanceEstimateCm" to Signal.observed(24.0, .6, estimated = true),
            "D05.validMinutes" to Signal.observed(11.0),
            "D08.windowMinutes" to Signal.observed(10.0))), pseudonym, "research-v1")!!
        assertNull(inconsistent.distanceEstimateCm)
        assertNull(inconsistent.distanceCalibrated)
        assertNull(inconsistent.distanceValidMinutes)
    }

    @Test fun unavailableAndUnapprovedRecordsAreNotMisrepresented() {
        val report = CareReport.unavailable("session", 1_000, "screen_capture_not_authorized")
        assertNull(ResearchObservationFactory.from(report, pseudonym, "research-v1")!!.score)
        val current = report.copy(id = reportId, version = MvpScorer.VERSION)
        assertNull(ResearchObservationFactory.from(current, pseudonym, "other-consent"))
        val record = ResearchObservationFactory.from(current, pseudonym, "research-v1")!!
        assertNull(record.score)
        assertEquals("unavailable", record.confidenceCode)
    }

    @Test fun fourteenMixedHistoricalReportsKeepStableIdsAndOnlyKnownFields() {
        val reports = (0 until 14).map { index ->
            CareReport(id = UUID.nameUUIDFromBytes("synthetic-$index".toByteArray()).toString(),
                sessionId = "synthetic-session-$index", createdAt = 1_700_000_000_000L + index,
                windowStartAt = 1_700_000_000_000L, intervalMin = 30,
                score = 20 + index, level = "synthetic only", confidence = "partial",
                inputs = listOf("private-app-name-must-not-appear"), usageScore = 35,
                screenScore = 42, screenCoverage = .5,
                version = if (index % 2 == 0) EfsScorer.VERSION else MvpScorer.VERSION)
        }
        val observations = reports.map { ResearchObservationFactory.from(it, pseudonym, "research-v1")!! }
        assertEquals(14, observations.map { it.reportId }.distinct().size)
        assertEquals(14, observations.map { it.id }.distinct().size)
        assertEquals(observations.first().id,
            ResearchObservationFactory.from(reports.first(), pseudonym, "research-v1")!!.id)
        assertEquals(42, observations.first().domainScores["screen"])
        assertNull(observations.first().domainQualities["screen"])
        assertNull(observations.first().ambientLux)
        assertFalse(observations.first().toString().contains("private-app-name"))
    }

    @Test fun fourteenOldSessionsProduceMeasuredAggregatesWithoutInventedScores() {
        val sessions = (0 until 14).map { index ->
            SessionRecord(id = UUID.nameUUIDFromBytes("old-session-$index".toByteArray()).toString(),
                startedAt = 1_700_000_000_000L + index * 100_000L, elapsedMs = 60_000L,
                stats = ExposureStats(validMs = 45_000, frames = 45, meanChange = .08),
                reason = "finished", content = "private content", device = "private device")
        }
        val rows = sessions.map {
            ResearchObservationFactory.fromLegacySession(it, pseudonym, "research-v1")!! }
        assertEquals(14, rows.map { it.reportId }.distinct().size)
        assertEquals("legacy-session-aggregate-v1", rows.first().algorithmVersion)
        assertEquals(.08, rows.first().screenChange!!, .0001)
        assertNull(rows.first().score)
        assertNull(rows.first().subjectiveFatigueNrs)
        assertFalse(rows.first().toString().contains("private content"))
        assertFalse(rows.first().toString().contains("private device"))
        assertEquals(rows.first().id,
            ResearchObservationFactory.fromLegacySession(sessions.first(), pseudonym, "research-v1")!!.id)
        assertNull(ResearchObservationFactory.fromLegacySession(
            sessions.first().copy(reason = "active"), pseudonym, "research-v1"))
    }
}
