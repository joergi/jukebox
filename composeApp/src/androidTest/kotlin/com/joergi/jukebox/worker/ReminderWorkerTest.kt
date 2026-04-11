package com.joergi.jukebox.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests that the ReminderWorker correctly invokes the notification service.
 * Verifies that:
 * 1. The worker returns SUCCESS when a random item is available
 * 2. The worker returns SUCCESS when the collection is empty
 * 3. The item provider lambda is properly invoked
 */
class ReminderWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testReminderWorkerSucceedsWithRandomItem() = runBlocking {
        // Arrange: Set up a provider that returns a random item
        val testArtist = "Test Artist"
        val testTitle = "Test Album"
        ReminderItemProvider.setProvider { testArtist to testTitle }

        // Act: Execute the worker
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
        val result = worker.doWork()

        // Assert: Worker should succeed
        assertEquals(ListenableWorker.Result.success(), result)

        // Cleanup
        ReminderItemProvider.clear()
    }

    @Test
    fun testReminderWorkerSucceedsWithEmptyCollection() = runBlocking {
        // Arrange: Set up a provider that returns null (empty collection)
        ReminderItemProvider.setProvider { null }

        // Act: Execute the worker
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
        val result = worker.doWork()

        // Assert: Worker should still succeed (no error condition)
        assertEquals(ListenableWorker.Result.success(), result)

        // Cleanup
        ReminderItemProvider.clear()
    }

    @Test
    fun testReminderItemProviderInvoked() = runBlocking {
        // Arrange: Track whether the provider was invoked
        var wasInvoked = false
        ReminderItemProvider.setProvider {
            wasInvoked = true
            "Artist" to "Title"
        }

        // Act: Execute the worker
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
        worker.doWork()

        // Assert: Provider should have been invoked
        assert(wasInvoked) { "ReminderItemProvider was not invoked" }

        // Cleanup
        ReminderItemProvider.clear()
    }
}
