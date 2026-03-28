package com.joergi.jukebox.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

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
}
