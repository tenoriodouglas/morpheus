package com.morpheus.family.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.schedule.ScheduleEnforcer
import com.morpheus.family.service.GuardianService
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net that keeps the child under enforcement even if the
 * guardian service was killed and START_STICKY / the restart alarm both missed.
 *
 * It enforces directly (works from the background regardless of the service) and
 * also tries to revive [GuardianService]. WorkManager survives process death,
 * reboots and updates, which is what makes persistence WhatsApp-like.
 */
class GuardianWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (Prefs(app).mode() == AppMode.CHILD) {
            runCatching { ScheduleEnforcer.syncAndApply(app) }
            // Background FGS start can be blocked on Android 12+ without the
            // battery exemption; the enforcement above still ran regardless.
            runCatching { GuardianService.start(app) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "guardian-watchdog"

        /** Idempotent: keeps the existing schedule if already enqueued. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<GuardianWatchdogWorker>(
                15, TimeUnit.MINUTES,
            ).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES).build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP, request,
                )
            }
        }
    }
}
