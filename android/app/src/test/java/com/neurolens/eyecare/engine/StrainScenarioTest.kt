package com.neurolens.eyecare.engine

import com.neurolens.eyecare.capture.StrainAlertPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulates continuous viewing per scenario; prints the minutes to each band and the first alerts. */
class StrainScenarioTest {
    private data class Scenario(val name: String, val inputs: (Double) -> StrainModel.Inputs)

    private val scenarios = listOf(
        Scenario("밝은 곳·40cm·읽기") { m -> StrainModel.Inputs(lux = 300.0, distanceCm = 40.0, continuousMin = m, screenChange = .02, blinksPerMin = 12.0) },
        Scenario("밝은 곳·30cm·영상") { m -> StrainModel.Inputs(lux = 300.0, distanceCm = 30.0, continuousMin = m, screenChange = .06, blinksPerMin = 10.0) },
        Scenario("어두운 곳(30lx)·40cm·읽기") { m -> StrainModel.Inputs(lux = 30.0, distanceCm = 40.0, continuousMin = m, screenChange = .02, brightness = .5) },
        Scenario("어두운 곳(3lx)·35cm·영상") { m -> StrainModel.Inputs(lux = 3.0, distanceCm = 35.0, continuousMin = m, screenChange = .07, brightness = .7) },
        Scenario("어두운 곳(3lx)·25cm·게임") { m -> StrainModel.Inputs(lux = 3.0, distanceCm = 25.0, continuousMin = m, screenChange = .14, flashPerMin = 5.0, brightness = .9) },
        Scenario("어두운 곳(3lx)·22cm·번쩍이는 영상") { m -> StrainModel.Inputs(lux = 3.0, distanceCm = 22.0, continuousMin = m, screenChange = .16, flashPerMin = 9.0, largeRatio = .4, brightness = 1.0, blinksPerMin = 6.0) },
    )

    private fun run(s: Scenario): Pair<Map<Int, Int?>, List<String>> {
        val ledger = StrainLedger(StrainState(1))
        val policy = StrainAlertPolicy()
        val reached = linkedMapOf<Int, Int?>(79 to null, 59 to null, 39 to null, 19 to null)
        val alerts = mutableListOf<String>()
        var t = 0L
        ledger.observe(0, 0, 1, true, false, null)
        for (second in 1..240 * 60) {
            t += 1000
            val minute = second / 60.0
            val a = StrainModel.assess(s.inputs(minute).copy(darkMinutes = ledger.darkMinutes, nearMinutes = ledger.nearMinutes))
            ledger.observe(t, t, 1, true, false, a)
            reached.keys.forEach { k -> if (reached[k] == null && ledger.condition() <= k) reached[k] = second / 60 }
            policy.evaluate(StrainAlertPolicy.Tick(t, ledger.condition(), a, minute, s.inputs(minute).distanceCm, ledger.darkMinutes, 20, 2))
                ?.let { if (alerts.size < 4) alerts += "${second / 60}분 ${it.kind}" }
        }
        return reached to alerts
    }

    @Test fun printScenarioTable() {
        val results = scenarios.map { it.name to run(it) }
        println("시나리오 | 양호(79) | 보통(59) | 주의(39) | 위험(19) | 첫 알림")
        results.forEach { (name, r) ->
            println("$name | ${r.first.values.joinToString(" | ") { it?.let { m -> "${m}분" } ?: "4시간 내 없음" }} | ${r.second.joinToString(", ")}")
        }
        val caution = results.map { it.second.first[39] ?: Int.MAX_VALUE }
        assertTrue("riskier scenarios must reach 주의 no later", caution.first() >= caution.last())
    }
}
