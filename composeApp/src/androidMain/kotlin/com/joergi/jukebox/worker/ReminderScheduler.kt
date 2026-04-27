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
 * 
 * Uses cron-style scheduling: reminders fire at aligned time slots
 * (e.g., with 15-minute interval: 14:00, 14:15, 14:30, 14:45, 15:00, etc.)
 * 
 * **Important:** The `setInitialDelay()` approach has limitations with WorkManager's
 * internal scheduling. To ensure truly aligned firing times, the worker itself
 * (ReminderWorker) calculates the correct time to next slot and can reschedule
 * itself as needed for precise cron-like behavior.
 */
object ReminderScheduler {

    private const val WORK_NAME = "jukebox_random_reminder"

    /**
     * Calculates milliseconds until the next aligned slot.
     * For example, with interval=15 and current time 14:32:45, returns ms until 14:45:00.
     */
    private fun getMillisUntilNextSlot(intervalMinutes: Long): Long {
        val now = System.currentTimeMillis()
        val totalMinutes = now / 60_000L
        val nextSlot = ((totalMinutes / intervalMinutes) + 1) * intervalMinutes
        val nextSlotMs = nextSlot * 60_000L
        return nextSlotMs - now
    }

    /**
     * Enqueues (or replaces) a periodic reminder that fires at aligned time slots.
     *
     * Uses [ExistingPeriodicWorkPolicy.UPDATE] so rescheduling with a new interval
     * cancels the previous chain rather than stacking multiple workers.
     *
     * **Note:** WorkManager enforces a minimum interval of 15 minutes on Android.
     * If [intervalMinutes] is less than 15, it will be silently clamped to 15 minutes
     * by the OS, not by this code.
     * 
     * **Important Note on Timing:**
     * While the initial delay is calculated to align with the next cron slot,
     * WorkManager may not execute at the exact millisecond due to its internal
     * batching and throttling. The worker will execute sometime around the scheduled
     * time, but for production-grade cron-like precision, the worker itself should
     * verify and reschedule if needed.
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
        
        // Calculate the initial delay to align to the next slot
        val initialDelayMs = getMillisUntilNextSlot(actualInterval)
        
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(
            actualInterval,
            TimeUnit.MINUTES,
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        
        Log.d(
            "ReminderScheduler",
            "Scheduled cron-style reminder: interval=${actualInterval}m, initialDelay=${initialDelayMs}ms"
        )
    }

    /** Cancels any previously scheduled reminder work. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
