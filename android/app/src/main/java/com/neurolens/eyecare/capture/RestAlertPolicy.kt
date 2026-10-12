package com.neurolens.eyecare.capture

/** Uses elapsed realtime, so clock changes cannot bypass duration or cooldown. */
class RestAlertPolicy {
    private var highSince: Long? = null
    private var suppressedUntil = 0L
    private var visible = false

    fun evaluate(now: Long, score: Int?, valid: Boolean, active: Boolean,
        durationMinutes: Int, cooldownMinutes: Int): Boolean {
        if (!active || !valid || score == null || score < 45) {
            highSince = null
            if (!active || !valid || score == null || score < 45) visible = false
            return false
        }
        if (score < 50) return false // 45–49 is a hysteresis band; keep the prior start.
        if (highSince == null) highSince = now
        if (visible || now < suppressedUntil || now - highSince!! < durationMinutes * 60_000L) return false
        visible = true
        suppressedUntil = now + cooldownMinutes * 60_000L
        return true
    }

    fun dismiss(now: Long, cooldownMinutes: Int) {
        visible = false; highSince = now; suppressedUntil = now + cooldownMinutes * 60_000L
    }
    fun snooze(now: Long, minutes: Int = 10) {
        visible = false; highSince = now; suppressedUntil = now + minutes * 60_000L
    }
    fun clear() { visible = false; highSince = null }
    val showing get() = visible
}
