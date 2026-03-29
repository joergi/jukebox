package com.joergi.jukebox.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.joergi.jukebox.model.CollectionSyncMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

actual class SecureStorage(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jukebox_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    actual suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value).apply()
    }

    actual suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key, null)
    }

    actual suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    actual suspend fun saveCollectionSyncMetadata(
        username: String,
        metadata: CollectionSyncMetadata
    ) = withContext(Dispatchers.IO) {
        val json = Json.encodeToString(metadata)
        prefs.edit().putString(StorageKeys.collectionSyncMetadata(username), json).apply()
    }

    actual suspend fun loadCollectionSyncMetadata(
        username: String
    ): CollectionSyncMetadata? = withContext(Dispatchers.IO) {
        val json = prefs.getString(StorageKeys.collectionSyncMetadata(username), null)
        json?.let { Json.decodeFromString(it) }
    }

    actual suspend fun saveNewestFiftyIds(
        username: String,
        ids: List<Int>
    ) = withContext(Dispatchers.IO) {
        val json = Json.encodeToString(ids)
        prefs.edit().putString(StorageKeys.newestFiftyIds(username), json).apply()
    }

    actual suspend fun loadNewestFiftyIds(
        username: String
    ): List<Int> = withContext(Dispatchers.IO) {
        val json = prefs.getString(StorageKeys.newestFiftyIds(username), null)
        json?.let { Json.decodeFromString(it) } ?: emptyList()
    }
}
