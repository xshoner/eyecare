package com.neurolens.eyecare.engine

import kotlin.math.roundToInt

data class StrainState(
    val localDay: Long,
    val deduction: Double = 0.0,                       // 0..100 points lost today
    val byFactor: Map<String, Double> = emptyMap(),    // points currently attributed to each factor
    val viewingMs: Long = 0L,                          // observed viewing time today
    val restMs: Long = 0L,                             // observed rest time today
    val darkMs: Long = 0L, val nearMs: Long = 0L,      // current continuous dark / near viewing
    val notDarkMs: Long = 0L, val notNearMs: Long = 0L,
    val restEpisodeMs: Long = 0L,                      // length of the current rest episode
    val lastWallMs: Long? = null,                      // wall clock of the last observation (gap recovery)
) {
    val condition: Int get() = (100 - deduction).roundToInt().coerceIn(0, 100)
}

/**
 * Integrates [StrainModel] deductions over observed viewing time and restores points during observed
 * rest (screen off while care runs, or screen-off time read from Android usage events between sessions).
 * Time that is neither observed viewing nor known rest changes nothing — it is never assumed to be rest.
 */
class StrainLedger(initial: StrainState) {
    private var value = initial.takeIf { it.deduction.isFinite() && it.deduction in 0.0..100.0 } ?: StrainState(initial.localDay)
    private var lastAt: Long? = null
    var lastAssessment: StrainModel.Assessment? = null; private set

    fun state() = value
    val darkMinutes get() = value.darkMs / 60_000.0
    val nearMinutes get() = value.nearMs / 60_000.0

    private fun dayCheck(localDay: Long) {
        if (localDay != value.localDay) { value = StrainState(localDay); lastAt = null }
    }

    /**
     * Call about once a second. [viewing] = screen on with a fresh, valid capture; [resting] = screen
     * off while care runs. Gaps over 5 s (stalled worker) and other states are skipped.
     */
    fun observe(nowMs: Long, wallMs: Long, localDay: Long, viewing: Boolean, resting: Boolean,
        assessment: StrainModel.Assessment?): StrainState {
        dayCheck(localDay)
        val dt = lastAt?.let { nowMs - it } ?: 0L
        lastAt = nowMs
        if (dt !in 1..5_000) { value = value.copy(lastWallMs = wallMs); return value }
        value = when {
            viewing && assessment != null -> deduct(dt, assessment)
            resting -> recover(dt)
            else -> value
        }.copy(lastWallMs = wallMs)
        if (viewing && assessment != null) lastAssessment = assessment
        return value
    }

    private fun deduct(dt: Long, a: StrainModel.Assessment): StrainState {
        val minutes = dt / 60_000.0
        val room = 100.0 - value.deduction
        val raw = a.totalRate * minutes
        val applied = minOf(raw, room)
        val share = if (raw > 0) applied / raw else 0.0
        val byFactor = value.byFactor.toMutableMap()
        a.rates.forEach { (f, r) -> if (r > 0) byFactor[f.id] = (byFactor[f.id] ?: 0.0) + r * minutes * share }
        // Continuous dark/near counters tolerate up to 2 minutes of interruption before resetting.
        val darkMs = if (a.dark) value.darkMs + dt else if (value.notDarkMs + dt > 120_000) 0L else value.darkMs
        val notDarkMs = if (a.dark) 0L else value.notDarkMs + dt
        val nearMs = if (a.near) value.nearMs + dt else if (value.notNearMs + dt > 120_000) 0L else value.nearMs
        val notNearMs = if (a.near) 0L else value.notNearMs + dt
        return value.copy(deduction = value.deduction + applied, byFactor = byFactor,
            viewingMs = value.viewingMs + dt, darkMs = darkMs, notDarkMs = notDarkMs,
            nearMs = nearMs, notNearMs = notNearMs, restEpisodeMs = 0L)
    }

    private fun recover(dt: Long): StrainState {
        val points = minOf(StrainModel.recoveryPoints(value.restEpisodeMs, dt), value.deduction)
        val scale = if (value.deduction > 0) (value.deduction - points) / value.deduction else 1.0
        val episode = value.restEpisodeMs + dt
        // A rest of 5 minutes or more ends the continuous dark / near streaks.
        val streakOver = episode >= 5 * 60_000L
        return value.copy(deduction = value.deduction - points, byFactor = value.byFactor.mapValues { it.value * scale },
            restMs = value.restMs + dt, restEpisodeMs = episode,
            darkMs = if (streakOver) 0L else value.darkMs, nearMs = if (streakOver) 0L else value.nearMs)
    }

    /** Applies screen-off time that Android recorded while care was not running. */
    fun recoverGap(restMs: Long, wallMs: Long, localDay: Long): StrainState {
        dayCheck(localDay)
        if (restMs > 0) value = recover(restMs).copy(restEpisodeMs = 0L)
        value = value.copy(lastWallMs = wallMs)
        return value
    }

    fun condition() = value.condition
}
