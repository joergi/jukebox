package com.joergi.jukebox.worker

import android.content.Context
import android.util.Log
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
     *
     * **Note:** WorkManager enforces a minimum interval of 15 minutes on Android.
     * If [intervalMinutes] is less than 15, it will be silently clamped to 15 minutes
     * by the OS, not by this code.
     */
    fun schedule(context: Context, intervalMinutes: Long) {
        val actualInterval = intervalMinutes.coerceAtLeast(15)
        if (intervalMinutes < 15) {
            Log.w(
                "ReminderScheduler",
                "Requested interval of ${intervalMinutes}m is below WorkManager minimum of 15m. " +
                "Will use 15 minutes instead."
            )
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(
            actualInterval,
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
