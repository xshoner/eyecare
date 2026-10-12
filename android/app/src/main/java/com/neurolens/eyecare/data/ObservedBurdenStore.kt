package com.neurolens.eyecare.data

import android.content.Context
import com.neurolens.eyecare.engine.ObservedBurdenState

/** Local display history. This does not alter research tables or the protected Auth session. */
class ObservedBurdenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("observed_burden_v1", Context.MODE_PRIVATE)
    fun load(day: Long): ObservedBurdenState {
        if (prefs.getLong("day", Long.MIN_VALUE) != day) return ObservedBurdenState(day)
        return ObservedBurdenState(day,
            prefs.getString("burden", null)?.toDoubleOrNull() ?: 0.0,
            prefs.getLong("observed_ms", 0L),
            prefs.getString("interaction", null)?.toDoubleOrNull() ?: 0.0,
            prefs.getLong("interaction_ms", 0L))
    }
    fun save(state: ObservedBurdenState) {
        prefs.edit().putLong("day", state.localDay)
            .putString("burden", state.burdenMinutes.toString())
            .putLong("observed_ms", state.observedMs)
            .putString("interaction", state.interactionMinutes.toString())
            .putLong("interaction_ms", state.interactionObservedMs).apply()
    }
}
