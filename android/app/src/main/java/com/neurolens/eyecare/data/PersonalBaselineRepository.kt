package com.neurolens.eyecare.data

import android.content.Context
import com.neurolens.eyecare.engine.BaselineMetric
import com.neurolens.eyecare.engine.PersonalBaseline
import com.neurolens.eyecare.engine.Signal

/** Version 1 counted missing values in every metric's denominator. Its averages cannot be
 * repaired reliably, so version 2 starts a fresh local baseline on upgrade. */
class PersonalBaselineRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("personal_baseline_mvp", Context.MODE_PRIVATE)
    fun read(): PersonalBaseline {
        if (prefs.getInt("schema", 0) != 2) return PersonalBaseline()
        fun metric(key: String) = BaselineMetric(
            if (prefs.contains("$key.mean")) prefs.getFloat("$key.mean", 0f).toDouble() else null,
            prefs.getInt("$key.count", 0), prefs.getFloat("$key.quality", 0f).toDouble())
        return PersonalBaseline(prefs.getInt("reports", 0), metric("open"), metric("blink"),
            metric("usage"), metric("switches"), metric("unlocks"), metric("microBreaks"), metric("score"))
    }
    fun add(open: Signal?, blink: Signal?, usage: Signal?, switches: Signal?, unlocks: Signal?,
        microBreaks: Signal?, score: Signal?) {
        val inputs = listOf(open, blink, usage, switches, unlocks, microBreaks, score)
        if (inputs.none { it?.value?.isFinite() == true && it.quality > 0 }) return
        val updated = read().updated(open, blink, usage, switches, unlocks, microBreaks, score)
        val editor = prefs.edit()
        if (prefs.getInt("schema", 0) != 2) editor.clear()
        editor.putInt("schema", 2).putInt("reports", updated.samples)
        fun put(key: String, metric: BaselineMetric) {
            metric.mean?.let { editor.putFloat("$key.mean", it.toFloat()) }
            editor.putInt("$key.count", metric.count).putFloat("$key.quality", metric.qualitySum.toFloat())
        }
        put("open", updated.open); put("blink", updated.blink); put("usage", updated.usage)
        put("switches", updated.switches); put("unlocks", updated.unlocks)
        put("microBreaks", updated.microBreaks); put("score", updated.score)
        editor.apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
