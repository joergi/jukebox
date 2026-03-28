package com.joergi.jukebox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.joergi.jukebox.service.DiscogsService
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
