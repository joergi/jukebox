package com.joergi.jukebox.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.joergi.jukebox.MainActivity
import com.joergi.jukebox.worker.ReminderItemProvider
import com.joergi.jukebox.worker.ReminderScheduler

actual object NotificationService {
    private var context: Context? = null

    private const val CHANNEL_COLLECTIONS = "collections"
    private const val CHANNEL_REMINDERS = "jukebox_reminders"
    
    private const val REMINDER_NOTIFICATION_ID = 2000
    
    // Intent extras for notification click handling
    private const val EXTRA_ARTIST = "notification_artist"
    private const val EXTRA_TITLE = "notification_title"
    private const val EXTRA_FROM_NOTIFICATION = "from_notification"

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        createNotificationChannels(ctx.applicationContext)
    }

    private fun createNotificationChannels(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        // Channel for new-records notifications
        if (manager.getNotificationChannel(CHANNEL_COLLECTIONS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_COLLECTIONS,
                    "New Records",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Notifies when new records are added to your collection" }
            )
        }
        // Channel for random-record reminders
        if (manager.getNotificationChannel(CHANNEL_REMINDERS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDERS,
                    "Play Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Reminds you to play a random record" }
            )
        }
    }

    actual suspend fun showNewRecordsNotification(count: Int) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val notification = NotificationCompat.Builder(ctx, CHANNEL_COLLECTIONS)
            .setContentTitle("New Records Added")
            .setContentText("$count new records added to your collection!")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        manager.notify(1001, notification)
    }

    actual suspend fun showRandomRecordNotification(artist: String, title: String) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        
        // Create Intent to open MainActivity with album info
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_ARTIST, artist)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }
        
        // Create PendingIntent to open MainActivity when notification is clicked
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            REMINDER_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        
        val notification = NotificationCompat.Builder(ctx, CHANNEL_REMINDERS)
            .setContentTitle("Jukebox Reminder")
            .setContentText("You have to play now this record: $artist \u2014 $title")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // Use a fixed notification ID so new reminders replace old ones
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    /**
     * On Android, background scheduling is handled by [ReminderWorker] via WorkManager.
     * This method stores the lambda reference for the worker to call and
     * delegates actual scheduling to [ReminderScheduler].
     */
    actual fun scheduleRandomReminder(
        intervalMinutes: Long,
        getRandomItem: () -> Pair<String, String>?,
    ) {
        val ctx = context ?: return
        ReminderItemProvider.setProvider(getRandomItem)
        ReminderScheduler.schedule(ctx, intervalMinutes)
    }

    actual fun cancelRandomReminder() {
        val ctx = context ?: return
        ReminderScheduler.cancel(ctx)
        ReminderItemProvider.clear()
    }
}
