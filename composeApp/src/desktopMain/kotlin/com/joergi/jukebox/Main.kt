package com.joergi.jukebox

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Image
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.util.Properties

fun main() = application {
    // ── Read credentials ──────────────────────────────────────────────────────
    val localProps = Properties().also { props ->
        val f = listOf(File("local.properties"), File("../local.properties"))
            .firstOrNull { it.exists() }
        if (f != null) props.load(f.inputStream())
    }
    val consumerKey = localProps.getProperty("discogs.consumerKey", "")
    val consumerSecret = localProps.getProperty("discogs.consumerSecret", "")

    // ── DataStore (file-based token persistence) ──────────────────────────────
    val dataStoreDir = File(System.getProperty("user.home"), ".jukebox")
    dataStoreDir.mkdirs()
    val dataStore = PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = null,
        migrations = emptyList(),
        scope = CoroutineScope(Dispatchers.IO),
        produceFile = { File(dataStoreDir, "jukebox_prefs.preferences_pb").absolutePath.toPath() },
    )

    // ── HTTP client ───────────────────────────────────────────────────────────
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) { level = LogLevel.HEADERS }
    }

    val service = DiscogsService(
        consumerKey = consumerKey,
        consumerSecret = consumerSecret,
        httpClient = httpClient,
    )

    val storage = SecureStorage(dataStore)

    val openUrl: suspend (String) -> Unit = { url ->
        withContext(Dispatchers.IO) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                // Fallback: try xdg-open / open on platforms where Desktop.browse isn't supported
                val os = System.getProperty("os.name").lowercase()
                val cmd = when {
                    os.contains("linux") -> arrayOf("xdg-open", url)
                    os.contains("mac") -> arrayOf("open", url)
                    os.contains("win") -> arrayOf("cmd", "/c", "start", url)
                    else -> null
                }
                cmd?.let { Runtime.getRuntime().exec(it) }
            }
        }
    }

    // ── System tray icon (required for displayMessage notifications) ──────────
    if (SystemTray.isSupported()) {
        val tray = SystemTray.getSystemTray()
        if (tray.trayIcons.isEmpty()) {
            val iconSize: Dimension = tray.trayIconSize
            val img: Image = try {
                val url = object {}.javaClass.getResource("/jukebox_tray.png")
                if (url != null) Toolkit.getDefaultToolkit().getImage(url)
                    .getScaledInstance(iconSize.width, iconSize.height, Image.SCALE_SMOOTH)
                else createFallbackTrayImage(iconSize)
            } catch (_: Exception) {
                createFallbackTrayImage(iconSize)
            }
            val trayIcon = TrayIcon(img, "Jukebox")
            trayIcon.isImageAutoSize = true
            try { tray.add(trayIcon) } catch (_: Exception) { /* ignore if tray unavailable */ }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Jukebox",
        state = rememberWindowState(width = 420.dp, height = 780.dp),
    ) {
        App(
            service = service,
            storage = storage,
            openUrl = openUrl,
        )
    }
}

/** Creates a minimal solid-colour image as a tray icon fallback. */
private fun createFallbackTrayImage(size: Dimension): Image {
    val w = size.width.coerceAtLeast(16)
    val h = size.height.coerceAtLeast(16)
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(30, 30, 30)
    g.fillOval(0, 0, w, h)
    g.color = java.awt.Color(200, 200, 200)
    g.fillOval(w / 4, h / 4, w / 2, h / 2)
    g.dispose()
    return img
}

