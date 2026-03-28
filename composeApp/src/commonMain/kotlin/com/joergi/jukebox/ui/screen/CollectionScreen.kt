package com.joergi.jukebox.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.joergi.jukebox.model.CollectionItem
import com.joergi.jukebox.viewmodel.CollectionUiState
import com.joergi.jukebox.viewmodel.CollectionViewModel
import com.joergi.jukebox.viewmodel.LetterFilter

/**
 * Shows the authenticated user's Discogs vinyl collection.
 *
 * Mirrors Flutter's CollectionScreen with pull-to-refresh and infinite scroll.
 * The alphabet bar at the bottom lets the user filter by first letter of the artist name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    viewModel: CollectionViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Trigger next-page load when the user scrolls near the bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 5 // start loading when 5 items from end
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadNextPage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My Collection")
                        if (uiState.totalItems > 0) {
                            Text(
                                "${uiState.totalItems} releases",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        bottomBar = {
            AlphabetBar(
                selectedFilter = uiState.selectedFilter,
                onFilterSelected = { viewModel.filterLetter(it) },
            )
        },
        // Capture keyboard events for letter filtering at the Scaffold level
        modifier = Modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when {
                event.key == Key.Escape -> { viewModel.filterLetter(null); true }
                event.key == Key.Zero || event.key == Key.NumPad0 -> {
                    viewModel.filterLetter(LetterFilter.NUMBERS); true
                }
                else -> {
                    val char = keyToLetter(event.key, event.isShiftPressed)
                    if (char != null) { viewModel.filterLetter(char); true } else false
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading && uiState.items.isEmpty(),
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val displayItems = uiState.filteredItems
            when {
                uiState.items.isEmpty() && uiState.isLoading -> LoadingPlaceholder()
                uiState.items.isEmpty() && uiState.error != null -> CollectionError(
                    message = uiState.error!!,
                    onRetry = { viewModel.refresh() },
                )
                uiState.isEmpty -> EmptyCollection()
                displayItems.isEmpty() && uiState.selectedFilter != null && uiState.isLoading -> LoadingPlaceholder()
                displayItems.isEmpty() && uiState.selectedFilter != null -> EmptyFilterResult(uiState.selectedFilter)
                else -> CollectionList(
                    uiState = uiState,
                    displayItems = displayItems,
                    listState = listState,
                )
            }
        }
    }
}

// ── Alphabet bar ──────────────────────────────────────────────────────────────

private val LETTERS = ('A'..'Z').toList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlphabetBar(
    selectedFilter: Any?,
    onFilterSelected: (Any?) -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            // All-button (clear filter)
            FilterChip(
                label = "All",
                selected = selectedFilter == null,
                onClick = { onFilterSelected(null) },
            )
            // A–Z
            LETTERS.forEach { letter ->
                FilterChip(
                    label = letter.toString(),
                    selected = selectedFilter == letter,
                    onClick = {
                        onFilterSelected(if (selectedFilter == letter) null else letter)
                    },
                )
            }
            // Numbers (#)
            FilterChip(
                label = "#",
                selected = selectedFilter == LetterFilter.NUMBERS,
                onClick = {
                    onFilterSelected(
                        if (selectedFilter == LetterFilter.NUMBERS) null else LetterFilter.NUMBERS
                    )
                },
            )
            // Special characters (?)
            FilterChip(
                label = "?",
                selected = selectedFilter == LetterFilter.SPECIAL,
                onClick = {
                    onFilterSelected(
                        if (selectedFilter == LetterFilter.SPECIAL) null else LetterFilter.SPECIAL
                    )
                },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .padding(2.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = fg,
        )
    }
}

// Maps a Compose Key to a letter Char (A–Z). Returns null for non-letter keys.
private fun keyToLetter(key: Key, shiftPressed: Boolean): Char? {
    // Key.A through Key.Z have sequential key codes in the Android/Compose key set
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J,
        Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T,
        Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    val index = letters.indexOf(key)
    return if (index >= 0) ('A' + index).toChar() else null
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun CollectionList(
    uiState: CollectionUiState,
    displayItems: List<CollectionItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(
            items = displayItems,
            key = { _, item -> item.instanceId },
        ) { index, item ->
            CollectionItemRow(item = item)
            if (index < displayItems.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        // Footer: loading spinner or error for subsequent pages (only when unfiltered)
        if (uiState.isLoading && uiState.items.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
        if (uiState.error != null && uiState.items.isNotEmpty()) {
            item {
                Text(
                    text = uiState.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

// ── Single row ────────────────────────────────────────────────────────────────

@Composable
private fun CollectionItemRow(item: CollectionItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(thumbUrl = item.thumb)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.artists.isNotEmpty()) {
                Text(
                    text = item.artists.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.year != null) {
                    Text(
                        text = "${item.year}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                if (item.label != null) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.formats.isNotEmpty()) {
                Text(
                    text = item.formats.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ── Thumbnail ─────────────────────────────────────────────────────────────────

@Composable
private fun Thumbnail(thumbUrl: String?) {
    val context = LocalPlatformContext.current
    Box(
        modifier = Modifier.size(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (painter.state.collectAsState().value) {
                    is AsyncImagePainter.State.Error, is AsyncImagePainter.State.Empty -> {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                    }
                    else -> SubcomposeAsyncImageContent()
                }
            }
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

// ── Empty / Error / Loading states ────────────────────────────────────────────

@Composable
private fun LoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyCollection() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(16.dp))
        Text("Your collection is empty.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EmptyFilterResult(filter: Any?) {
    val label = when (filter) {
        is Char -> filter.toString()
        LetterFilter.NUMBERS -> "#"
        LetterFilter.SPECIAL -> "?"
        else -> ""
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No artists starting with \"$label\"",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun CollectionError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onRetry) { Text("Retry") }
    }
}
