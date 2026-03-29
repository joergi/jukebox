package com.joergi.jukebox.util

import platform.Foundation.NSLog

actual fun logDebug(tag: String, message: String, throwable: Throwable?) {
    NSLog("[$tag] DEBUG: $message")
    if (throwable != null) {
        NSLog("  Error: ${throwable.message}")
    }
}

actual fun logInfo(tag: String, message: String, throwable: Throwable?) {
    NSLog("[$tag] INFO: $message")
    if (throwable != null) {
        NSLog("  Error: ${throwable.message}")
    }
}

actual fun logWarn(tag: String, message: String, throwable: Throwable?) {
    NSLog("[$tag] WARN: $message")
    if (throwable != null) {
        NSLog("  Error: ${throwable.message}")
    }
}

actual fun logError(tag: String, message: String, throwable: Throwable?) {
    NSLog("[$tag] ERROR: $message")
    if (throwable != null) {
        NSLog("  Error: ${throwable.message}")
    }
}
