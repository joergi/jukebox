package com.joergi.jukebox

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.service.NotificationService
import com.joergi.jukebox.storage.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS entry point.
 *
 * Called from the Swift/SwiftUI host as:
 *   MainViewControllerKt.MainViewController()
 *
 * Credentials are read from the app's main bundle Info.plist at Gradle build
 * time (injected via a build phase script reading local.properties).
 * For simplicity the keys are read from the bundle; update the Xcode build
 * phase to inject them if needed, or hardcode placeholders for development.
 */
fun MainViewController() = ComposeUIViewController {

    // Request notification permission on first launch (best-effort, no-op if already granted)
    LaunchedEffect(Unit) {
        NotificationService.requestAuthorization()
    }

    val httpClient = remember {
        HttpClient(Darwin) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Logging) { level = LogLevel.HEADERS }
        }
    }

    // Read credentials injected into the bundle by a build phase (or use
    // empty strings for local dev without real credentials).
    val consumerKey = (
        platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("DISCOGS_CONSUMER_KEY")
            as? String
        ) ?: ""
    val consumerSecret = (
        platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("DISCOGS_CONSUMER_SECRET")
            as? String
        ) ?: ""

    val service = remember {
        DiscogsService(
            consumerKey = consumerKey,
            consumerSecret = consumerSecret,
            httpClient = httpClient,
        )
    }

    val storage = remember { SecureStorage() }

    val openUrl: suspend (String) -> Unit = { url ->
        NSURL.URLWithString(url)?.let {
            UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null)
        }
    }

    App(
        service = service,
        storage = storage,
        openUrl = openUrl,
    )
}
