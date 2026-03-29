package com.joergi.jukebox.util

actual fun logDebug(tag: String, message: String, throwable: Throwable?) {
    println("[$tag] DEBUG: $message")
    if (throwable != null) {
        throwable.printStackTrace()
    }
}

actual fun logInfo(tag: String, message: String, throwable: Throwable?) {
    println("[$tag] INFO: $message")
    if (throwable != null) {
        throwable.printStackTrace()
    }
}

actual fun logWarn(tag: String, message: String, throwable: Throwable?) {
    System.err.println("[$tag] WARN: $message")
    if (throwable != null) {
        throwable.printStackTrace()
    }
}

actual fun logError(tag: String, message: String, throwable: Throwable?) {
    System.err.println("[$tag] ERROR: $message")
    if (throwable != null) {
        throwable.printStackTrace()
    }
}
