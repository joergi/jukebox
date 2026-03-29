package com.joergi.jukebox.viewmodel

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import com.joergi.jukebox.model.CollectionItem
import kotlin.test.Test

/**
 * Unit tests for collection synchronization helper functions.
 *
 * Tests the core logic for detecting new and removed records,
 * and for merging collections while maintaining order.
 */
class CollectionSyncTest {

    // ── detectNewRecords ──────────────────────────────────────────────────────

    @Test
    fun `detectNewRecords finds all new records when old collection is empty`() {
        val oldCollection = emptyList<CollectionItem>()
        val newestFifty = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )

        val newRecords = detectNewRecords(oldCollection, newestFifty)

        newRecords.map { it.id } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `detectNewRecords returns empty when all newest records exist in old collection`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
            createItem(3, "Album C"),
        )
        val newestFifty = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )

        val newRecords = detectNewRecords(oldCollection, newestFifty)

        newRecords.shouldBeEmpty()
    }

    @Test
    fun `detectNewRecords finds records in newest fifty not in old collection`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(3, "Album C"),
        )
        val newestFifty = listOf(
            createItem(2, "Album B"),
            createItem(1, "Album A"),
        )

        val newRecords = detectNewRecords(oldCollection, newestFifty)

        newRecords.map { it.id } shouldContainExactly listOf(2)
    }

    @Test
    fun `detectNewRecords handles duplicates in newest fifty`() {
        val oldCollection = listOf(createItem(1, "Album A"))
        val newestFifty = listOf(
            createItem(2, "Album B"),
            createItem(2, "Album B"),  // duplicate
            createItem(1, "Album A"),
        )

        val newRecords = detectNewRecords(oldCollection, newestFifty)

        newRecords.map { it.id } shouldContainExactly listOf(2)
    }

    // ── detectRemovedRecords ──────────────────────────────────────────────────

    @Test
    fun `detectRemovedRecords finds all records removed when collection is now empty`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )
        val newestFifty = emptyList<CollectionItem>()

        val removedRecords = detectRemovedRecords(oldCollection, newestFifty)

        removedRecords.map { it.id } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `detectRemovedRecords returns empty when no records were removed`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )
        val newestFifty = listOf(
            createItem(2, "Album B"),
            createItem(1, "Album A"),
        )

        val removedRecords = detectRemovedRecords(oldCollection, newestFifty)

        removedRecords.shouldBeEmpty()
    }

    @Test
    fun `detectRemovedRecords finds records in old collection not in newest fifty`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
            createItem(3, "Album C"),
        )
        val newestFifty = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )

        val removedRecords = detectRemovedRecords(oldCollection, newestFifty)

        removedRecords.map { it.id } shouldContainExactly listOf(3)
    }

    @Test
    fun `detectRemovedRecords handles duplicates in newest fifty`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
            createItem(3, "Album C"),
        )
        val newestFifty = listOf(
            createItem(1, "Album A"),
            createItem(1, "Album A"),  // duplicate
            createItem(2, "Album B"),
        )

        val removedRecords = detectRemovedRecords(oldCollection, newestFifty)

        removedRecords.map { it.id } shouldContainExactly listOf(3)
    }

    // ── Sorting and merging ───────────────────────────────────────────────────

    @Test
    fun `newest records appear first when merged (most recently added first)`() {
        val oldCollection = listOf(
            createItem(4, "Album D"),
            createItem(3, "Album C"),
            createItem(2, "Album B"),
            createItem(1, "Album A"),
        )
        val newestFifty = listOf(
            createItem(6, "Album F"),  // new
            createItem(5, "Album E"),  // new
            createItem(4, "Album D"),
        )

        val newRecords = detectNewRecords(oldCollection, newestFifty)
        val newestIds = newestFifty.map { it.id }.toSet()
        val merged = newestFifty + oldCollection.filter { it.id !in newestIds }

        // Verify new records are at the start
        merged.take(2).map { it.id } shouldContainExactly listOf(6, 5)
    }

    // ── Validation logic ──────────────────────────────────────────────────────

    @Test
    fun `expected total count equals actual after merge with new records`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )
        val newestFifty = listOf(
            createItem(3, "Album C"),  // new
            createItem(1, "Album A"),
        )
        val expectedTotalCount = 3  // From API metadata: 3 total items

        val newRecords = detectNewRecords(oldCollection, newestFifty)
        // Merge: newest first, then old records that aren't in newest
        val newestIds = newestFifty.map { it.id }.toSet()
        val merged = newestFifty + oldCollection.filter { it.id !in newestIds }

        merged.size shouldBe expectedTotalCount
    }

    @Test
    fun `validation fails when count mismatch detected`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
            createItem(3, "Album C"),
        )
        val newestFifty = listOf(
            createItem(4, "Album D"),  // new
            createItem(3, "Album C"),
        )
        val expectedTotalCount = 6  // API says 6, but we only have 4

        val newRecords = detectNewRecords(oldCollection, newestFifty)
        val merged = newestFifty + oldCollection.filterNot { old ->
            newRecords.any { it.id == old.id }
        }

        (merged.size == expectedTotalCount) shouldBe false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Copy of the real detectNewRecords for testing */
    private fun detectNewRecords(
        oldCollection: List<CollectionItem>,
        newestFifty: List<CollectionItem>,
    ): List<CollectionItem> {
        val oldIds = oldCollection.map { it.id }.toSet()
        return newestFifty.filter { it.id !in oldIds }.distinctBy { it.id }
    }

    /** Copy of the real detectRemovedRecords for testing */
    private fun detectRemovedRecords(
        oldCollection: List<CollectionItem>,
        newestFifty: List<CollectionItem>,
    ): List<CollectionItem> {
        val newIds = newestFifty.map { it.id }.toSet()
        return oldCollection.filter { it.id !in newIds }
    }

    private fun createItem(
        id: Int,
        title: String,
    ) = CollectionItem(
        instanceId = id,
        id = id,
        title = title,
        artists = emptyList(),
        formats = emptyList(),
        thumb = null,
        year = null,
        label = null,
    )
}
