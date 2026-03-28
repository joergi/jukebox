package com.joergi.jukebox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.ui.screen.CollectionScreen
import com.joergi.jukebox.ui.screen.HomeScreen
import com.joergi.jukebox.ui.theme.JukeboxTheme
import com.joergi.jukebox.viewmodel.CollectionViewModel
import com.joergi.jukebox.viewmodel.DiscogsAuthViewModel
import kotlinx.serialization.Serializable

// ── Typed navigation destinations ─────────────────────────────────────────────

@Serializable
internal data object HomeRoute

@Serializable
internal data class CollectionRoute(val username: String)

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
) {
    JukeboxTheme {
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

                CollectionScreen(
                    viewModel = collectionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
