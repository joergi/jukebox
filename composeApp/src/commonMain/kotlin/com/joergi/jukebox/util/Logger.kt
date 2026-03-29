package com.joergi.jukebox.util

/**
 * Simple cross-platform logger for KMP projects.
 * Use this instead of println or System.out for consistent logging across all platforms.
 */
object Logger {
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        logDebug(tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        logInfo(tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        logWarn(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        logError(tag, message, throwable)
    }
}

/**
 * Platform-specific logging implementations.
 */
expect fun logDebug(tag: String, message: String, throwable: Throwable? = null)
expect fun logInfo(tag: String, message: String, throwable: Throwable? = null)
expect fun logWarn(tag: String, message: String, throwable: Throwable? = null)
expect fun logError(tag: String, message: String, throwable: Throwable? = null)
