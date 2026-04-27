package com.joergi.jukebox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.joergi.jukebox.util.Logger
import com.joergi.jukebox.viewmodel.CollectionViewModel
import com.joergi.jukebox.viewmodel.DiscogsAuthViewModel
import com.joergi.jukebox.viewmodel.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

// ── Typed navigation destinations ─────────────────────────────────────────────

private const val TAG = "App"

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
    notificationInstanceId: Int? = null,
    isFromNotification: Boolean = false,
) {
    Logger.d(TAG, "App() composable called - isFromNotification=$isFromNotification, notificationInstanceId=$notificationInstanceId, notificationArtist='$notificationArtist', notificationTitle='$notificationTitle'")
    
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

        // Watch auth state to determine navigation
        val authState by authViewModel.state.collectAsState()

        // Auto-navigate to collection if user is already authenticated (skip HomeScreen)
        LaunchedEffect(authState) {
            if (authState is AuthState.Authenticated) {
                val username = (authState as AuthState.Authenticated).username
                // Navigate to collection, clearing the back stack so user can't go back to HomeScreen
                navController.navigate(CollectionRoute(username)) {
                    popUpTo(HomeRoute) { inclusive = true }
                }
            }
        }

        // If opened from notification and user is authenticated, navigate directly to collection
        LaunchedEffect(isFromNotification, authState) {
            if (isFromNotification && authState is AuthState.Authenticated) {
                val username = (authState as AuthState.Authenticated).username
                if (notificationInstanceId != null || 
                    (!notificationArtist.isNullOrEmpty() && !notificationTitle.isNullOrEmpty())) {
                    // Navigate to collection with the notification data
                    navController.navigate(CollectionRoute(username))
                }
            }
        }

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
                
                val uiState by collectionViewModel.uiState.collectAsState()

                // If opened from notification, highlight the album ONLY after collection is loaded
                LaunchedEffect(isFromNotification, notificationInstanceId, notificationArtist, notificationTitle, uiState.items.size) {
                    if (isFromNotification && uiState.items.isNotEmpty()) {
                        Logger.d(TAG, "Collection loaded with ${uiState.items.size} items, processing notification")
                        if (notificationInstanceId != null) {
                            // Prefer highlighting by instanceId for exact match
                            Logger.d(TAG, "Calling highlightByInstanceId($notificationInstanceId) with shouldPersist=true")
                            collectionViewModel.highlightByInstanceId(notificationInstanceId, shouldPersist = true)
                        } else if (!notificationArtist.isNullOrEmpty() && !notificationTitle.isNullOrEmpty()) {
                            // Fallback to artist/title search (less precise)
                            Logger.d(TAG, "Calling searchAndHighlightAlbum(artist='$notificationArtist', title='$notificationTitle') with shouldPersist=true")
                            collectionViewModel.searchAndHighlightAlbum(notificationArtist, notificationTitle, shouldPersist = true)
                        } else {
                            Logger.w(TAG, "isFromNotification=true but no valid notification data provided")
                        }
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
                    authViewModel = authViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onDisconnect = {
                        // Navigate back to HomeScreen after disconnect
                        navController.navigate(HomeRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
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
