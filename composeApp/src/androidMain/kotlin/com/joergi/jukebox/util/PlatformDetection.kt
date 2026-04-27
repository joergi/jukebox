package com.joergi.jukebox.util

import com.joergi.jukebox.worker.ReminderItemProvider

actual fun isAndroidPlatform(): Boolean = true

actual fun setReminderUsername(username: String) {
    ReminderItemProvider.setUsername(username)
}
