package com.joergi.jukebox.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joergi.jukebox.viewmodel.AuthState
import com.joergi.jukebox.viewmodel.DiscogsAuthViewModel

/**
 * Root screen — mirrors Flutter's HomeScreen.
 *
 * Switches between unauthenticated, loading, error, and authenticated bodies
 * driven by [DiscogsAuthViewModel.state].
 */
@Composable
fun HomeScreen(
    viewModel: DiscogsAuthViewModel,
    onNavigateToCollection: (username: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jukebox") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        when (val s = state) {
            is AuthState.Unauthenticated -> UnauthenticatedBody(
                modifier = Modifier.padding(padding),
                onConnect = { viewModel.connectToDiscogs() },
            )

            is AuthState.Authenticating -> LoadingBody(
                modifier = Modifier.padding(padding),
            )

            is AuthState.Authenticated -> AuthenticatedBody(
                modifier = Modifier.padding(padding),
                username = s.username,
                onViewCollection = { onNavigateToCollection(s.username) },
                onDisconnect = { viewModel.disconnect() },
            )

            is AuthState.Error -> ErrorBody(
                modifier = Modifier.padding(padding),
                message = s.message,
                onRetry = { viewModel.connectToDiscogs() },
            )
        }
    }

    // Show PIN dialog whenever we are in the Authenticating state so the user
    // can paste the verifier after approving on the Discogs website.
    if (state is AuthState.Authenticating) {
        VerifierDialog(
            onSubmit = { pin -> viewModel.submitVerifier(pin) },
            onDismiss = { viewModel.disconnect() },
        )
    }
}

// ── Unauthenticated ───────────────────────────────────────────────────────────

@Composable
private fun UnauthenticatedBody(
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(80.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Jukebox", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect your Discogs account to view your collection.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onConnect,
        ) {
            Icon(Icons.Default.Link, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text("Connect to Discogs")
        }
    }
}

// ── Authenticated ─────────────────────────────────────────────────────────────

@Composable
private fun AuthenticatedBody(
    modifier: Modifier = Modifier,
    username: String,
    onViewCollection: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.height(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Connected as $username", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onViewCollection) {
            Icon(Icons.Default.LibraryMusic, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text("View My Collection")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect")
        }
    }
}

// ── Loading ───────────────────────────────────────────────────────────────────

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Connecting to Discogs…")
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorBody(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.height(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try Again") }
    }
}

// ── Verifier PIN dialog ───────────────────────────────────────────────────────

@Composable
private fun VerifierDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Discogs PIN") },
        text = {
            Column {
                Text(
                    "After authorising Jukebox on the Discogs website, " +
                        "enter the PIN code shown here.",
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN / Verifier code") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onSubmit(pin.text) }) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
