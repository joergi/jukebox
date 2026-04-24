package com.joergi.jukebox.util

/**
 * Returns true if running on Android platform, false otherwise.
 * Used to conditionally enable Android-specific features like WorkManager-based scheduling.
 */
expect fun isAndroidPlatform(): Boolean
