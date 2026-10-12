package com.neurolens.eyecare.data

import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.Signal
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class ResearchDataExporterTest {
    private fun report(i: Int, score: Int = 30, available: Boolean = true, nrs: Int? = null,
        signals: Map<String, Signal> = emptyMap()) = CareReport(
        id = "r-$i", sessionId = "s-$i", createdAt = i * 1_000_000L,
        windowStartAt = i * 1_000_000L - 60_000, intervalMin = 30,
        score = score, level = "test", confidence = "보통 · 연구용", inputs = emptyList(),
        usageScore = 20, screenScore = 30, screenCoverage = .5, version = MvpScorer.VERSION,
        scoreAvailable = available, availableWeight = 100, nrs = nrs, nrsAt = nrs?.let { i * 1_000_000L + 5 },
        mvpDomains = mapOf("screen" to 40), domainQualities = mapOf("screen" to .8),
        contributions = mapOf("screen" to 10.4), mvpSignals = signals)
    private fun rows(csv: String) = csv.trimEnd().split("\r\n").map { it.split(",") }

    @Test fun oneRowPerReportInTimeOrderWithConditionAndAllNrsAnswers() {
        val csv = ResearchDataExporter.csv(listOf(report(2), report(1)), listOf(
            SubjectiveResponse("b", "r-1", 7, 1_000_200L),
            SubjectiveResponse("a", "r-1", 4, 1_000_100L)), ZoneOffset.UTC)
        val table = rows(csv)
        val h = table[0]
        assertEquals(3, table.size)
        assertEquals("r-1", table[1][h.indexOf("report_id")])
        assertEquals("30", table[1][h.indexOf("burden_score")])
        assertEquals("70", table[1][h.indexOf("condition_score")])
        assertEquals("4", table[1][h.indexOf("nrs_first")])
        assertEquals("7", table[1][h.indexOf("nrs_latest")])
        assertEquals("2", table[1][h.indexOf("nrs_count")])
        assertEquals("0", table[2][h.indexOf("nrs_count")])
        assertEquals("1970-01-01T00:16:40Z", table[1][h.indexOf("created_at")])
        assertEquals("10.4", table[1][h.indexOf("screen_contribution")])
    }

    @Test fun missingValuesStayEmptyNotZero() {
        val csv = ResearchDataExporter.csv(listOf(report(1, available = false, signals = mapOf(
            "E01.lux" to Signal.missing("stale_sensor_sample"),
            "S06.change" to Signal.observed(0.0001, .6)))), emptyList(), ZoneOffset.UTC)
        val (h, r) = rows(csv)
        assertEquals("", r[h.indexOf("burden_score")])
        assertEquals("", r[h.indexOf("condition_score")])
        assertEquals("", r[h.indexOf("E01.lux")])
        assertEquals("", r[h.indexOf("E01.lux.quality")])
        assertEquals("stale_sensor_sample", r[h.indexOf("E01.lux.reason")])
        assertEquals("0.0001", r[h.indexOf("S06.change")])
        assertEquals("0.6", r[h.indexOf("S06.change.quality")])
    }

    @Test fun legacyReportAnswerIsKeptAndTextIsQuoted() {
        val legacy = report(1, nrs = 6).copy(confidence = "낮음, \"부분\"")
        val csv = ResearchDataExporter.csv(listOf(legacy), emptyList(), ZoneOffset.UTC)
        assertTrue(csv.contains("\"낮음, \"\"부분\"\"\""))
        val header = csv.lineSequence().first().split(",")
        val line = csv.lines()[1]
        assertTrue(line.startsWith("r-1,s-1,"))
        assertEquals(24 + 15, header.size)
    }
}
