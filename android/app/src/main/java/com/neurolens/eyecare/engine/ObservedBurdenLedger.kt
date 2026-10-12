package com.neurolens.eyecare.engine

import kotlin.math.exp
import kotlin.math.roundToInt

data class ObservedBurdenState(val localDay: Long, val burdenMinutes: Double = 0.0,
    val observedMs: Long = 0L, val interactionMinutes: Double = 0.0,
    val interactionObservedMs: Long = 0L)

/**
 * Integrates only screen-active intervals actually sampled by the service. A sensor gap,
 * thermal pause, screen off, or service restart never removes already observed burden.
 * The calendar-day window is a display period, not a physiological recovery model.
 */
class ObservedBurdenLedger(initial: ObservedBurdenState) {
    private var value = initial.takeIf { it.burdenMinutes.isFinite() && it.burdenMinutes >= 0 &&
        it.interactionMinutes.isFinite() && it.interactionMinutes >= 0 && it.observedMs >= 0 &&
        it.interactionObservedMs >= 0 } ?: ObservedBurdenState(initial.localDay)
    private var lastAtMs: Long? = null

    fun state(): ObservedBurdenState = value
    fun observe(nowMs: Long, localDay: Long, validScreenOnContext: Boolean,
        rate: Double?, interactionRate: Double = 0.0): ObservedBurdenState {
        if (localDay != value.localDay) {
            value = ObservedBurdenState(localDay)
            lastAtMs = nowMs
            return value
        }
        val dt = lastAtMs?.let { nowMs - it } ?: 0L
        lastAtMs = nowMs
        // A stalled worker or killed service must not manufacture unobserved exposure.
        if (!validScreenOnContext || dt !in 1..5_000 || rate == null || !rate.isFinite() ||
            rate !in 0.0..1.08) return value
        val interaction = interactionRate.takeIf { it.isFinite() && it in 0.0..0.08 } ?: 0.0
        value = value.copy(burdenMinutes = value.burdenMinutes + rate * dt / 60_000.0,
            observedMs = value.observedMs + dt,
            interactionMinutes = value.interactionMinutes + interaction * dt / 60_000.0,
            interactionObservedMs = value.interactionObservedMs + if (interaction > 0) dt else 0L)
        return value
    }

    /** 40 burden-minutes is a product display scale, not a medical limit or recovery half-life. */
    fun score(): Int = (100 * (1 - exp(-value.burdenMinutes / 40.0))).roundToInt().coerceIn(0, 100)
}
