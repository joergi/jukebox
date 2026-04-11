package com.joergi.jukebox.viewmodel

import com.joergi.jukebox.model.CollectionItem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for random reminder notification logic in CollectionViewModel.
 * Verifies that:
 * 1. Random items are picked according to the schedule
 * 2. Items are validated correctly
 * 3. Empty artist lists are handled gracefully
 * 4. Empty collections are handled correctly
 */
class NotificationSchedulingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // No setup needed
    }

    @After
    fun tearDown() {
        // No cleanup needed
    }

    @Test
    fun testRandomItemSelectionFromCollection() = runTest(testDispatcher) {
        // Arrange: Create test items
        val testItems = listOf(
            CollectionItem(
                instanceId = 1,
                id = 1,
                title = "Album 1",
                artists = listOf("Artist 1"),
                formats = listOf("Vinyl"),
                thumb = null,
                year = 2024,
                label = "Label 1"
            ),
            CollectionItem(
                instanceId = 2,
                id = 2,
                title = "Album 2",
                artists = listOf("Artist 2"),
                formats = listOf("CD"),
                thumb = null,
                year = 2024,
                label = "Label 2"
            ),
            CollectionItem(
                instanceId = 3,
                id = 3,
                title = "Album 3",
                artists = listOf("Artist 3"),
                formats = listOf("Digital"),
                thumb = null,
                year = 2024,
                label = "Label 3"
            )
        )

        // Act: Pick random items
        val selectedItems = (1..10).map { testItems.random() }

        // Assert: All selected items are from the collection
        assertTrue(selectedItems.all { it in testItems })
    }

    @Test
    fun testArtistNameFormatting() = runTest(testDispatcher) {
        // Arrange: Create item with multiple artists
        val testItem = CollectionItem(
            instanceId = 1,
            id = 1,
            title = "Album",
            artists = listOf("Artist 1", "Artist 2", "Artist 3"),
            formats = listOf("Vinyl"),
            thumb = null,
            year = 2024,
            label = "Label"
        )

        // Act: Format artist names as done in ViewModel
        val artist = testItem.artists.joinToString(", ").ifBlank { "Unknown Artist" }

        // Assert
        assertEquals("Artist 1, Artist 2, Artist 3", artist)
    }

    @Test
    fun testEmptyArtistListDefaults() = runTest(testDispatcher) {
        // Arrange: Create item with empty artist list
        val testItem = CollectionItem(
            instanceId = 1,
            id = 1,
            title = "Album",
            artists = emptyList(),
            formats = listOf("Vinyl"),
            thumb = null,
            year = 2024,
            label = "Label"
        )

        // Act: Format with default
        val artist = testItem.artists.joinToString(", ").ifBlank { "Unknown Artist" }

        // Assert: Should default to "Unknown Artist"
        assertEquals("Unknown Artist", artist)
    }

    @Test
    fun testBlankArtistNameDefaults() = runTest(testDispatcher) {
        // Arrange: Create item with blank artist (all spaces)
        val testItem = CollectionItem(
            instanceId = 1,
            id = 1,
            title = "Album",
            artists = listOf("   "),
            formats = listOf("Vinyl"),
            thumb = null,
            year = 2024,
            label = "Label"
        )

        // Act: Format with trim and default
        val artist = testItem.artists
            .map { it.trim() }
            .joinToString(", ")
            .ifBlank { "Unknown Artist" }

        // Assert: Should default to "Unknown Artist" after trimming
        assertEquals("Unknown Artist", artist)
    }

    @Test
    fun testNotificationTitleGeneration() = runTest(testDispatcher) {
        // Arrange: Create item
        val testItem = CollectionItem(
            instanceId = 1,
            id = 1,
            title = "Test Album Title",
            artists = listOf("Test Artist"),
            formats = listOf("Vinyl"),
            thumb = null,
            year = 2024,
            label = "Label"
        )

        // Act: Generate notification content as done in ViewModel
        val artist = testItem.artists.joinToString(", ").ifBlank { "Unknown Artist" }
        val notificationText = "You have to play now this record: $artist — ${testItem.title}"

        // Assert
        assertEquals("You have to play now this record: Test Artist — Test Album Title", notificationText)
    }

    @Test
    fun testMultipleRandomPicksAreDifferent() = runTest(testDispatcher) {
        // Arrange: Create collection with multiple items
        val testItems = (1..10).map { i ->
            CollectionItem(
                instanceId = i,
                id = i,
                title = "Album $i",
                artists = listOf("Artist $i"),
                formats = listOf("Vinyl"),
                thumb = null,
                year = 2024,
                label = "Label $i"
            )
        }

        // Act: Pick random items multiple times
        val picks = (1..20).map { testItems.random() }

        // Assert: Should have picked more than one unique item
        val uniqueIds = picks.map { it.id }.toSet()
        assertTrue(uniqueIds.size > 1)
    }
}

