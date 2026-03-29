package com.joergi.jukebox.util

import android.util.Log

actual fun logDebug(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) {
        Log.d(tag, message, throwable)
    } else {
        Log.d(tag, message)
    }
}

actual fun logInfo(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) {
        Log.i(tag, message, throwable)
    } else {
        Log.i(tag, message)
    }
}

actual fun logWarn(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) {
        Log.w(tag, message, throwable)
    } else {
        Log.w(tag, message)
    }
}

actual fun logError(tag: String, message: String, throwable: Throwable?) {
    if (throwable != null) {
        Log.e(tag, message, throwable)
    } else {
        Log.e(tag, message)
    }
}
