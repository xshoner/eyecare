package com.neurolens.eyecare.engine

/** Engineering hypotheses for relative burden, not clinical exposure limits. */
object ContextRisk {
    fun darkBright(lux: Double?, brightnessSetting: Double?): Double? {
        if (lux == null || brightnessSetting == null || !lux.isFinite() ||
            !brightnessSetting.isFinite() || lux < 0 || brightnessSetting !in 0.0..1.0) return null
        val darkness = ((50.0 - lux) / 50.0).coerceIn(0.0, 1.0)
        // Brightness setting is a relative OS control, not display luminance in cd/m².
        return darkness * (50.0 + 50.0 * brightnessSetting)
    }
    fun near(cm: Double): Double = ((40.0 - cm) / 20.0 * 100).coerceIn(0.0, 100.0)
    fun band(score: Int?): String = when (score) {
        null -> "unavailable"
        in 0..24 -> "good"
        in 25..49 -> "ordinary"
        else -> "caution"
    }
}
