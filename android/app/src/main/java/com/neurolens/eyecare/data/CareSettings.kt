package com.neurolens.eyecare.data

import android.content.Context
import com.neurolens.eyecare.engine.ReportScheduler

data class CareSettings(val intervalMin: Int = 30, val basis: ReportScheduler.Basis = ReportScheduler.Basis.ACTIVE_SCREEN,
    val overlayRestEnabled: Boolean = false, val restAlertMinutes: Int = 2,
    val restCooldownMinutes: Int = 20, val personalCalibrationEnabled: Boolean = true)

class CareSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("care_settings", Context.MODE_PRIVATE)

    fun read(): CareSettings {
        val minutes = prefs.getInt("interval_min", 30).takeIf { it in 10..120 && it % 10 == 0 } ?: 30
        val basis = runCatching { ReportScheduler.Basis.valueOf(prefs.getString("basis", "ACTIVE_SCREEN") ?: "ACTIVE_SCREEN") }
            .getOrDefault(ReportScheduler.Basis.ACTIVE_SCREEN)
        return CareSettings(minutes, basis,
            prefs.getBoolean("overlay_rest_enabled", false),
            prefs.getInt("rest_alert_minutes", 2).takeIf { it in 1..5 } ?: 2,
            prefs.getInt("rest_cooldown_minutes", 20).takeIf { it in 10..60 } ?: 20,
            prefs.getBoolean("personal_calibration_enabled", true))
    }

    fun save(value: CareSettings) {
        require(value.intervalMin in 10..120 && value.intervalMin % 10 == 0)
        require(value.restAlertMinutes in 1..5 && value.restCooldownMinutes in 10..60)
        prefs.edit().putInt("interval_min", value.intervalMin).putString("basis", value.basis.name)
            .putBoolean("overlay_rest_enabled", value.overlayRestEnabled)
            .putInt("rest_alert_minutes", value.restAlertMinutes)
            .putInt("rest_cooldown_minutes", value.restCooldownMinutes)
            .putBoolean("personal_calibration_enabled", value.personalCalibrationEnabled).apply()
    }
}
