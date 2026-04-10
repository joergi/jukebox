package com.joergi.jukebox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.model.CollectionSyncMetadata
import com.joergi.jukebox.model.SyncState
import com.joergi.jukebox.model.formatSyncTime
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.service.NotificationService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.storage.StorageKeys
import com.joergi.jukebox.util.Logger
import com.joergi.jukebox.util.TimeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── State ─────────────────────────────────────────────────────────────────────

/**
 * Special filter tokens beyond the 26 letters.
 * [NUMBERS] matches artists whose name starts with a digit.
 * [SPECIAL] matches artists whose name starts with anything else (punctuation, symbols, …).
 */
enum class LetterFilter { NUMBERS, SPECIAL }

data class CollectionUiState(
    val items: List<CollectionItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    /** null = show all, Char = show that letter, LetterFilter = show # or ? group */
    val selectedFilter: Any? = null,
    /** Non-null while a randomly picked item should be highlighted in the list. */
    val highlightedItem: CollectionItem? = null,
    /**
     * Index into [filteredItems] to scroll to after a random pick.
     * Consumed (set to null) by the UI after scrolling.
     */
    val scrollToIndex: Int? = null,
    /**
     * 0f..1f progress of the background full-collection fetch.
     * null = not fetching (either done or not started yet).
     */
    val syncProgress: Float? = null,
    // Synchronization fields
    val syncState: SyncState = SyncState.Idle,
    val syncMetadata: CollectionSyncMetadata? = null,
    val newRecordsCount: Int = 0,
    /** Current random-reminder interval in minutes. Default 1. */
    val notificationIntervalMinutes: Long = DEFAULT_NOTIFICATION_INTERVAL_MINUTES,
    /** Seconds remaining until the next random pick. Null when no reminder is scheduled. */
    val reminderCountdownSeconds: Long? = null,
) {
    val hasMore: Boolean get() = currentPage < totalPages
    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null

    val filteredItems: List<CollectionItem>
        get() {
            if (selectedFilter == null) return items
            return items.filter { item ->
                val first = item.artists.firstOrNull()
                    ?.trimStart()
                    ?.firstOrNull()
                    ?: item.title.firstOrNull()
                    ?: return@filter false
                when (selectedFilter) {
                    is Char -> first.uppercaseChar() == selectedFilter.uppercaseChar()
                    LetterFilter.NUMBERS -> first.isDigit()
                    LetterFilter.SPECIAL -> !first.isLetter() && !first.isDigit()
                    else -> true
                }
            }
        }

    companion object {
        const val DEFAULT_NOTIFICATION_INTERVAL_MINUTES = 1L
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

private val cacheJson = Json { ignoreUnknownKeys = true }

/**
 * Manages paginated loading of a user's Discogs collection.
 *
 * On start it immediately restores the collection from the on-disk cache so
 * the list appears instantly, then fetches the full collection from Discogs
 * in the background and persists the updated result.
 *
 * [readCache] and [writeCache] are suspend lambdas so they can be backed by
 * any store: [SecureStorage] in production, an in-memory map in tests.
 * Use the secondary constructor taking [SecureStorage] for production.
 */
class CollectionViewModel(
    private val service: DiscogsService,
    private val username: String,
    private val readCache: suspend () -> String?,
    private val writeCache: suspend (String) -> Unit,
    private val storage: SecureStorage? = null,
    private val perPage: Int = 50,
) : ViewModel() {

    /** Production convenience constructor – wraps [SecureStorage]. */
    constructor(
        service: DiscogsService,
        username: String,
        storage: SecureStorage,
        perPage: Int = 50,
    ) : this(
        service = service,
        username = username,
        readCache = { storage.read(StorageKeys.collectionCache(username)) },
        writeCache = { json -> storage.write(StorageKeys.collectionCache(username), json) },
        storage = storage,
        perPage = perPage,
    )

    private val _uiState = MutableStateFlow(CollectionUiState())
    val uiState: StateFlow<CollectionUiState> = _uiState.asStateFlow()

    // Synchronization state
    private var syncTimer: Job? = null
    private var reminderJob: Job? = null
    private companion object {
        private const val SYNC_INTERVAL_MS = 3 * 60 * 60 * 1000L  // 3 hours
    }

    init {
        viewModelScope.launch {
            restoreFromCache()
            syncAllPages()
            loadAndApplyNotificationInterval()
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncTimer?.cancel()
        syncTimer = null
        reminderJob?.cancel()
        reminderJob = null
        NotificationService.cancelRandomReminder()
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private suspend fun restoreFromCache() {
        val json = readCache() ?: return
        runCatching {
            cacheJson.decodeFromString<List<CollectionItem>>(json)
        }.onSuccess { cached ->
            if (cached.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(
                        items = cached,
                        totalItems = cached.size,
                        // Mark all pages as "loaded" from cache so the list is
                        // immediately usable; syncAllPages will refresh below.
                        currentPage = 1,
                        totalPages = 1,
                    )
                }
            }
        }
    }

    private suspend fun persistToCache(items: List<CollectionItem>) {
        runCatching {
            val json = cacheJson.encodeToString(items)
            writeCache(json)
        }
    }

    // ── Full sync ─────────────────────────────────────────────────────────────

    /**
     * Fetches every page from the Discogs API, accumulating items and
     * reporting progress via [CollectionUiState.syncProgress].
     * Persists the complete list to cache when finished.
     */
    private suspend fun syncAllPages() {
        var page = 1
        var totalPages = 1
        val allItems = mutableListOf<CollectionItem>()

        _uiState.update { it.copy(syncProgress = 0f) }

        while (page <= totalPages) {
            runCatching {
                service.getCollection(username = username, page = page, perPage = perPage)
            }.onSuccess { result ->
                totalPages = result.totalPages
                allItems += result.items

                val progress = page.toFloat() / totalPages.toFloat()
                _uiState.update { state ->
                    state.copy(
                        items = allItems.toList(),
                        currentPage = page,
                        totalPages = totalPages,
                        totalItems = result.totalItems,
                        isLoading = false,
                        error = null,
                        syncProgress = if (page < totalPages) progress else null,
                    )
                }
                page++
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        syncProgress = null,
                        error = "Failed to load collection: ${e.message}",
                    )
                }
                return
            }
        }

        // Persist the freshly fetched full collection
        persistToCache(allItems)
    }

    // ── Incremental Sync ──────────────────────────────────────────────────────

    /**
     * Main entry point for incremental collection synchronization.
     * Coordinates all 4 phases of the sync process:
     * 1. Load cached collection
     * 2. Fetch newest 50 records
     * 3. Merge & validate
     * 4. Full resync (if validation fails)
     *
     * Called automatically on app launch and every 3 hours thereafter.
     * Non-blocking: users can continue using the app during sync.
     */
    suspend fun performIncrementalSync() {
        // Guard: only one sync at a time
        val currentState = _uiState.value.syncState
        if (currentState !is SyncState.Idle && currentState !is SyncState.Complete) {
            log("CollectionSync", "Sync already in progress, skipping")
            return
        }

        try {
            // Phase 1: Load cached collection
            _uiState.update { it.copy(syncState = SyncState.LoadingCache, syncProgress = 0f) }
            loadCachedCollection()

            val oldCount = _uiState.value.items.size

            // Phase 2: Fetch newest 50
            _uiState.update { it.copy(syncState = SyncState.FetchingNewest) }
            fetchNewestFifty()

            // Phase 3: Merge & validate
            _uiState.update { it.copy(syncState = SyncState.Validating) }
            mergeAndValidate(oldCount)

            _uiState.update { it.copy(syncState = SyncState.Complete, syncProgress = null) }

            // Schedule next sync in 3 hours
            scheduleNextSync()

        } catch (e: Exception) {
            log("CollectionSync", "Sync failed: ${e.message}", e)
            _uiState.update { it.copy(syncState = SyncState.Error("Sync failed: ${e.message}", e), syncProgress = null) }
        }
    }

    /**
     * Phase 1: Load collection from local cache
     * Goal: Display data to user immediately (<100ms)
     * Non-blocking, happens instantly from device storage
     */
    private suspend fun loadCachedCollection() {
        try {
            val json = readCache()
            val cachedCollection = json?.let {
                runCatching {
                    cacheJson.decodeFromString<List<CollectionItem>>(it)
                }.getOrNull() ?: emptyList()
            } ?: emptyList()

            val metadata = storage?.loadCollectionSyncMetadata(username)

            _uiState.update { state ->
                state.copy(
                    items = cachedCollection,
                    totalItems = cachedCollection.size,
                    syncMetadata = metadata,
                )
            }

            log("CollectionSync", "Phase 1: Loaded ${cachedCollection.size} records from cache")
        } catch (e: Exception) {
            log("CollectionSync", "Phase 1: Failed to load cache: ${e.message}", e)
            // Continue with empty collection
            _uiState.update { it.copy(items = emptyList()) }
        }
    }

    /**
     * Phase 2: Fetch newest 50 records from API
     * Goal: Detect if new records were added since last sync
     * Stores result separately (not merged yet)
     */
    private suspend fun fetchNewestFifty() {
        try {
            val newest = service.fetchNewestFiftyRecords(username)
            log("CollectionSync", "Phase 2: Fetched ${newest.size} newest records")
        } catch (e: Exception) {
            log("CollectionSync", "Phase 2: Failed to fetch newest records: ${e.message}", e)
            throw e
        }
    }

    /**
     * Phase 3: Merge new records with cached collection and validate
     *
     * Process:
     * 1. Detect new records (in newest 50 but not in cache)
     * 2. Detect removed records (in cache but not in newest 50)
     * 3. Merge: remove deleted, add new, re-sort by artist ASC
     * 4. Validate: check total count matches API
     * 5. If validation passes: save merged collection and metadata
     * 6. If validation fails: trigger full resync (Phase 4)
     *
     * @param oldCount Size of cached collection before sync
     */
    private suspend fun mergeAndValidate(oldCount: Int) {
        val cachedCollection = _uiState.value.items

        // For now, since we don't have newest fifty stored, do a full refetch
        val newestFifty = service.fetchNewestFiftyRecords(username)

        // Detect changes
        val newRecords = detectNewRecords(cachedCollection, newestFifty)
        val removedRecords = detectRemovedRecords(cachedCollection, newestFifty)

        log("CollectionSync", "Phase 3: Found ${newRecords.size} new, ${removedRecords.size} removed")

        // Merge
        val removedIds = removedRecords.map { it.id }.toSet()
        val merged = (cachedCollection.filter { it.id !in removedIds } + newRecords)
            .sortedBy { it.artists.firstOrNull() ?: it.title }

        // Validate
        val expectedTotal = oldCount + newRecords.size - removedRecords.size

        try {
            val actualTotal = service.getCollectionMetadata(username)

            if (expectedTotal != actualTotal) {
                log("CollectionSync",
                    "Phase 3: Validation FAILED - expected=$expectedTotal, actual=$actualTotal")

                // Validation failed - trigger full resync
                performFullResync()
                return
            }
        } catch (e: Exception) {
            log("CollectionSync", "Phase 3: Failed to get metadata for validation: ${e.message}", e)
            throw e
        }

        // Validation passed - save merged collection
        val newMetadata = CollectionSyncMetadata(
            totalCount = merged.size,
            lastSyncedAt = TimeProvider.currentTimeMillis(),
            newestFiftyIds = newestFifty.map { it.id },
            newRecordsCount = newRecords.size
        )

        persistToCache(merged)
        storage?.saveCollectionSyncMetadata(username, newMetadata)
        storage?.saveNewestFiftyIds(username, newRecords.map { it.id })

        _uiState.update { state ->
            state.copy(
                items = merged,
                totalItems = merged.size,
                syncMetadata = newMetadata,
                newRecordsCount = newRecords.size,
            )
        }

        log("CollectionSync", "Phase 3: Validation PASSED, merged collection saved")

        // Show notification if new records detected
        if (newRecords.isNotEmpty()) {
            NotificationService.showNewRecordsNotification(newRecords.size)
        }
    }

    /**
     * Phase 4: Full collection resync (triggered when Phase 3 validation fails)
     *
     * Process:
     * 1. Fetch ALL collection records (paginated)
     * 2. Sort by artist ASC
     * 3. Replace cache entirely
     * 4. Update metadata
     * 5. Return to normal state
     */
    private suspend fun performFullResync() {
        _uiState.update { it.copy(syncState = SyncState.FullResync) }

        try {
            log("CollectionSync", "Phase 4: Starting full resync")

            val allRecords = service.fetchAllCollectionRecords(
                username,
                sortBy = "artist",
                sortOrder = "asc"
            )

            val newMetadata = CollectionSyncMetadata(
                totalCount = allRecords.size,
                lastSyncedAt = TimeProvider.currentTimeMillis(),
                lastFullSyncAt = TimeProvider.currentTimeMillis(),
                newestFiftyIds = allRecords.take(50).map { it.id }
            )

            persistToCache(allRecords)
            storage?.saveCollectionSyncMetadata(username, newMetadata)

            _uiState.update { state ->
                state.copy(
                    items = allRecords,
                    totalItems = allRecords.size,
                    syncMetadata = newMetadata,
                )
            }

            log("CollectionSync", "Phase 4: Full resync complete, ${allRecords.size} records")

        } catch (e: Exception) {
            log("CollectionSync", "Phase 4: Full resync failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Identifies records in the newest 50 that are NOT in the cached collection.
     * These are records that were added to the collection since last sync.
     */
    private fun detectNewRecords(
        cachedCollection: List<CollectionItem>,
        newestFifty: List<CollectionItem>
    ): List<CollectionItem> {
        val cachedIds = cachedCollection.map { it.id }.toSet()
        return newestFifty.filter { it.id !in cachedIds }
    }

    /**
     * Identifies records in the cached collection that are NOT in the newest 50.
     * These are records that were removed from the collection.
     */
    private fun detectRemovedRecords(
        cachedCollection: List<CollectionItem>,
        newestFifty: List<CollectionItem>
    ): List<CollectionItem> {
        val newestIds = newestFifty.map { it.id }.toSet()
        return cachedCollection.filter { cached ->
            cached.id !in newestIds
        }
    }

    /**
     * Schedules the next incremental sync in 3 hours.
     * Only active if app remains open. Cancels if app is backgrounded.
     */
    private fun scheduleNextSync() {
        syncTimer?.cancel()

        syncTimer = viewModelScope.launch {
            delay(SYNC_INTERVAL_MS)
            log("CollectionSync", "3-hour sync interval reached, triggering sync")
            performIncrementalSync()
        }

        log("CollectionSync", "Scheduled next sync in 3 hours")
    }

    /**
     * Triggered when user taps the refresh button.
     * Performs a full resync immediately, replacing cached data completely.
     */
    fun performManualSync() {
        viewModelScope.launch {
            try {
                performFullResync()
                _uiState.update { it.copy(syncState = SyncState.Complete) }
                scheduleNextSync()
            } catch (e: Exception) {
                _uiState.update { it.copy(syncState = SyncState.Error("Manual sync failed: ${e.message}", e)) }
            }
        }
    }

    // ── Full sync ─────────────────────────────────────────────────────────────

    // ── Public API ────────────────────────────────────────────────────────────

    fun refresh() {
        _uiState.update {
            CollectionUiState(
                // Keep the cached items visible while refreshing
                items = it.items,
                totalItems = it.totalItems,
            )
        }
        viewModelScope.launch { syncAllPages() }
    }

    /** Pass a [Char] (A-Z), [LetterFilter.NUMBERS], [LetterFilter.SPECIAL], or null to show all. */
    fun filterLetter(filter: Any?) {
        _uiState.update { it.copy(selectedFilter = filter) }
    }

    /**
     * If no item is highlighted yet, picks a random record and highlights it.
     * If an item is already highlighted, scrolls back to it without changing the selection.
     * Clears any active letter filter so the item is visible.
     */
    fun pickRandom() {
        val state = _uiState.value
        val items = state.items
        if (items.isEmpty()) return
        val existing = state.highlightedItem
        if (existing != null) {
            // Scroll back to the already-selected item
            val index = items.indexOf(existing)
            _uiState.update {
                it.copy(
                    selectedFilter = null,
                    scrollToIndex = if (index >= 0) index else null,
                )
            }
        } else {
            val picked = items.random()
            val index = items.indexOf(picked)
            _uiState.update {
                it.copy(
                    selectedFilter = null,
                    highlightedItem = picked,
                    scrollToIndex = if (index >= 0) index else null,
                )
            }
        }
    }

    /** Called by the UI after it has consumed the scroll-to event. */
    fun onScrollToIndexConsumed() {
        _uiState.update { it.copy(scrollToIndex = null) }
    }

    /** Clears the random highlight. */
    fun clearHighlight() {
        _uiState.update { it.copy(highlightedItem = null, scrollToIndex = null) }
    }

    // ── Notification interval ─────────────────────────────────────────────────

    /**
     * Reads the saved notification interval from storage and starts the reminder.
     * Falls back to [CollectionUiState.DEFAULT_NOTIFICATION_INTERVAL_MINUTES] if not set.
     */
    private suspend fun loadAndApplyNotificationInterval() {
        val saved = storage?.read(StorageKeys.RANDOM_NOTIFICATION_INTERVAL_MINUTES)
            ?.toLongOrNull()
            ?: CollectionUiState.DEFAULT_NOTIFICATION_INTERVAL_MINUTES
        _uiState.update { it.copy(notificationIntervalMinutes = saved) }
        // Only schedule the in-process reminder when backed by real storage.
        // Without storage (e.g. in tests) the infinite delay loop would hang.
        if (storage != null) scheduleReminder(saved)
    }

    /**
     * Persists the new interval and reschedules the reminder.
     * Safe to call from any coroutine context.
     */
    fun setNotificationIntervalMinutes(minutes: Long) {
        viewModelScope.launch {
            storage?.write(StorageKeys.RANDOM_NOTIFICATION_INTERVAL_MINUTES, minutes.toString())
            _uiState.update { it.copy(notificationIntervalMinutes = minutes) }
            if (storage != null) scheduleReminder(minutes)
        }
    }

    private fun scheduleReminder(intervalMinutes: Long) {
        reminderJob?.cancel()
        reminderJob = viewModelScope.launch {
            val intervalSeconds = intervalMinutes * 60L
            while (true) {
                // Tick countdown every second
                var remaining = intervalSeconds
                while (remaining > 0) {
                    _uiState.update { it.copy(reminderCountdownSeconds = remaining) }
                    delay(1_000L)
                    remaining--
                }
                _uiState.update { it.copy(reminderCountdownSeconds = 0) }
                forcePickRandom()
                // Fire notification with the newly picked item
                val picked = _uiState.value.highlightedItem
                if (picked != null) {
                    val artist = picked.artists.joinToString(", ").ifBlank { "Unknown Artist" }
                    NotificationService.showRandomRecordNotification(artist, picked.title)
                }
            }
        }
    }

    /** Always picks a new random record, replacing any existing highlight. */
    private fun forcePickRandom() {
        val items = _uiState.value.items
        if (items.isEmpty()) return
        val picked = items.random()
        val index = items.indexOf(picked)
        _uiState.update {
            it.copy(
                selectedFilter = null,
                highlightedItem = picked,
                scrollToIndex = if (index >= 0) index else null,
            )
        }
    }

    // loadNextPage kept for backward-compat (infinite scroll trigger still calls it)
    fun loadNextPage() { /* no-op: sync is now driven by syncAllPages */ }
}

// ── Logging helper ────────────────────────────────────────────────────────────

private fun log(tag: String, message: String, exception: Throwable? = null) {
    Logger.d(tag, message, exception)
}
