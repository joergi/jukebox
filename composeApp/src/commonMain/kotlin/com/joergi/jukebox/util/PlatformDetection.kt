package com.joergi.jukebox.util

/**
 * Returns true if running on Android platform, false otherwise.
 * Used to conditionally enable Android-specific features like WorkManager-based scheduling.
 */
expect fun isAndroidPlatform(): Boolean

/**
 * Sets the username for Android WorkManager reminder history tracking.
 * On Android: Stores username in ReminderItemProvider for history persistence.
 * On other platforms: No-op (history tracking happens in-app via ViewModel).
 */
expect fun setReminderUsername(username: String)
