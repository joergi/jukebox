package com.joergi.jukebox.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.joergi.jukebox.service.NotificationService

/**
 * WorkManager worker that fires a single random-record reminder notification.
 *
 * Scheduled as a [androidx.work.PeriodicWorkRequest] by [ReminderScheduler].
 * Each execution asks [ReminderItemProvider] for a random (artist, title) pair
 * and delegates to [NotificationService.showRandomRecordNotification].
 *
 * If the collection is empty (provider returns null) the worker exits silently —
 * it will be retried at the next scheduled interval without any user-visible error.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val (artist, title) = ReminderItemProvider.getRandomItem() ?: return Result.success()
        NotificationService.showRandomRecordNotification(artist, title)
        return Result.success()
    }
}
