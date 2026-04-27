package com.joergi.jukebox.service

import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File

actual object NotificationService {

    // ── notify-send ───────────────────────────────────────────────────────────

    private val notifySend: String? =
        listOf("/usr/bin/notify-send", "/usr/local/bin/notify-send")
            .firstOrNull { File(it).canExecute() }

    private fun notifySend(title: String, body: String) {
        if (notifySend != null) {
            Runtime.getRuntime().exec(arrayOf(notifySend, "-a", "Jukebox", title, body))
            return
        }
        // Fallback: AWT tray bubble (X11 only, unreliable on Wayland)
        trayIcon()?.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }

    // ── Tray icon access (fallback only) ──────────────────────────────────────

    private fun trayIcon(): TrayIcon? {
        if (!SystemTray.isSupported()) return null
        val icons = SystemTray.getSystemTray().trayIcons
        return if (icons.isNotEmpty()) icons[0] else null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    actual suspend fun showNewRecordsNotification(count: Int) {
        notifySend(
            "New Records Added",
            "$count new records added to your collection!",
        )
    }

    actual suspend fun showRandomRecordNotification(
        artist: String, 
        title: String, 
        thumbnailUrl: String?,
        instanceId: Int?
    ) {
        notifySend(
            "Jukebox Reminder",
            "Time to play: $artist \u2014 $title",
        )
        // TODO: Desktop notifications don't support images via notify-send easily
        // Consider using a custom notification library in the future
        // instanceId parameter is unused on Desktop (no click handling)
    }

    actual fun scheduleRandomReminder(
        intervalMinutes: Long,
        getRandomItem: () -> Quadruple<String, String, String?, Int>?,
    ) {
        // Scheduling is handled by the ViewModel coroutine loop.
        // This expect/actual stub is retained for API compatibility.
    }

    actual fun cancelRandomReminder() {
        // No-op: cancellation is handled by the ViewModel's reminderJob.
    }
}
