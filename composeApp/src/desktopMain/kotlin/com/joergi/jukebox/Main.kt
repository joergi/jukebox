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
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Properties

fun main() = application {
    // ── Read credentials ──────────────────────────────────────────────────────
    val localProps = Properties().also { props ->
        val f = File("local.properties")
        if (f.exists()) props.load(f.inputStream())
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
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
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
