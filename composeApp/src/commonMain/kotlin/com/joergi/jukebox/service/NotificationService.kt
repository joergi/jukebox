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
}
