package com.neurolens.eyecare.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

data class UsageSummary(val granted: Boolean, val millis: Long = 0, val hasEvents: Boolean = false)

object UsageReader {
    fun allowed(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    /**
     * Screen-off milliseconds Android recorded between two wall-clock times, or null without
     * Usage Access or usable screen events. Used only to credit rest between care sessions.
     */
    fun screenOffMillis(context: Context, fromMs: Long, toMs: Long): Long? {
        if (!allowed(context) || toMs <= fromMs) return null
        return runCatching {
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(fromMs - 86_400_000, toMs)
            val event = UsageEvents.Event()
            var interactive: Boolean? = null
            var cursor = fromMs
            var off = 0L
            var seen = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type != UsageEvents.Event.SCREEN_INTERACTIVE && type != UsageEvents.Event.SCREEN_NON_INTERACTIVE) continue
                val time = event.timeStamp.coerceIn(fromMs, toMs)
                if (event.timeStamp > fromMs) {
                    if (interactive == false) off += time - cursor
                    cursor = time; seen = true
                }
                interactive = type == UsageEvents.Event.SCREEN_INTERACTIVE
            }
            if (interactive == false) off += toMs - cursor
            off.takeIf { seen || interactive == false }
        }.getOrNull()
    }

    fun today(context: Context): UsageSummary {
        if (!allowed(context)) return UsageSummary(false)
        val now = System.currentTimeMillis()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return runCatching {
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start - 86400000, now)
            val event = UsageEvents.Event()
            var interactive = false
            var unlocked = true
            val active = mutableSetOf<String>()
            var last = start
            var total = 0L
            var observed = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val time = event.timeStamp.coerceAtMost(now)
                if (time >= start) {
                    if (interactive && unlocked && active.isNotEmpty()) total += (time - last).coerceAtLeast(0)
                    last = time
                    observed = true
                }
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> { active.add(event.packageName); interactive = true }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> active.remove(event.packageName)
                    UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { interactive = false; active.clear() }
                    UsageEvents.Event.KEYGUARD_SHOWN -> unlocked = false
                    UsageEvents.Event.KEYGUARD_HIDDEN -> unlocked = true
                }
            }
            if (interactive && unlocked && active.isNotEmpty()) total += (now - last).coerceAtLeast(0)
            UsageSummary(true, total.coerceIn(0, now - start), observed)
        }.getOrDefault(UsageSummary(true))
    }
}
