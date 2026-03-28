package com.joergi.jukebox.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
}
