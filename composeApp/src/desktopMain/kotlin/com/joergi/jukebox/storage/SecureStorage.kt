package com.joergi.jukebox.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.joergi.jukebox.model.CollectionSyncMetadata
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Desktop actual: persists tokens via DataStore Preferences (plain file).
 *
 * The DataStore instance is created in desktopMain's entry point and injected
 * here. For a production desktop app consider integrating with the OS keyring
 * (libsecret on Linux, Keychain on macOS, Windows Credential Manager).
 */
actual class SecureStorage(private val dataStore: DataStore<Preferences>) {

    actual suspend fun write(key: String, value: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = value }
    }

    actual suspend fun read(key: String): String? =
        dataStore.data
            .map { prefs -> prefs[stringPreferencesKey(key)] }
            .firstOrNull()

    actual suspend fun delete(key: String) {
        dataStore.edit { prefs -> prefs.remove(stringPreferencesKey(key)) }
    }

    actual suspend fun saveCollectionSyncMetadata(
        username: String,
        metadata: CollectionSyncMetadata
    ) {
        val json = Json.encodeToString(metadata)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(StorageKeys.collectionSyncMetadata(username))] = json
        }
    }

    actual suspend fun loadCollectionSyncMetadata(
        username: String
    ): CollectionSyncMetadata? {
        val json = dataStore.data
            .map { prefs -> prefs[stringPreferencesKey(StorageKeys.collectionSyncMetadata(username))] }
            .firstOrNull()
        return json?.let { Json.decodeFromString(it) }
    }

    actual suspend fun saveNewestFiftyIds(
        username: String,
        ids: List<Int>
    ) {
        val json = Json.encodeToString(ids)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(StorageKeys.newestFiftyIds(username))] = json
        }
    }

    actual suspend fun loadNewestFiftyIds(
        username: String
    ): List<Int> {
        val json = dataStore.data
            .map { prefs -> prefs[stringPreferencesKey(StorageKeys.newestFiftyIds(username))] }
            .firstOrNull()
        return json?.let { Json.decodeFromString(it) } ?: emptyList()
    }
}
