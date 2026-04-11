package com.joergi.jukebox.storage

import com.joergi.jukebox.model.CollectionSyncMetadata

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

    /**
     * Persists collection synchronization metadata to secure storage.
     * @param username The authenticated user's username
     * @param metadata The sync metadata to persist
     */
    suspend fun saveCollectionSyncMetadata(
        username: String,
        metadata: CollectionSyncMetadata
    )

    /**
     * Retrieves collection synchronization metadata from secure storage.
     * @param username The authenticated user's username
     * @return The metadata if previously saved, null if not found
     */
    suspend fun loadCollectionSyncMetadata(
        username: String
    ): CollectionSyncMetadata?

    /**
     * Persists the list of newest 50 release IDs for quick comparison.
     * Allows fast detection of which records are new without comparing full objects.
     * @param username The authenticated user's username
     * @param ids List of release IDs from latest sync
     */
    suspend fun saveNewestFiftyIds(
        username: String,
        ids: List<Int>
    )

    /**
     * Retrieves the list of newest 50 release IDs.
     * @param username The authenticated user's username
     * @return List of IDs, or empty list if not previously saved
     */
    suspend fun loadNewestFiftyIds(
        username: String
    ): List<Int>
}

object StorageKeys {
    const val ACCESS_TOKEN = "discogs_access_token"
    const val ACCESS_TOKEN_SECRET = "discogs_access_token_secret"
    const val USERNAME = "discogs_username"
    /** Interval in minutes between random-record reminder notifications. Default: 15. */
    const val RANDOM_NOTIFICATION_INTERVAL_MINUTES = "random_notification_interval_minutes"
    /** Dark mode preference. Default: false (light mode). */
    const val DARK_MODE = "dark_mode"
    /** Seconds remaining on the current reminder countdown timer. */
    const val REMINDER_COUNTDOWN_SECONDS = "reminder_countdown_seconds"
    fun collectionCache(username: String) = "collection_cache_$username"
    fun collectionSyncMetadata(username: String) = "collection_sync_metadata_$username"
    fun newestFiftyIds(username: String) = "newest_fifty_ids_$username"
    fun selectedRecordsHistory(username: String) = "selected_records_history_$username"
    fun currentHighlightedItem(username: String) = "current_highlighted_item_$username"
}
