package com.joergi.jukebox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.storage.StorageKeys
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
    /** Non-null while the random-pick overlay is showing. */
    val randomItem: CollectionItem? = null,
    /**
     * 0f..1f progress of the background full-collection fetch.
     * null = not fetching (either done or not started yet).
     */
    val syncProgress: Float? = null,
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
        perPage = perPage,
    )

    private val _uiState = MutableStateFlow(CollectionUiState())
    val uiState: StateFlow<CollectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            restoreFromCache()
            syncAllPages()
        }
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

    /** Picks a random record from all loaded items and shows the overlay. */
    fun pickRandom() {
        val items = _uiState.value.items
        if (items.isEmpty()) return
        _uiState.update { it.copy(randomItem = items.random()) }
    }

    /** Dismisses the random-pick overlay. */
    fun dismissRandom() {
        _uiState.update { it.copy(randomItem = null) }
    }

    // loadNextPage kept for backward-compat (infinite scroll trigger still calls it)
    fun loadNextPage() { /* no-op: sync is now driven by syncAllPages */ }
}
