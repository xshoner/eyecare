package com.neurolens.eyecare.engine

/** Elapsed-realtime timestamps only. Old measurements must not be relabelled as current. */
object ObservationFreshness {
    const val MAX_AGE_MS = 10_000L
    fun current(nowMs: Long, sampledAtMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        sampledAtMs >= 0 && nowMs >= sampledAtMs && nowMs - sampledAtMs <= maxAgeMs

    /** SensorEvent timestamps share the elapsed-realtime clock, unlike callback arrival time. */
    fun belongsToRegistration(sampledAtNs: Long, startedAtNs: Long, nowNs: Long): Boolean =
        startedAtNs > 0 && sampledAtNs >= startedAtNs && sampledAtNs <= nowNs

    /** An active on-change sensor's last event remains current until unregister or accuracy loss. */
    fun sensorCurrent(nowMs: Long, sampledAtMs: Long, registered: Boolean,
        onChange: Boolean, reliable: Boolean = true): Boolean = registered && reliable &&
        sampledAtMs >= 0 && nowMs >= sampledAtMs &&
        (onChange || current(nowMs, sampledAtMs))
}
