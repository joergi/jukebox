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
     * @param thumbnailUrl Optional URL to the album cover image
     * @param instanceId The Discogs instance ID of the record for proper identification
     */
    suspend fun showRandomRecordNotification(
        artist: String, 
        title: String, 
        thumbnailUrl: String? = null,
        instanceId: Int? = null
    )

    /**
     * Schedules a repeating random-record reminder at the given interval.
     * Cancels any previously scheduled reminder first.
     * On Desktop: uses a background Timer thread.
     * On Android: uses WorkManager (background-safe).
     * On iOS: uses UNTimeIntervalNotificationTrigger (repeating).
     * @param intervalMinutes How often to remind the user (in minutes)
     * @param getRandomItem   Supplier called each time to obtain the item to show;
     *                        returns null if the collection is empty.
     *                        Returns (artist, title, thumbnailUrl, instanceId)
     */
    fun scheduleRandomReminder(
        intervalMinutes: Long,
        getRandomItem: () -> Quadruple<String, String, String?, Int>?,
    )

    /**
     * Cancels any active random-record reminder schedule.
     */
    fun cancelRandomReminder()
}

/** Helper data class for 4-tuple return values */
data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
