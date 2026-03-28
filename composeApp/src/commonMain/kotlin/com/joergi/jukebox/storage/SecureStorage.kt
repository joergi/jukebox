package com.joergi.jukebox.storage

/**
 * Minimal secure key-value store.
 *
 * Each platform provides its own actual implementation:
 *   - Android  → EncryptedSharedPreferences (Jetpack Security)
 *   - iOS      → Keychain via NSUserDefaults wrapper (simplified; use
 *                KeychainSwift or Security framework for production hardening)
 *   - Desktop  → DataStore Preferences (file-based, not encrypted — suitable
 *                for dev; replace with OS credential store for production)
 */
expect class SecureStorage {
    suspend fun write(key: String, value: String)
    suspend fun read(key: String): String?
    suspend fun delete(key: String)
}

object StorageKeys {
    const val ACCESS_TOKEN = "discogs_access_token"
    const val ACCESS_TOKEN_SECRET = "discogs_access_token_secret"
    const val USERNAME = "discogs_username"
}
