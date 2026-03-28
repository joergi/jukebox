package com.joergi.jukebox.service

import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.model.DiscogsCollectionResponse
import com.joergi.jukebox.model.DiscogsIdentityJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/**
 * Low-level Discogs API wrapper using Ktor.
 *
 * Implements the same OAuth 1.0a PLAINTEXT flow as the Flutter version.
 * An [HttpClient] is injected so tests can supply a mock engine.
 */
class DiscogsService(
    private val consumerKey: String,
    private val consumerSecret: String,
    private val httpClient: HttpClient,
) {
    companion object {
        private const val BASE_URL = "https://api.discogs.com"
        private const val AUTHORIZE_URL = "https://www.discogs.com/oauth/authorize"
        private const val USER_AGENT = "JukeboxKMP/1.0"
    }

    private var accessToken: String? = null
    private var accessTokenSecret: String? = null

    // Temporary credentials held during the OAuth handshake
    private var pendingRequestToken: String? = null
    private var pendingRequestTokenSecret: String? = null

    val isAuthenticated: Boolean
        get() = accessToken != null && accessTokenSecret != null

    // ── OAuth Step 1: obtain a request token, return the authorize URL ────────

    /**
     * Fetches a request token from Discogs and returns the URL the user must
     * open in their browser to grant access.
     */
    suspend fun startOAuthFlow(): String {
        val nonce = currentTimeMillis().toString()
        val timestamp = (currentTimeMillis() / 1000).toString()

        val response = httpClient.get("$BASE_URL/oauth/request_token") {
            header("User-Agent", USER_AGENT)
            header("Content-Type", "application/x-www-form-urlencoded")
            header(
                "Authorization",
                buildOAuthHeader(
                    consumerKey = consumerKey,
                    consumerSecret = consumerSecret,
                    nonce = nonce,
                    timestamp = timestamp,
                    oauthToken = null,
                    tokenSecret = null,
                    verifier = null,
                    callback = "oob",
                ),
            )
        }

        if (!response.status.isSuccess()) {
            throw DiscogsException("Failed to get request token: ${response.status} ${response.bodyAsText()}")
        }

        val params = parseQueryString(response.bodyAsText())
        pendingRequestToken = params["oauth_token"]
            ?: throw DiscogsException("Discogs did not return oauth_token")
        pendingRequestTokenSecret = params["oauth_token_secret"]
            ?: throw DiscogsException("Discogs did not return oauth_token_secret")

        return "$AUTHORIZE_URL?oauth_token=$pendingRequestToken"
    }

    // ── OAuth Step 2: exchange the verifier PIN for an access token ───────────

    /**
     * Completes the OAuth handshake. Returns the access token pair that should
     * be persisted in secure storage.
     */
    suspend fun completeOAuthFlow(verifier: String): OAuthTokens {
        val reqToken = pendingRequestToken
            ?: throw IllegalStateException("startOAuthFlow() must be called first.")
        val reqSecret = pendingRequestTokenSecret
            ?: throw IllegalStateException("startOAuthFlow() must be called first.")

        val nonce = currentTimeMillis().toString()
        val timestamp = (currentTimeMillis() / 1000).toString()

        val response = httpClient.post("$BASE_URL/oauth/access_token") {
            header("User-Agent", USER_AGENT)
            header("Content-Type", "application/x-www-form-urlencoded")
            header(
                "Authorization",
                buildOAuthHeader(
                    consumerKey = consumerKey,
                    consumerSecret = consumerSecret,
                    nonce = nonce,
                    timestamp = timestamp,
                    oauthToken = reqToken,
                    tokenSecret = reqSecret,
                    verifier = verifier,
                    callback = null,
                ),
            )
        }

        if (!response.status.isSuccess()) {
            throw DiscogsException("Failed to get access token: ${response.status} ${response.bodyAsText()}")
        }

        val params = parseQueryString(response.bodyAsText())
        val token = params["oauth_token"]
            ?: throw DiscogsException("Discogs did not return access oauth_token")
        val secret = params["oauth_token_secret"]
            ?: throw DiscogsException("Discogs did not return access oauth_token_secret")

        accessToken = token
        accessTokenSecret = secret
        pendingRequestToken = null
        pendingRequestTokenSecret = null

        return OAuthTokens(token, secret)
    }

    // ── Credential management ─────────────────────────────────────────────────

    fun setCredentials(token: String, secret: String) {
        accessToken = token
        accessTokenSecret = secret
    }

    fun clearCredentials() {
        accessToken = null
        accessTokenSecret = null
        pendingRequestToken = null
        pendingRequestTokenSecret = null
    }

    // ── Discogs API ───────────────────────────────────────────────────────────

    /** Returns the authenticated user's username. */
    suspend fun getIdentity(): String {
        val data: DiscogsIdentityJson = authenticatedGet("/oauth/identity")
        return data.username
    }

    /**
     * Returns a page of the user's collection from folder 0 ("All").
     *
     * @return Triple of (items, totalPages, totalItems)
     */
    suspend fun getCollection(
        username: String,
        page: Int = 1,
        perPage: Int = 25,
    ): CollectionResult {
        val data: DiscogsCollectionResponse = authenticatedGet(
            path = "/users/$username/collection/folders/0/releases",
            queryParams = mapOf(
                "page" to "$page",
                "per_page" to "$perPage",
                "sort" to "artist",
                "sort_order" to "asc",
            ),
        )
        return CollectionResult(
            items = data.releases.map { CollectionItem.fromJson(it) },
            totalPages = data.pagination.pages,
            totalItems = data.pagination.items,
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend inline fun <reified T> authenticatedGet(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
    ): T {
        val token = accessToken ?: throw IllegalStateException("Not authenticated.")
        val secret = accessTokenSecret ?: throw IllegalStateException("Not authenticated.")

        val nonce = currentTimeMillis().toString()
        val timestamp = (currentTimeMillis() / 1000).toString()

        val response = httpClient.get("$BASE_URL$path") {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/vnd.discogs.v2.discogs+json")
            header(
                "Authorization",
                buildOAuthHeader(
                    consumerKey = consumerKey,
                    consumerSecret = consumerSecret,
                    nonce = nonce,
                    timestamp = timestamp,
                    oauthToken = token,
                    tokenSecret = secret,
                    verifier = null,
                    callback = null,
                ),
            )
            queryParams.forEach { (k, v) -> parameter(k, v) }
        }

        if (!response.status.isSuccess()) {
            throw DiscogsException("Discogs API error ${response.status}: ${response.bodyAsText()}")
        }

        return response.body()
    }

    private fun buildOAuthHeader(
        consumerKey: String,
        consumerSecret: String,
        nonce: String,
        timestamp: String,
        oauthToken: String?,
        tokenSecret: String?,
        verifier: String?,
        callback: String?,
    ): String = buildString {
        append("OAuth ")
        append("oauth_consumer_key=\"$consumerKey\", ")
        append("oauth_nonce=\"$nonce\", ")
        if (oauthToken != null) append("oauth_token=\"$oauthToken\", ")
        // PLAINTEXT signature: consumerSecret & tokenSecret (empty if absent)
        append("oauth_signature=\"$consumerSecret&${tokenSecret ?: ""}\", ")
        append("oauth_signature_method=\"PLAINTEXT\", ")
        append("oauth_timestamp=\"$timestamp\"")
        if (verifier != null) append(", oauth_verifier=\"$verifier\"")
        if (callback != null) append(", oauth_callback=\"$callback\"")
    }

    private fun parseQueryString(raw: String): Map<String, String> =
        raw.split("&").associate { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) pair to "" else pair.substring(0, idx) to pair.substring(idx + 1)
        }
}

// ── Supporting types ──────────────────────────────────────────────────────────

data class OAuthTokens(val token: String, val secret: String)

data class CollectionResult(
    val items: List<CollectionItem>,
    val totalPages: Int,
    val totalItems: Int,
)

class DiscogsException(message: String) : Exception(message)

/** Platform-specific current time in milliseconds. Declared as expect so each
 *  platform can provide the correct implementation. */
expect fun currentTimeMillis(): Long
