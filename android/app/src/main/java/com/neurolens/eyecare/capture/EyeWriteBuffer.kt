package com.neurolens.eyecare.capture

/** Bounds uncommitted local observations to four frames or 250 ms. */
class EyeWriteBuffer {
    private val pending = ArrayList<EyeObservation>(4)
    private var firstAt = 0L
    fun offer(value: EyeObservation): List<EyeObservation>? {
        if (pending.isEmpty()) firstAt = value.capturedAtMs
        pending.add(value)
        return if (pending.size >= 4 || value.capturedAtMs - firstAt >= 250) drain() else null
    }
    fun due(now: Long): List<EyeObservation>? =
        if (pending.isNotEmpty() && now - firstAt >= 250) drain() else null
    fun drain(): List<EyeObservation> {
        if (pending.isEmpty()) return emptyList()
        val batch = pending.toList(); pending.clear(); firstAt = 0L
        return batch
    }
}
