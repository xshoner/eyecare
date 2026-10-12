package com.neurolens.eyecare

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neurolens.eyecare.data.CareReport
import com.neurolens.eyecare.data.SessionRecord
import com.neurolens.eyecare.data.SessionStore
import com.neurolens.eyecare.data.SubjectiveResponse
import com.neurolens.eyecare.engine.StrainModel
import com.neurolens.eyecare.engine.Signal
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds SYNTHETIC demo reports for screenshot checks on an emulator. It is skipped unless the
 * instrumentation argument seedDemo=true is passed, so a normal test run never touches app data.
 */
@RunWith(AndroidJUnit4::class)
class DemoSeedTest {
    @Test fun seedSyntheticDashboardData() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedDemo") == "true")
        val store = SessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        // burden, minutes, cm, lux, blinks, nrs, days ago
        val rows = listOf(
            listOf(18, 25.0, 38.0, 180.0, 14.0, 3, 6), listOf(32, 40.0, 33.0, 60.0, 11.0, 4, 5),
            listOf(45, 55.0, 29.0, 20.0, 9.0, 6, 4), listOf(27, 30.0, 36.0, 240.0, 15.0, 3, 3),
            listOf(58, 70.0, 26.0, 4.0, 7.0, 7, 2), listOf(36, 45.0, 31.0, 35.0, 10.0, 5, 1),
            listOf(24, 20.0, 34.0, 150.0, 13.0, null, 0), listOf(31, 35.0, 30.0, 8.0, 12.0, null, 0))
        rows.forEachIndexed { i, r ->
            val at = now - (r[6] as Int) * day - (rows.size - i) * 600_000L
            val session = SessionRecord(id = "demo-session-$i", startedAt = at - 1_800_000,
                reason = "user_stopped", appVersion = "0.13.0-alpha", elapsedMs = 1_800_000)
            store.save(session)
            val burden = r[0] as Int
            val report = CareReport(id = "demo-report-$i", sessionId = session.id, createdAt = at,
                windowStartAt = at - 1_800_000, intervalMin = 30, score = burden, level = "demo",
                confidence = "보통 · 감점 모델", inputs = emptyList(), usageScore = 0, screenScore = null,
                screenCoverage = .9, version = StrainModel.VERSION, availableWeight = 80, scoreAvailable = true,
                contributions = mapOf("screen_time" to 3.0, "dark" to (if ((r[3] as Double) < 50) burden * .45 else 0.0),
                    "near" to (if ((r[2] as Double) < 35) burden * .3 else burden * .1), "long" to burden * .2, "flash" to burden * .05),
                mvpDomains = mapOf("screen" to (burden + 10).coerceAtMost(100), "environment" to (if ((r[3] as Double) < 50) 55 else 15),
                    "posture" to 20, "usage" to 60, "eyes" to burden),
                mvpSignals = mapOf(
                    "U01.continuousMin" to Signal.observed(r[1] as Double, .9),
                    "D01.distanceEstimateCm" to Signal.observed(r[2] as Double, .8),
                    "D05.validMinutes" to Signal.observed(20.0),
                    "E01.lux" to Signal.observed(r[3] as Double, .9),
                    "F02.blinksPerMin" to Signal.observed(r[4] as Double, .7)))
            store.saveReport(report)
            (r[5] as Int?)?.let { store.addSubjectiveResponse(SubjectiveResponse(reportId = report.id, nrs = it, answeredAt = at + 60_000)) }
        }
    }
}
