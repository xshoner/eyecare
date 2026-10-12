package com.neurolens.eyecare.data

import android.content.Context
import com.neurolens.eyecare.engine.StrainState
import org.json.JSONObject

/** Local-only daily strain ledger, so a restarted care session continues today's condition. */
class StrainStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strain_ledger_v1", Context.MODE_PRIVATE)

    fun load(day: Long): StrainState {
        val raw = prefs.getString("state", null) ?: return StrainState(day)
        return runCatching {
            val j = JSONObject(raw)
            if (j.getLong("day") != day) return StrainState(day)
            val factors = j.optJSONObject("byFactor")
            StrainState(day, j.getDouble("deduction"),
                factors?.keys()?.asSequence()?.associateWith { factors.getDouble(it) } ?: emptyMap(),
                j.optLong("viewingMs"), j.optLong("restMs"), j.optLong("darkMs"), j.optLong("nearMs"),
                lastWallMs = if (j.has("lastWallMs")) j.getLong("lastWallMs") else null)
        }.getOrDefault(StrainState(day))
    }

    fun save(state: StrainState) {
        prefs.edit().putString("state", JSONObject().apply {
            put("day", state.localDay); put("deduction", state.deduction)
            put("byFactor", JSONObject(state.byFactor))
            put("viewingMs", state.viewingMs); put("restMs", state.restMs)
            put("darkMs", state.darkMs); put("nearMs", state.nearMs)
            state.lastWallMs?.let { put("lastWallMs", it) }
        }.toString()).apply()
    }
}
