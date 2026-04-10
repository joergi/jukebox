package com.joergi.jukebox.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.joergi.jukebox.service.DiscogsException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for [DiscogsAuthViewModel].
 *
 * Uses a Ktor MockEngine for HTTP and a DataStore-backed [SecureStorage] with a
 * temp folder so storage is truly exercised without side-effects between tests.
 */
class DiscogsAuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var storage: SecureStorage
    private val openedUrls = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        openedUrls.clear()
        storage = SecureStorage(
            PreferenceDataStoreFactory.createWithPath(
                scope = CoroutineScope(Dispatchers.IO),
                produceFile = {
                    tmpFolder.newFile("auth_prefs.preferences_pb").absolutePath.toPath()
                },
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
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
            openUrl = { url -> openedUrls.add(url) },
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
        }
    }

    @Test
    fun `restoreSession stays Unauthenticated when no tokens stored`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)

        vm.restoreSession()
        // give coroutine a chance to run
        kotlinx.coroutines.delay(100)
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
            awaitItem().shouldBeInstanceOf<AuthState.Authenticating>()
        }

        openedUrls.size shouldBe 1
        openedUrls.first() shouldBe "https://www.discogs.com/oauth/authorize?oauth_token=req_tok"
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
            awaitItem() // Unauthenticated
            vm.connectToDiscogs()
            awaitItem() // Authenticating
            vm.submitVerifier("123456")
            awaitItem() // Authenticating again
            val auth = awaitItem()
            auth.shouldBeInstanceOf<AuthState.Authenticated>().username shouldBe "the_user"
        }

        // Tokens should now be persisted
        storage.read(StorageKeys.ACCESS_TOKEN) shouldBe "acc_tok"
        storage.read(StorageKeys.USERNAME) shouldBe "the_user"
    }

    @Test
    fun `submitVerifier with blank pin emits Error`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem() // Unauthenticated
            vm.submitVerifier("   ")
            awaitItem().shouldBeInstanceOf<AuthState.Error>()
        }
    }

    @Test
    fun `connectToDiscogs emits Error when request token fetch fails`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.ServiceUnavailable) }
        val vm = makeViewModel(engine)

        vm.state.test {
            awaitItem() // Unauthenticated
            vm.connectToDiscogs()
            awaitItem() // Authenticating
            awaitItem().shouldBeInstanceOf<AuthState.Error>()
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

        vm.restoreSession()
        kotlinx.coroutines.delay(100)
        vm.state.value.shouldBeInstanceOf<AuthState.Authenticated>()

        vm.state.test {
            awaitItem() // Authenticated
            vm.disconnect()
            awaitItem().shouldBeInstanceOf<AuthState.Unauthenticated>()
        }

        storage.read(StorageKeys.ACCESS_TOKEN) shouldBe null
        storage.read(StorageKeys.USERNAME) shouldBe null
    }
}
