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

data class CollectionUiState(
    val items: List<CollectionItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
) {
    val hasMore: Boolean get() = currentPage < totalPages
    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null
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
}
