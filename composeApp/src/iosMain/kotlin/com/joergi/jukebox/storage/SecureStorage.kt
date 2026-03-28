package com.joergi.jukebox.storage

import platform.Foundation.NSUserDefaults

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
}
