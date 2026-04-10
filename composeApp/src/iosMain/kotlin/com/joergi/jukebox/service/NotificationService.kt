package com.joergi.jukebox.service

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNNotificationRequest

actual object NotificationService {

    private const val REMINDER_ID = "jukebox_random_reminder"

    /** Must be called once at app start to request notification permission. */
    fun requestAuthorization() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or
            UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { _, _ -> }
    }

    actual suspend fun showNewRecordsNotification(count: Int) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val content = UNMutableNotificationContent()
        content.setTitle("New Records Added")
        content.setBody("$count new records added to your collection!")
        content.setSound(UNNotificationSound.defaultSound)

        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(
            "new_records_$count",
            content = content,
            trigger = trigger,
        )
        center.addNotificationRequest(request) { _ -> }
    }

    actual suspend fun showRandomRecordNotification(artist: String, title: String) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val content = UNMutableNotificationContent()
        content.setTitle("Jukebox Reminder")
        content.setBody("You have to play now this record: $artist \u2014 $title")
        content.setSound(UNNotificationSound.defaultSound)

        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(1.0, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(
            "random_record_now",
            content = content,
            trigger = trigger,
        )
        center.addNotificationRequest(request) { _ -> }
    }

    /**
     * Schedules a repeating random-record reminder using UNTimeIntervalNotificationTrigger.
     *
     * Note: [getRandomItem] is captured at schedule time. Because the iOS
     * notification system fires the trigger even while the app is in the
     * foreground, this works for foreground-only reminders.
     * For true background delivery when the app is fully killed, the Xcode
     * host project would need to set up background modes — not in scope here.
     *
     * The notification content (artist/title) is baked in at schedule time
     * and repeats with the same text until rescheduled.
     */
    actual fun scheduleRandomReminder(
        intervalMinutes: Long,
        getRandomItem: () -> Pair<String, String>?,
    ) {
        cancelRandomReminder()

        val item = getRandomItem() ?: return
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val content = UNMutableNotificationContent()
        content.setTitle("Jukebox Reminder")
        content.setBody("You have to play now this record: ${item.first} \u2014 ${item.second}")
        content.setSound(UNNotificationSound.defaultSound)

        val intervalSeconds = (intervalMinutes * 60L).toDouble()
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            intervalSeconds,
            repeats = true,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            REMINDER_ID,
            content = content,
            trigger = trigger,
        )
        center.addNotificationRequest(request) { _ -> }
    }

    actual fun cancelRandomReminder() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removePendingNotificationRequestsWithIdentifiers(listOf(REMINDER_ID))
    }
}
