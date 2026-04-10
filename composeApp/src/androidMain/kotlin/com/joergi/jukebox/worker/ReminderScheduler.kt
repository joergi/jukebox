package com.joergi.jukebox.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Manages the WorkManager periodic task that drives random-record reminders.
 *
 * WorkManager enforces a minimum repeat interval of 15 minutes; intervals
 * below that floor are silently clamped to 15 minutes by the OS.
 */
object ReminderScheduler {

    private const val WORK_NAME = "jukebox_random_reminder"

    /**
     * Enqueues (or replaces) a periodic reminder that fires every [intervalMinutes].
     *
     * Uses [ExistingPeriodicWorkPolicy.UPDATE] so rescheduling with a new interval
     * cancels the previous chain rather than stacking multiple workers.
     */
    fun schedule(context: Context, intervalMinutes: Long) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Cancels any previously scheduled reminder work. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
