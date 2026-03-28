package com.joergi.jukebox.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for the Desktop actual implementation of [SecureStorage]
 * (DataStore Preferences).
 *
 * A [TemporaryFolder] rule creates a fresh directory for each test so that
 * DataStore files don't leak between runs.
 */
class SecureStorageTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var storage: SecureStorage

    @BeforeTest
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.createWithPath(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = {
                tmpFolder.newFile("test_prefs.preferences_pb").absolutePath.toPath()
            },
        )
        storage = SecureStorage(dataStore)
    }

    @Test
    fun `write and read returns the stored value`() = runTest {
        storage.write("key1", "hello")
        storage.read("key1") shouldBe "hello"
    }

    @Test
    fun `read returns null for unknown key`() = runTest {
        storage.read("nonexistent").shouldBeNull()
    }

    @Test
    fun `delete removes a previously stored key`() = runTest {
        storage.write("key2", "to_delete")
        storage.delete("key2")
        storage.read("key2").shouldBeNull()
    }

    @Test
    fun `delete on unknown key does not throw`() = runTest {
        storage.delete("never_written") // should not throw
    }

    @Test
    fun `overwrite replaces the existing value`() = runTest {
        storage.write("key3", "first")
        storage.write("key3", "second")
        storage.read("key3") shouldBe "second"
    }

    @Test
    fun `multiple keys are stored independently`() = runTest {
        storage.write(StorageKeys.ACCESS_TOKEN, "token_value")
        storage.write(StorageKeys.ACCESS_TOKEN_SECRET, "secret_value")
        storage.write(StorageKeys.USERNAME, "user123")

        storage.read(StorageKeys.ACCESS_TOKEN) shouldBe "token_value"
        storage.read(StorageKeys.ACCESS_TOKEN_SECRET) shouldBe "secret_value"
        storage.read(StorageKeys.USERNAME) shouldBe "user123"
    }
}
