package com.joergi.jukebox.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joergi.jukebox.model.SyncState
import com.joergi.jukebox.updateGlobalDarkMode
import com.joergi.jukebox.viewmodel.CollectionViewModel
import com.joergi.jukebox.viewmodel.DiscogsAuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CollectionViewModel,
    authViewModel: DiscogsAuthViewModel,
    onNavigateBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // Dark mode toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dark mode",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (uiState.isDarkMode) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.isDarkMode,
                        onCheckedChange = { 
                            viewModel.setDarkMode(it)
                            updateGlobalDarkMode(it)
                        },
                    )
                }
            }

            item {
                // Start on boot toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start on device boot",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (uiState.startOnBoot) "Reminders will start automatically when device boots" 
                                  else "Manual start required after device boot",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.startOnBoot,
                        onCheckedChange = { viewModel.setStartOnBoot(it) },
                    )
                }
            }

            item { HorizontalDivider() }

            item {
                // Resync collection button
                val syncState = uiState.syncState
                val isSyncing = syncState is SyncState.FetchingNewest ||
                    syncState is SyncState.Validating ||
                    syncState is SyncState.FullResync
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Resync collection",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (isSyncing) "Syncing…" else "Manually trigger a collection sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { viewModel.performManualSync() },
                        enabled = uiState.items.isNotEmpty() && !isSyncing,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Sync")
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                Text(
                    text = "Selected records history",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "${uiState.selectedRecordsHistory.size} record(s) selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(uiState.selectedRecordsHistory) { record ->
                CollectionItemRow(item = record)
            }

            item {
                if (uiState.selectedRecordsHistory.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { viewModel.clearSelectedRecordsHistory() }) {
                            Text("Clear history")
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            item {
                // Disconnect account button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    TextButton(
                        onClick = {
                            authViewModel.disconnect()
                            onDisconnect()
                        }
                    ) {
                        Text(
                            text = "Disconnect from Discogs",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
