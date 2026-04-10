package com.joergi.jukebox.service

/**
 * Platform-agnostic notification service.
 * Handles showing notifications about new records to the user.
 * Implemented per-platform: Android, Desktop, iOS.
 */
expect object NotificationService {
    /**
     * Shows a notification that new records were detected.
     * @param count Number of new records added
     */
    suspend fun showNewRecordsNotification(count: Int)

    /**
     * Shows a random-record reminder notification.
     * Message: "You have to play now this record: [artist] — [title]"
     * @param artist Artist name(s)
     * @param title  Album/record title
     */
    suspend fun showRandomRecordNotification(artist: String, title: String)

    /**
     * Schedules a repeating random-record reminder at the given interval.
     * Cancels any previously scheduled reminder first.
     * On Desktop: uses a background Timer thread.
     * On Android: uses WorkManager (background-safe).
     * On iOS: uses UNTimeIntervalNotificationTrigger (repeating).
     * @param intervalMinutes How often to remind the user (in minutes)
     * @param getRandomItem   Supplier called each time to obtain the item to show;
     *                        returns null if the collection is empty
     */
    fun scheduleRandomReminder(
        intervalMinutes: Long,
        getRandomItem: () -> Pair<String, String>?,
    )

    /**
     * Cancels any active random-record reminder schedule.
     */
    fun cancelRandomReminder()
}
