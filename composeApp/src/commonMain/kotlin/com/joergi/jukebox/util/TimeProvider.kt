package com.joergi.jukebox.util

/**
 * Platform-independent way to get the current time in milliseconds.
 * Implementations for each platform are provided via expect/actual.
 */
expect object TimeProvider {
    fun currentTimeMillis(): Long
}
