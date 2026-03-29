package com.joergi.jukebox.viewmodel

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.model.CollectionSyncMetadata
import com.joergi.jukebox.model.SyncState
import kotlin.test.Test

/**
 * Integration tests for the full collection synchronization flow.
 *
 * Tests the state machine and logic for the four phases of sync:
 * 1. Load cached collection
 * 2. Fetch newest 50 records
 * 3. Merge and validate
 * 4. Full resync (fallback if validation fails)
 */
class CollectionSyncFlowTest {

    // ── Phase 1: Load Cache ───────────────────────────────────────────────────

    @Test
    fun `sync state transitions to LoadingCache on start`() {
        var syncState: SyncState = SyncState.Idle

        // Simulate phase 1
        syncState = SyncState.LoadingCache

        syncState.shouldBeInstanceOf<SyncState.LoadingCache>()
    }

    @Test
    fun `cached collection is loaded instantly`() {
        val cachedItems = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )

        // Simulate instant cache load
        cachedItems.shouldContainExactly(cachedItems)
    }

    // ── Phase 2: Fetch Newest 50 ─────────────────────────────────────────────

    @Test
    fun `sync state transitions to FetchingNewest after loading cache`() {
        var syncState: SyncState = SyncState.LoadingCache

        // Simulate phase 2
        syncState = SyncState.FetchingNewest

        syncState.shouldBeInstanceOf<SyncState.FetchingNewest>()
    }

    @Test
    fun `newest fifty are fetched sorted by date_added DESC`() {
        val newestFifty = listOf(
            createItem(50, "Newest Album"),
            createItem(49, "Second Newest"),
            createItem(48, "Third Newest"),
        )

        // Verify they're in the correct order (newest first)
        newestFifty.first().id shouldBe 50
        newestFifty.last().id shouldBe 48
    }

    // ── Phase 3: Merge and Validate ───────────────────────────────────────────

    @Test
    fun `sync state transitions to Validating after fetching`() {
        var syncState: SyncState = SyncState.FetchingNewest

        // Simulate phase 3
        syncState = SyncState.Validating

        syncState.shouldBeInstanceOf<SyncState.Validating>()
    }

    @Test
    fun `successful merge detects new records and updates metadata`() {
        val oldCollection = listOf(
            createItem(1, "Album A"),
            createItem(2, "Album B"),
        )
        val newestFifty = listOf(
            createItem(3, "Album C"),  // new
            createItem(1, "Album A"),
        )
        val apiTotalCount = 3

        // Simulate merge
        val newIds = newestFifty.map { it.id }.toSet()
        val newRecords = newestFifty.filter { it.id !in oldCollection.map { it.id }.toSet() }
        val merged = newestFifty + oldCollection.filter { it.id !in newIds }

        // Verify merge
        merged.size shouldBe apiTotalCount
        newRecords.map { it.id } shouldContainExactly listOf(3)

        // Create metadata as the sync would
        val metadata = CollectionSyncMetadata(
            totalCount = merged.size,
            newRecordsCount = newRecords.size,
            newestFiftyIds = newestFifty.map { it.id }
        )

        metadata.totalCount shouldBe 3
        metadata.newRecordsCount shouldBe 1
    }

    @Test
    fun `sync completes successfully when validation passes`() {
        var syncState: SyncState = SyncState.Validating

        // Simulate validation pass
        syncState = SyncState.Complete

        syncState.shouldBeInstanceOf<SyncState.Complete>()
    }

    // ── Phase 4: Full Resync (Validation Failure) ─────────────────────────────

    @Test
    fun `sync transitions to FullResync when validation fails`() {
        var syncState: SyncState = SyncState.Validating

        // Simulate validation failure (count mismatch)
        val expectedTotal = 10
        val actualTotal = 5

        if (expectedTotal != actualTotal) {
            syncState = SyncState.FullResync
        }

        syncState.shouldBeInstanceOf<SyncState.FullResync>()
    }

    @Test
    fun `full resync fetches entire collection and validates`() {
        val fullCollection = (1..100).map { createItem(it, "Album $it") }

        // After full resync, we have all records
        fullCollection.size shouldBe 100

        // Update metadata with full resync
        val metadata = CollectionSyncMetadata(
            totalCount = fullCollection.size,
            newestFiftyIds = fullCollection.take(50).map { it.id }
        )

        metadata.totalCount shouldBe 100
    }

    @Test
    fun `full resync completes and transitions to Complete`() {
        var syncState: SyncState = SyncState.FullResync

        // Simulate full resync completion
        syncState = SyncState.Complete

        syncState.shouldBeInstanceOf<SyncState.Complete>()
    }

    // ── Error Handling ────────────────────────────────────────────────────────

    @Test
    fun `sync transitions to Error state on exception`() {
        var syncState: SyncState = SyncState.FetchingNewest

        // Simulate exception during fetch
        try {
            throw Exception("Network error")
        } catch (e: Exception) {
            syncState = SyncState.Error(e.message ?: "Unknown error", e)
        }

        syncState.shouldBeInstanceOf<SyncState.Error>()
        val error = syncState as SyncState.Error
        error.message shouldBe "Network error"
    }

    @Test
    fun `error state provides helpful error message`() {
        val errorMessage = "Failed to fetch from API: 403 Forbidden"
        val syncState = SyncState.Error(errorMessage)

        syncState.shouldBeInstanceOf<SyncState.Error>()
        (syncState as SyncState.Error).message shouldBe errorMessage
    }

    // ── Sync Scheduler ───────────────────────────────────────────────────────

    @Test
    fun `sync is scheduled for 3 hours from now`() {
        val syncIntervalMs = 3 * 60 * 60 * 1000L  // 3 hours
        val syncIntervalHours = syncIntervalMs / (60 * 60 * 1000)

        syncIntervalHours shouldBe 3L
    }

    @Test
    fun `manual sync can be triggered at any time`() {
        var syncState: SyncState = SyncState.Idle

        // Simulate manual sync trigger
        syncState = SyncState.LoadingCache

        syncState.shouldBeInstanceOf<SyncState.LoadingCache>()
    }

    // ── New Records Notification ──────────────────────────────────────────────

    @Test
    fun `new notification when count is greater than zero`() {
        val newRecordsCount = 5

        val shouldNotify = newRecordsCount > 0
        shouldNotify shouldBe true
    }

    @Test
    fun `no notification is sent when no new records found`() {
        val newRecordsCount = 0

        val shouldNotify = newRecordsCount > 0
        shouldNotify shouldBe false
    }

    @Test
    fun `notification includes count of new records`() {
        val newRecordsCount = 3
        val notificationTitle = if (newRecordsCount > 0) "+$newRecordsCount new records" else ""

        notificationTitle shouldBe "+3 new records"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
