package com.joergi.jukebox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.ui.screen.CollectionScreen
import com.joergi.jukebox.ui.screen.HomeScreen
import com.joergi.jukebox.ui.theme.JukeboxTheme
import com.joergi.jukebox.viewmodel.CollectionViewModel
import com.joergi.jukebox.viewmodel.DiscogsAuthViewModel

// ── Navigation destinations ───────────────────────────────────────────────────

private object Routes {
    const val HOME = "home"
    const val COLLECTION = "collection/{username}"

    fun collection(username: String) = "collection/$username"
}

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

        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = authViewModel,
                    onNavigateToCollection = { username ->
                        navController.navigate(Routes.collection(username))
                    },
                )
            }

            composable(Routes.COLLECTION) { backStackEntry ->
                val username = backStackEntry.arguments?.getString("username") ?: return@composable

                val collectionViewModel = remember(username) {
                    CollectionViewModel(service = service, username = username)
                }

                CollectionScreen(
                    viewModel = collectionViewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
