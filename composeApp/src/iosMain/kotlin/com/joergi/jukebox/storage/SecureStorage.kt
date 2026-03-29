package com.joergi.jukebox.storage

import platform.Foundation.NSUserDefaults
import com.joergi.jukebox.model.CollectionSyncMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * iOS actual: stores tokens in NSUserDefaults with a jukebox_ prefix.
 *
 * For a production app use the Keychain (Security framework) instead.
 * NSUserDefaults is used here to keep the KMP actual free of Swift/ObjC
 * interop complexity; the interface is identical so swapping is trivial.
 */
actual class SecureStorage {

    private val defaults = NSUserDefaults.standardUserDefaults

    actual suspend fun write(key: String, value: String) {
        defaults.setObject(value, forKey = "jukebox_$key")
        defaults.synchronize()
    }

    actual suspend fun read(key: String): String? =
        defaults.stringForKey("jukebox_$key")

    actual suspend fun delete(key: String) {
        defaults.removeObjectForKey("jukebox_$key")
        defaults.synchronize()
    }

    actual suspend fun saveCollectionSyncMetadata(
        username: String,
        metadata: CollectionSyncMetadata
    ) {
        val json = Json.encodeToString(metadata)
        defaults.setObject(json, forKey = "jukebox_${StorageKeys.collectionSyncMetadata(username)}")
        defaults.synchronize()
    }

    actual suspend fun loadCollectionSyncMetadata(
        username: String
    ): CollectionSyncMetadata? {
        val json = defaults.stringForKey("jukebox_${StorageKeys.collectionSyncMetadata(username)}")
        return json?.let { Json.decodeFromString(it) }
    }

    actual suspend fun saveNewestFiftyIds(
        username: String,
        ids: List<Int>
    ) {
        val json = Json.encodeToString(ids)
        defaults.setObject(json, forKey = "jukebox_${StorageKeys.newestFiftyIds(username)}")
        defaults.synchronize()
    }

    actual suspend fun loadNewestFiftyIds(
        username: String
    ): List<Int> {
        val json = defaults.stringForKey("jukebox_${StorageKeys.newestFiftyIds(username)}")
        return json?.let { Json.decodeFromString(it) } ?: emptyList()
    }
}
