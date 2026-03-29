package com.joergi.jukebox.service

import java.awt.SystemTray
import java.awt.TrayIcon

actual object NotificationService {
    actual suspend fun showNewRecordsNotification(count: Int) {
        if (SystemTray.isSupported()) {
            val tray = SystemTray.getSystemTray()
            // Get existing tray icon and show notification
            if (tray.trayIcons.isNotEmpty()) {
                tray.trayIcons[0].displayMessage(
                    "New Records Added",
                    "📀 $count new records added to your collection!",
                    TrayIcon.MessageType.INFO
                )
            }
        }
    }
}
