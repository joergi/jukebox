package com.joergi.jukebox.util

actual fun isAndroidPlatform(): Boolean = false

actual fun setReminderUsername(username: String) {
    // No-op on iOS - history tracking happens in ViewModel via forcePickRandom()
}
