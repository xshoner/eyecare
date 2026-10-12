package com.neurolens.eyecare.engine

/** Monotonic clock scheduler. Screen-off time is paused and a long break resets the cycle. */
class ReportScheduler(
    private var intervalMin: Int = 30,
    private var basis: Basis = Basis.ACTIVE_SCREEN,
    private val restResetMin: Int = 5,
    startMs: Long,
    screenOn: Boolean = true
) {
    enum class Basis { ACTIVE_SCREEN, ELAPSED }

    private var lastAt = startMs
    private var screenOn = screenOn
    private var screenOffAt: Long? = if (screenOn) null else startMs
    private var activeMs = 0L
    private var continuousMs = 0L
    private var elapsedCycleStart = startMs
    private var lastReportAt = startMs
    var restCount = 0
        private set
    var microRestCount = 0
        private set

    init { require(intervalMin in 10..120 && intervalMin % 10 == 0) }

    fun update(nowMs: Long, isScreenOn: Boolean = screenOn): Boolean {
        require(nowMs >= lastAt)
        if (screenOn) {
            activeMs += nowMs - lastAt
            continuousMs += nowMs - lastAt
        }
        if (screenOn && !isScreenOn) screenOffAt = nowMs
        if (!screenOn && isScreenOn) {
            val offMs = nowMs - (screenOffAt ?: nowMs)
            if (offMs in 1L until 120_000L) microRestCount++
            if (offMs >= restResetMin * 60_000L) {
                activeMs = 0
                continuousMs = 0
                elapsedCycleStart = nowMs
                restCount++
            }
            screenOffAt = null
        }
        screenOn = isScreenOn
        lastAt = nowMs
        return remainingMs(nowMs) <= 0
    }

    fun remainingMs(nowMs: Long = lastAt): Long {
        val accumulated = when (basis) {
            Basis.ACTIVE_SCREEN -> activeMs
            Basis.ELAPSED -> nowMs - elapsedCycleStart
        }
        return (intervalMin * 60_000L - accumulated).coerceAtLeast(0)
    }

    fun intervalMinutes() = intervalMin
    fun activeMillis() = activeMs
    fun continuousMillis() = continuousMs
    fun lastReportAt() = lastReportAt

    fun settings(minutes: Int, newBasis: Basis, nowMs: Long): Boolean {
        require(minutes in 10..120 && minutes % 10 == 0)
        update(nowMs)
        intervalMin = minutes
        basis = newBasis
        return remainingMs(nowMs) == 0L
    }

    fun reported(nowMs: Long) {
        update(nowMs)
        lastReportAt = nowMs
        activeMs = 0
        elapsedCycleStart = nowMs
    }
}
