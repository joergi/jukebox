package com.joergi.jukebox.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import com.joergi.jukebox.MainActivity
import com.joergi.jukebox.worker.ReminderItemProvider
import com.joergi.jukebox.worker.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

actual object NotificationService {
    private const val TAG = "NotificationService"
    private var context: Context? = null

    private const val CHANNEL_COLLECTIONS = "collections"
    private const val CHANNEL_REMINDERS = "jukebox_reminders"
    
    private const val REMINDER_NOTIFICATION_ID = 2000
    
    // Intent extras for notification click handling
    private const val EXTRA_ARTIST = "notification_artist"
    private const val EXTRA_TITLE = "notification_title"
    private const val EXTRA_INSTANCE_ID = "notification_instance_id"
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

    actual suspend fun showRandomRecordNotification(
        artist: String, 
        title: String, 
        thumbnailUrl: String?,
        instanceId: Int?
    ) {
        Log.d(TAG, "showRandomRecordNotification() called: artist='$artist', title='$title', instanceId=$instanceId")
        val ctx = context ?: run {
            Log.w(TAG, "Context is null - cannot show notification")
            return
        }
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: run {
                Log.w(TAG, "NotificationManager is null - cannot show notification")
                return
            }
        
        // Create Intent to open MainActivity with album info
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_ARTIST, artist)
            putExtra(EXTRA_TITLE, title)
            instanceId?.let { 
                putExtra(EXTRA_INSTANCE_ID, it)
                Log.d(TAG, "Added EXTRA_INSTANCE_ID=$it to Intent")
            }
            putExtra(EXTRA_FROM_NOTIFICATION, true)
        }
        Log.d(TAG, "Intent extras set: artist='$artist', title='$title', instanceId=$instanceId, fromNotification=true")
        
        // Create PendingIntent to open MainActivity when notification is clicked
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            REMINDER_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        
        // Load thumbnail image if available
        val largeIcon: Bitmap? = thumbnailUrl?.let { url ->
            try {
                Log.d(TAG, "Attempting to load thumbnail from: $url")
                withContext(Dispatchers.IO) {
                    URL(url).openStream().use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load thumbnail from $url: ${e.message}")
                null // Fail silently if image can't be loaded
            }
        }
        
        val notification = NotificationCompat.Builder(ctx, CHANNEL_REMINDERS)
            .setContentTitle("Jukebox Reminder")
            .setContentText("You have to play now this record: $artist \u2014 $title")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .apply {
                if (largeIcon != null) {
                    setLargeIcon(largeIcon)
                    setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(largeIcon)
                        .bigLargeIcon(null as Bitmap?) // Hide large icon when expanded
                    )
                }
            }
            .build()
        // Use a fixed notification ID so new reminders replace old ones
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
        Log.d(TAG, "Notification posted successfully with ID=$REMINDER_NOTIFICATION_ID, largeIcon=${if (largeIcon != null) "included" else "not available"}")
    }

    /**
     * On Android, background scheduling is handled by [ReminderWorker] via WorkManager.
     * This method stores the lambda reference for the worker to call and
     * delegates actual scheduling to [ReminderScheduler].
     */
    actual fun scheduleRandomReminder(
        intervalMinutes: Long,
        getRandomItem: () -> Quadruple<String, String, String?, Int>?,
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
