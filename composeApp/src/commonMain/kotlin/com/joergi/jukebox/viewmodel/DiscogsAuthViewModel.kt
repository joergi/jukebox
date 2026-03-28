package com.joergi.jukebox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.storage.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── State ─────────────────────────────────────────────────────────────────────

sealed interface AuthState {
    data object Unauthenticated : AuthState
    data object Authenticating : AuthState
    data class Authenticated(val username: String) : AuthState
    data class Error(val message: String) : AuthState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Manages the Discogs OAuth lifecycle.
 *
 * Mirrors the Flutter [DiscogsAuthProvider] but uses [StateFlow] instead of
 * [ChangeNotifier] and coroutines instead of async/await.
 *
 * [openUrl] is injected as a lambda so each platform can supply its own
 * browser-opening implementation (and tests can supply a fake).
 */
class DiscogsAuthViewModel(
    private val service: DiscogsService,
    private val storage: SecureStorage,
    private val openUrl: suspend (String) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isAuthenticated: Boolean
        get() = _state.value is AuthState.Authenticated

    // ── Session restore ───────────────────────────────────────────────────────

    /** Called once on startup. Restores a persisted session if tokens exist. */
    fun restoreSession() {
        viewModelScope.launch {
            val token = storage.read(StorageKeys.ACCESS_TOKEN) ?: return@launch
            val secret = storage.read(StorageKeys.ACCESS_TOKEN_SECRET) ?: return@launch
            val username = storage.read(StorageKeys.USERNAME) ?: return@launch

            service.setCredentials(token, secret)
            _state.update { AuthState.Authenticated(username) }
        }
    }

    // ── OAuth Step 1 ──────────────────────────────────────────────────────────

    /**
     * Starts the OAuth flow: fetches a request token and opens the Discogs
     * authorization page in the system browser.
     *
     * The state becomes [AuthState.Authenticating] while the user is on the
     * Discogs page. The caller must subsequently call [submitVerifier] with
     * the PIN shown on that page.
     */
    fun connectToDiscogs() {
        viewModelScope.launch {
            _state.update { AuthState.Authenticating }
            runCatching { service.startOAuthFlow() }
                .onSuccess { url ->
                    runCatching { openUrl(url) }
                        .onFailure { e -> _state.update { AuthState.Error("Could not open browser: ${e.message}") } }
                }
                .onFailure { e -> _state.update { AuthState.Error("Failed to start login: ${e.message}") } }
        }
    }

    // ── OAuth Step 2 ──────────────────────────────────────────────────────────

    /** Called after the user pastes the PIN from the Discogs authorisation page. */
    fun submitVerifier(verifier: String) {
        if (verifier.isBlank()) {
            _state.update { AuthState.Error("Please enter the PIN code.") }
            return
        }
        viewModelScope.launch {
            _state.update { AuthState.Authenticating }
            runCatching {
                val tokens = service.completeOAuthFlow(verifier.trim())
                val username = service.getIdentity()

                storage.write(StorageKeys.ACCESS_TOKEN, tokens.token)
                storage.write(StorageKeys.ACCESS_TOKEN_SECRET, tokens.secret)
                storage.write(StorageKeys.USERNAME, username)

                username
            }
                .onSuccess { username -> _state.update { AuthState.Authenticated(username) } }
                .onFailure { e -> _state.update { AuthState.Error("Login failed: ${e.message}") } }
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    /** Clears all persisted tokens and returns to unauthenticated state. */
    fun disconnect() {
        viewModelScope.launch {
            service.clearCredentials()
            storage.delete(StorageKeys.ACCESS_TOKEN)
            storage.delete(StorageKeys.ACCESS_TOKEN_SECRET)
            storage.delete(StorageKeys.USERNAME)
            _state.update { AuthState.Unauthenticated }
        }
    }
}
