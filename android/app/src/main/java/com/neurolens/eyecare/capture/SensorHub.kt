package com.neurolens.eyecare.capture

import android.Manifest
import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.hardware.display.DisplayManager
import com.neurolens.eyecare.data.UsageReader
import com.neurolens.eyecare.engine.Signal
import com.neurolens.eyecare.engine.ObservationFreshness
import kotlin.math.*

/** Session-scoped, non-image sensors. Unavailable OS APIs and permissions remain explicit. */
class SensorHub(private val context: Context, private val handler: Handler) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var lux: Float? = null
    private var luxAt = -1L
    private var luxMin: Float? = null
    private var luxMax: Float? = null
    private var proximity: Float? = null
    private var proximityAt = -1L
    private var gravity: FloatArray? = null
    private var gravityAt = -1L
    private var accelerationSum = 0.0
    private var accelerationCount = 0
    private var accelerationAt = -1L
    private var steps = 0
    private val stepTimes = ArrayDeque<Long>()
    private var rotationChanges = 0
    private var lastRotation: Int? = null
    private val registered = mutableMapOf<Int, Sensor>()
    private val unreliable = mutableSetOf<Int>()
    private var registrationStartedNs = -1L

    private fun clear(type: Int) {
        when (type) {
            Sensor.TYPE_LIGHT -> { lux = null; luxAt = -1; luxMin = null; luxMax = null }
            Sensor.TYPE_PROXIMITY -> { proximity = null; proximityAt = -1 }
            Sensor.TYPE_GRAVITY -> { gravity = null; gravityAt = -1 }
            Sensor.TYPE_ACCELEROMETER -> { accelerationSum = 0.0; accelerationCount = 0; accelerationAt = -1 }
            Sensor.TYPE_STEP_DETECTOR -> { steps = 0; stepTimes.clear() }
        }
    }

    private fun clearAll() = listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_STEP_DETECTOR).forEach(::clear)

    fun start() {
        if (registrationStartedNs >= 0) return
        clearAll()
        unreliable.clear()
        registrationStartedNs = SystemClock.elapsedRealtimeNanos()
        for (type in listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_STEP_DETECTOR)) {
            if (type == Sensor.TYPE_STEP_DETECTOR && context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) continue
            manager.getDefaultSensor(type)?.let { sensor ->
                if (manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler))
                    registered[type] = sensor
            }
        }
    }
    fun stop() {
        manager.unregisterListener(this)
        registered.clear(); unreliable.clear(); registrationStartedNs = -1
        clearAll()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        if (type !in registered || type in unreliable ||
            !ObservationFreshness.belongsToRegistration(event.timestamp,
                registrationStartedNs, SystemClock.elapsedRealtimeNanos())) return
        val sampledAt = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val value = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f }
                if (value == null) clear(type) else {
                    lux = value; luxAt = sampledAt
                    luxMin = minOf(luxMin ?: value, value); luxMax = maxOf(luxMax ?: value, value)
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val value = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f }
                if (value == null) clear(type) else { proximity = value; proximityAt = sampledAt }
            }
            Sensor.TYPE_GRAVITY -> {
                if (event.values.size < 3 || event.values.take(3).any { !it.isFinite() }) clear(type)
                else { gravity = event.values.copyOf(); gravityAt = sampledAt }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3 || event.values.take(3).any { !it.isFinite() }) clear(type)
                else {
                    val magnitude = sqrt(event.values.take(3).sumOf { (it * it).toDouble() })
                    accelerationSum += (magnitude - 9.81).pow(2); accelerationCount++
                    accelerationAt = sampledAt
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> { steps++; stepTimes.addLast(sampledAt) }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val type = sensor?.type ?: return
        if (type !in registered) return
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            unreliable += type
            clear(type)
        } else unreliable -= type // Still missing until another valid event arrives.
    }

    fun snapshot(nowMs: Long = SystemClock.elapsedRealtime()): Map<String, Signal> {
        val out = linkedMapOf<String, Signal>()
        fun put(id: String, value: Double?, reason: String = "unavailable", quality: Double = 1.0,
            estimated: Boolean = false) {
            out[id] = if (value == null) Signal.missing(reason) else Signal.observed(value, quality, estimated)
        }
        fun active(type: Int, sampledAt: Long) = ObservationFreshness.sensorCurrent(
            nowMs, sampledAt, type in registered,
            registered[type]?.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE,
            type !in unreliable)
        val recentLux = active(Sensor.TYPE_LIGHT, luxAt)
        put("E01.lux", lux?.toDouble()?.takeIf { recentLux },
            if (Sensor.TYPE_LIGHT !in registered) "sensor_absent_or_inactive"
            else if (Sensor.TYPE_LIGHT in unreliable) "sensor_unreliable" else "light_sample_unavailable")
        put("E01.luxMin", luxMin?.toDouble()?.takeIf { recentLux }, "stale_light_sample")
        put("E01.luxMax", luxMax?.toDouble()?.takeIf { recentLux }, "stale_light_sample")
        // Sensor metadata for cross-checking implausibly low lux against camera EV (C04).
        put("E01.maxRangeLux", registered[Sensor.TYPE_LIGHT]?.maximumRange?.toDouble(), "sensor_absent_or_inactive")
        put("E01.sensorCount", manager.getSensorList(Sensor.TYPE_LIGHT).size.toDouble())
        put("E03.proximity", proximity?.toDouble()?.takeIf { active(Sensor.TYPE_PROXIMITY, proximityAt) },
            if (Sensor.TYPE_PROXIMITY in registered) "sensor_no_sample" else "sensor_absent")
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        put("E04.hour", hour.toDouble()); put("E04.night", if (hour >= 22 || hour < 6) 1.0 else 0.0)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        put("E05.batteryPct", if (level >= 0 && scale > 0) level * 100.0 / scale else null)
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        put("E05.batteryC", if (temperature >= 0) temperature / 10.0 else null)
        put("E05.charging", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            if (it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL) 1.0 else 0.0
        })
        put("E05.thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus.toDouble())
        val audio = context.getSystemService(AudioManager::class.java)
        val playback = runCatching { audio.activePlaybackConfigurations }.getOrNull()
        put("E06.activePlayback", playback?.size?.toDouble(), "playback_os_restricted")
        put("E06.mediaVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
        val g = gravity.takeIf { active(Sensor.TYPE_GRAVITY, gravityAt) }
        put("M01.tiltDeg", g?.let { Math.toDegrees(atan2(abs(it[2].toDouble()), hypot(it[0].toDouble(), it[1].toDouble()))) },
            if (Sensor.TYPE_GRAVITY in registered) "sensor_no_sample" else "sensor_absent", estimated = true)
        put("M02.shake", if (accelerationCount > 0 && active(Sensor.TYPE_ACCELEROMETER, accelerationAt))
            sqrt(accelerationSum / accelerationCount) else null,
            if (Sensor.TYPE_ACCELEROMETER in registered) "sensor_no_sample" else "sensor_absent", .7, true)
        accelerationSum = 0.0; accelerationCount = 0
        val nowElapsed = SystemClock.elapsedRealtime()
        while (stepTimes.isNotEmpty() && nowElapsed - stepTimes.first() > 60_000) stepTimes.removeFirst()
        put("M03.walking", if (Sensor.TYPE_STEP_DETECTOR in registered) if (stepTimes.size >= 4) 1.0 else 0.0 else null,
            if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                "sensor_absent" else "permission_missing", .6, true)
        put("M03.steps", if (Sensor.TYPE_STEP_DETECTOR in registered) steps.toDouble() else null, "permission_or_sensor_unavailable")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation
        if (rotation != null && lastRotation != null && rotation != lastRotation) rotationChanges++
        lastRotation = rotation
        put("M05.rotations", rotationChanges.toDouble())
        put("S14.rotation", rotation?.toDouble())
        put("S14.refreshHz", display?.refreshRate?.toDouble())
        put("S14.widthPx", context.resources.displayMetrics.widthPixels.toDouble())
        put("S14.heightPx", context.resources.displayMetrics.heightPixels.toDouble())
        val brightness = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        put("S15.brightness", brightness?.div(255.0), "setting_unavailable")
        put("S15.autoBrightness", runCatching { Settings.System.getInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE).toDouble() }.getOrNull(), "setting_unavailable")
        put("S15.darkMode", if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) 1.0 else 0.0)
        put("S15.fontScale", context.resources.configuration.fontScale.toDouble())
        put("S15.densityDpi", context.resources.displayMetrics.densityDpi.toDouble())
        out["S15.nightLight"] = Signal.missing("public_api_unavailable")
        out["S15.extraDim"] = Signal.missing("public_api_unavailable")
        val usage = UsageReader.today(context)
        put("U02.todayMin", if (usage.granted && usage.hasEvents) usage.millis / 60_000.0 else null,
            if (usage.granted) "no_usage_events" else "permission_missing")
        val recent = recentUsage()
        put("U03.switchesPerMin", recent?.first?.div(5.0), "usage_access_unavailable")
        put("U06.unlocks", recent?.second?.toDouble(), "usage_access_unavailable")
        put("U03.foregroundCategory", null, "package_category_not_reliably_available")
        out["U04.mediaSession"] = Signal.missing("notification_access_not_granted")
        out["U05.notificationInterruptions"] = Signal.missing("notification_access_not_granted")
        out["U08.interactions"] = Signal.missing("accessibility_opt_in_not_enabled")
        out["M04.transport"] = Signal.missing("activity_recognition_api_unavailable")
        val network = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull()
        put("E07.wifi", network?.let { if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1.0 else 0.0 }, "network_unavailable")
        return out
    }

    private fun recentUsage(): Pair<Int, Int>? {
        if (!UsageReader.allowed(context)) return null
        return runCatching {
            val now = System.currentTimeMillis()
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 300_000, now)
            val item = UsageEvents.Event(); var switches = 0; var unlocks = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(item)
                if (item.eventType == UsageEvents.Event.ACTIVITY_RESUMED) switches++
                if (item.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) unlocks++
            }
            switches to unlocks
        }.getOrNull()
    }
}
