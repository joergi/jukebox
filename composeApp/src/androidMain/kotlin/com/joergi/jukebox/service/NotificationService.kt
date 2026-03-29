package com.joergi.jukebox.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

actual object NotificationService {
    private var context: Context? = null

    fun initialize(ctx: Context) {
        context = ctx
    }

    actual suspend fun showNewRecordsNotification(count: Int) {
        val ctx = context ?: return

        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val notification = NotificationCompat.Builder(ctx, "collections")
            .setContentTitle("New Records Added")
            .setContentText("📀 $count new records added to your collection!")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
