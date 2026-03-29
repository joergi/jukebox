package com.joergi.jukebox.service

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNNotificationRequest

actual object NotificationService {
    actual suspend fun showNewRecordsNotification(count: Int) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val content = UNMutableNotificationContent()

        content.setTitle("New Records Added")
        content.setBody("📀 $count new records added to your collection!")
        content.setSound(UNNotificationSound.defaultSound)

        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(
            "new_records_$count",
            content = content,
            trigger = trigger
        )

        center.addNotificationRequest(request) { error ->
            // Handle error silently
        }
    }
}
