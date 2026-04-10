package com.joergi.jukebox.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.joergi.jukebox.service.DiscogsService
import com.joergi.jukebox.storage.SecureStorage
import com.joergi.jukebox.storage.StorageKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In-memory [DataStore]<[Preferences]> for tests.
 *
 * All reads and writes happen synchronously on the calling coroutine's
 * context — no file I/O, no [Dispatchers.IO] involvement, no wall-clock delays.
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val _state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = _state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val newValue = transform(_state.value)
        _state.value = newValue
        return newValue
    }
}

/**
 * Unit tests for [DiscogsAuthViewModel].
 *
 * Uses Turbine to observe [StateFlow] emissions.  Ktor's MockEngine may dispatch
 * responses on a real background thread (Dispatchers.IO); Turbine's awaitItem()
 * handles that via a wall-clock timeout rather than virtual time, avoiding
 * the flakiness we saw when using advanceUntilIdle() alone.
 *
 * For tests that also need to observe [openUrl] being called (which happens on
 * the IO-thread continuation, after the HTTP response), we use a [Channel] so
 * that [receive()] suspends with a wall-clock timeout until the URL arrives.
 */
class DiscogsAuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var storage: SecureStorage
    /** Rendezvous channel — each openUrl() call sends the URL here. */
    private val openedUrlChannel = Channel<String>(Channel.UNLIMITED)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        storage = SecureStorage(InMemoryPreferencesDataStore())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        openedUrlChannel.close()
    }

    private fun formHeaders() = headersOf(
        "Content-Type" to listOf("application/x-www-form-urlencoded"),
    )

    private fun jsonHeaders() = headersOf(
        "Content-Type" to listOf(ContentType.Application.Json.toString()),
    )

    private fun makeViewModel(engine: MockEngine): DiscogsAuthViewModel {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = DiscogsService("key", "secret", client)
        return DiscogsAuthViewModel(
            service = service,
            storage = storage,
            openUrl = { url -> openedUrlChannel.send(url) },
        )
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is Unauthenticated`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)
        vm.state.value.shouldBeInstanceOf<AuthState.Unauthenticated>()
    }

    // ── restoreSession ────────────────────────────────────────────────────────

    @Test
    fun `restoreSession transitions to Authenticated when tokens exist`() = runTest {
        storage.write(StorageKeys.ACCESS_TOKEN, "saved_token")
        storage.write(StorageKeys.ACCESS_TOKEN_SECRET, "saved_secret")
        storage.write(StorageKeys.USERNAME, "saved_user")

        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()
            vm.restoreSession()
            awaitItem().shouldBeInstanceOf<AuthState.Authenticated>().username shouldBe "saved_user"
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoreSession stays Unauthenticated when no tokens stored`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)

        vm.restoreSession()

        // No state change expected — stays Unauthenticated
        vm.state.value.shouldBeInstanceOf<AuthState.Unauthenticated>()
    }

    // ── connectToDiscogs / submitVerifier ──────────────────────────────────────

    @Test
    fun `connectToDiscogs transitions to Authenticating and opens URL`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "oauth_token=req_tok&oauth_token_secret=req_sec",
                status = HttpStatusCode.OK,
                headers = formHeaders(),
            )
        }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()

            vm.connectToDiscogs()

            // Authenticating is emitted synchronously (before the HTTP call).
            awaitItem().shouldBeInstanceOf<AuthState.Authenticating>()

            // openUrl() is called by the viewModelScope coroutine after the HTTP call
            // completes on Dispatchers.IO.  openedUrlChannel.receive() suspends here
            // with a wall-clock timeout (Turbine's default 3 s) until the IO thread
            // finishes and the URL is sent — far more reliable than advanceUntilIdle().
            val url = openedUrlChannel.receive()
            assertEquals("https://www.discogs.com/oauth/authorize?oauth_token=req_tok", url)

            cancelAndIgnoreRemainingEvents()
        }

        vm.state.value.shouldBeInstanceOf<AuthState.Authenticating>()
    }

    @Test
    fun `submitVerifier transitions to Authenticated on success`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            when (callCount) {
                1 -> respond(
                    "oauth_token=req_tok&oauth_token_secret=req_sec",
                    HttpStatusCode.OK,
                    formHeaders(),
                )
                2 -> respond(
                    "oauth_token=acc_tok&oauth_token_secret=acc_sec",
                    HttpStatusCode.OK,
                    formHeaders(),
                )
                else -> respond(
                    """{"username":"the_user","id":1,"resource_url":""}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
            }
        }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()

            vm.connectToDiscogs()
            // Authenticating emitted before the HTTP call.
            awaitItem().shouldBeInstanceOf<AuthState.Authenticating>()

            // Wait for connectToDiscogs to fully complete: openUrl() is called after
            // startOAuthFlow() sets pendingRequestToken on the service.
            // By awaiting the URL from the channel we know the IO thread has finished
            // and pendingRequestToken is set — safe to call submitVerifier now.
            openedUrlChannel.receive()

            vm.submitVerifier("123456")
            // submitVerifier sets Authenticating again, but StateFlow deduplicates
            // identical values — we may or may not see it.  Drain to final state.
            val states = mutableListOf(awaitItem())
            while (states.last() !is AuthState.Authenticated && states.last() !is AuthState.Error) {
                states.add(awaitItem())
            }
            states.last().shouldBeInstanceOf<AuthState.Authenticated>().username shouldBe "the_user"

            cancelAndIgnoreRemainingEvents()
        }

        storage.read(StorageKeys.ACCESS_TOKEN) shouldBe "acc_tok"
        storage.read(StorageKeys.USERNAME) shouldBe "the_user"
    }

    @Test
    fun `submitVerifier with blank pin emits Error`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()

            vm.submitVerifier("   ")

            awaitItem().shouldBeInstanceOf<AuthState.Error>()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connectToDiscogs emits Error when request token fetch fails`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.ServiceUnavailable) }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()

            vm.connectToDiscogs()

            // Authenticating emitted first, then Error after HTTP failure
            val states = mutableListOf(awaitItem())
            while (states.last() !is AuthState.Error && states.last() !is AuthState.Authenticated) {
                states.add(awaitItem())
            }
            states.last().shouldBeInstanceOf<AuthState.Error>()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── disconnect ────────────────────────────────────────────────────────────

    @Test
    fun `disconnect clears storage and returns to Unauthenticated`() = runTest {
        storage.write(StorageKeys.ACCESS_TOKEN, "tok")
        storage.write(StorageKeys.ACCESS_TOKEN_SECRET, "sec")
        storage.write(StorageKeys.USERNAME, "user")

        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()

            vm.restoreSession()
            awaitItem().shouldBeInstanceOf<AuthState.Authenticated>()

            vm.disconnect()
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()

            cancelAndIgnoreRemainingEvents()
        }

        storage.read(StorageKeys.ACCESS_TOKEN) shouldBe null
        storage.read(StorageKeys.USERNAME) shouldBe null
    }
}
