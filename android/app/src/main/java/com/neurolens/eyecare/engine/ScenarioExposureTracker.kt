package com.neurolens.eyecare.engine

/** Session-only duration for the dark + bright + changing-screen interaction.
 * The thresholds are research hypotheses, not validated exposure limits. */
class ScenarioExposureTracker {
    private var lastAtMs: Long? = null
    private var lastDay: Long? = null
    private var precedingQualified = false
    private var exposureMs = 0L

    fun observe(atMs: Long, localDay: Long, screenOn: Boolean, signals: Map<String, Signal>): Signal {
        if (lastDay != null && lastDay != localDay) reset()
        lastDay = localDay
        if (!screenOn) {
            reset()
            lastDay = localDay
            return Signal.missing("screen_off_or_rest")
        }
        val lux = signals["E01.lux"]
        val emission = signals["S16.emissionProxy"]
        val change = signals["S06.change"]
        val required = listOf(lux, emission, change)
        if (required.any { it?.value == null || it.quality < .5 }) {
            reset()
            lastDay = localDay
            return Signal.missing(required.firstOrNull { it?.value == null || it.quality < .5 }
                ?.reason ?: "dark_stimulus_input_unavailable")
        }
        val qualified = lux!!.value!! < 50.0 && emission!!.value!! >= .45 && change!!.value!! >= .05
        val previous = lastAtMs
        val delta = if (previous != null) atMs - previous else 0L
        if (!qualified || delta !in 1..90_000) exposureMs = 0L
        else if (precedingQualified) exposureMs = (exposureMs + delta).coerceAtMost(600_000L)
        lastAtMs = atMs
        precedingQualified = qualified
        return Signal.observed(exposureMs / 60_000.0,
            required.minOf { it!!.quality }, estimated = true)
    }

    fun reset() {
        lastAtMs = null
        precedingQualified = false
        exposureMs = 0L
    }
}
