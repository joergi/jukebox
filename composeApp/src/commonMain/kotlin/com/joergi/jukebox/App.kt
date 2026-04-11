package com.joergi.jukebox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.storage.StorageKeys
import com.joergi.jukebox.ui.screen.CollectionScreen
import com.joergi.jukebox.ui.screen.HomeScreen
import com.joergi.jukebox.ui.screen.SettingsScreen
import com.joergi.jukebox.ui.theme.JukeboxTheme
import com.joergi.jukebox.viewmodel.CollectionViewModel
import com.joergi.jukebox.viewmodel.DiscogsAuthViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

// ── Typed navigation destinations ─────────────────────────────────────────────

@Serializable
internal data object HomeRoute

@Serializable
internal data class CollectionRoute(val username: String)

@Serializable
internal data class SettingsRoute(val username: String)

// ── Dark mode state (shared across all screens) ─────────────────────────────────

/** Global dark mode state that persists across screen navigation. */
private val globalDarkModeState = MutableStateFlow(true)  // Default to dark mode

/**
 * Root composable.
 *
 * [service], [storage], and [openUrl] are injected by each platform's entry
 * point so that platform-specific dependencies (HTTP engine, secure storage
 * backend, browser launcher) stay in the correct source set.
 */
@Composable
fun App(
    service: DiscogsService,
    storage: SecureStorage,
    openUrl: suspend (String) -> Unit,
    notificationArtist: String? = null,
    notificationTitle: String? = null,
    isFromNotification: Boolean = false,
) {
    val isDarkMode by globalDarkModeState.collectAsState()
    
    // Load dark mode preference from storage on first app load
    LaunchedEffect(Unit) {
        val saved = storage.read(StorageKeys.DARK_MODE)?.toBoolean() ?: true
        globalDarkModeState.value = saved
    }
    
    JukeboxTheme(darkTheme = isDarkMode) {
        val navController = rememberNavController()

        val authViewModel = viewModel {
            DiscogsAuthViewModel(
                service = service,
                storage = storage,
                openUrl = openUrl,
            )
        }

        // Restore any previously saved session on first composition
        LaunchedEffect(Unit) { authViewModel.restoreSession() }

        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    viewModel = authViewModel,
                    onNavigateToCollection = { username ->
                        navController.navigate(CollectionRoute(username))
                    },
                )
            }

            composable<CollectionRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<CollectionRoute>()

                val collectionViewModel = remember(route.username) {
                    CollectionViewModel(
                        service = service,
                        username = route.username,
                        storage = storage,
                    )
                }

                // If opened from notification, search for and highlight the album
                LaunchedEffect(isFromNotification, notificationArtist, notificationTitle) {
                    if (isFromNotification && !notificationArtist.isNullOrEmpty() && !notificationTitle.isNullOrEmpty()) {
                        collectionViewModel.searchAndHighlightAlbum(notificationArtist, notificationTitle)
                    }
                }

                CollectionScreen(
                    viewModel = collectionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSettings = {
                        navController.navigate(SettingsRoute(route.username))
                    },
                )
            }

            composable<SettingsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SettingsRoute>()

                // Reuse the same CollectionViewModel instance so interval changes
                // take effect immediately in the collection screen.
                val collectionViewModel = remember(route.username) {
                    CollectionViewModel(
                        service = service,
                        username = route.username,
                        storage = storage,
                    )
                }

                SettingsScreen(
                    viewModel = collectionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Updates the global dark mode state and notifies all observers.
 * Called by SettingsScreen when user toggles the dark mode switch.
 */
fun updateGlobalDarkMode(isDark: Boolean) {
    globalDarkModeState.value = isDark
}
