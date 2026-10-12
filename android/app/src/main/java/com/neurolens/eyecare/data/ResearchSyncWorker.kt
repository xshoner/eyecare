package com.neurolens.eyecare.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** WorkManager obeys Android's network and background limits; no PC or Wi-Fi requirement. */
object ResearchSyncScheduler {
    private const val WORK = "research-aggregate-sync-v2"
    private const val PERIODIC = "research-aggregate-safety-v2"
    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context, manual: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ResearchSyncWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ResearchSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Page through the real local report table; never read raw camera or screen captures. */
    suspend fun backfill(context: Context, manager: ResearchUploadManager): Int =
        withContext(Dispatchers.IO) {
            if (!manager.status.value.enabled) return@withContext 0
            val store = SessionStore(context.applicationContext)
            try {
                var offset = 0
                var added = 0
                val sessionsWithReports = hashSetOf<String>()
                while (true) {
                    val page = store.reportPage(offset, 200)
                    if (page.isEmpty()) break
                    sessionsWithReports.addAll(page.map { it.sessionId })
                    added += manager.enqueueReports(page)
                    offset += page.size
                    if (page.size < 200) break
                }
                offset = 0
                while (true) {
                    val page = store.sessionPage(offset, 200)
                    if (page.isEmpty()) break
                    added += manager.enqueueLegacySessions(page.filter {
                        it.id !in sessionsWithReports && it.reason != "active" })
                    offset += page.size
                    if (page.size < 200) break
                }
                added
            } finally { store.close() }
        }
}

class ResearchSyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = ResearchUploadManager.get(applicationContext)
        try {
            if (!manager.resolveLegacyActivation()) return@withContext Result.retry()
            ResearchSyncScheduler.backfill(applicationContext, manager)
            if (manager.retry()) Result.success() else Result.retry()
        } catch (_: Exception) {
            // The queue is encrypted and persistent. WorkManager retries under its backoff policy.
            Result.retry()
        }
    }
}
