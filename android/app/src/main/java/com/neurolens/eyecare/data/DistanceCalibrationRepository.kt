package com.neurolens.eyecare.data

import android.content.Context
import com.neurolens.eyecare.engine.DistanceEstimate

/** Only a scale and camera-geometry identifier are stored, never an image or landmark trace. */
class DistanceCalibrationRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("distance_calibration_v1", Context.MODE_PRIVATE)
    fun apply(raw: DistanceEstimate, geometryId: String): DistanceEstimate {
        val scale = prefs.getFloat("cm_px", 0f).toDouble()
        if (prefs.getString("geometry_id", null) != geometryId || scale <= 0 ||
            raw.diameterPx == null || raw.reason !in setOf(null, "camera_geometry_or_calibration_needed", "outside_estimation_range"))
            return raw
        val cm = scale / raw.diameterPx
        if (cm !in 15.0..80.0 || !cm.isFinite()) return raw.copy(cm = null,
            reason = "outside_estimation_range")
        return raw.copy(cm = cm, quality = maxOf(raw.quality, .55).coerceAtMost(.85),
            source = "user_reference", reason = null, calibrated = true)
    }
    fun calibrate(referenceCm: Double, diameterPx: Double, geometryId: String) {
        require(referenceCm in 20.0..60.0 && diameterPx in 3.0..100.0 && geometryId != "unavailable")
        prefs.edit().putFloat("cm_px", (referenceCm * diameterPx).toFloat())
            .putString("geometry_id", geometryId).apply()
    }
}
