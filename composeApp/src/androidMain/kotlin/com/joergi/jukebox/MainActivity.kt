package com.joergi.jukebox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.service.NotificationService
import com.joergi.jukebox.storage.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permission granted or denied — notifications are best-effort
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialise notification service (creates channels, stores context)
        NotificationService.initialize(applicationContext)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val httpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Logging) { level = LogLevel.HEADERS }
        }

        val service = DiscogsService(
            consumerKey = BuildConfig.DISCOGS_CONSUMER_KEY,
            consumerSecret = BuildConfig.DISCOGS_CONSUMER_SECRET,
            httpClient = httpClient,
        )

        val storage = SecureStorage(applicationContext)

        val openUrl: suspend (String) -> Unit = { url ->
            withContext(Dispatchers.Main) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        setContent {
            App(
                service = service,
                storage = storage,
                openUrl = openUrl,
            )
        }
    }
}
