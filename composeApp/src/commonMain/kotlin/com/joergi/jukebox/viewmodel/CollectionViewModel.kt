package com.joergi.jukebox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.service.DiscogsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/**
 * Manages paginated loading of a user's Discogs collection.
 *
 * Mirrors the state logic in the Flutter [_CollectionScreenState].
 */
class CollectionViewModel(
    private val service: DiscogsService,
    private val username: String,
    private val perPage: Int = 25,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionUiState())
    val uiState: StateFlow<CollectionUiState> = _uiState.asStateFlow()

    init {
        loadNextPage()
    }

    fun loadNextPage() {
        val current = _uiState.value
        if (current.isLoading || !current.hasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatching {
                service.getCollection(
                    username = username,
                    page = current.currentPage + 1,
                    perPage = perPage,
                )
            }
                .onSuccess { result ->
                    _uiState.update { state ->
                        state.copy(
                            items = state.items + result.items,
                            currentPage = state.currentPage + 1,
                            totalPages = result.totalPages,
                            totalItems = result.totalItems,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Failed to load collection: ${e.message}") }
                }
        }
    }

    fun refresh() {
        _uiState.update { CollectionUiState() }
        loadNextPage()
    }

    /** Pass a [Char] (A-Z), [LetterFilter.NUMBERS], [LetterFilter.SPECIAL], or null to show all. */
    fun filterLetter(filter: Any?) {
        _uiState.update { it.copy(selectedFilter = filter) }
        // When a filter is active we need the full collection in memory so the
        // client-side filter can show every matching artist.  Load all remaining
        // pages automatically instead of waiting for the user to scroll.
        if (filter != null) {
            loadAllRemainingPages()
        }
    }

    /**
     * Fetches every page that hasn't been loaded yet, one after another.
     * No-op if all pages are already in memory or a load is already running.
     */
    private fun loadAllRemainingPages() {
        viewModelScope.launch {
            while (_uiState.value.hasMore && !_uiState.value.isLoading) {
                val current = _uiState.value
                if (!current.hasMore) break

                _uiState.update { it.copy(isLoading = true, error = null) }

                runCatching {
                    service.getCollection(
                        username = username,
                        page = current.currentPage + 1,
                        perPage = perPage,
                    )
                }
                    .onSuccess { result ->
                        _uiState.update { state ->
                            state.copy(
                                items = state.items + result.items,
                                currentPage = state.currentPage + 1,
                                totalPages = result.totalPages,
                                totalItems = result.totalItems,
                                isLoading = false,
                                error = null,
                            )
                        }
                    }
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Failed to load collection: ${e.message}",
                            )
                        }
                        break
                    }
            }
        }
    }
}
