package com.joergi.jukebox.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [DiscogsService] using Ktor's [MockEngine].
 *
 * The mock engine lets us exercise all HTTP paths without hitting the network.
 */
class DiscogsServiceTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun jsonHeaders() = headersOf(
        "Content-Type" to listOf(ContentType.Application.Json.toString()),
    )

    private fun formHeaders() = headersOf(
        "Content-Type" to listOf("application/x-www-form-urlencoded"),
    )

    private fun buildClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun makeService(engine: MockEngine) = DiscogsService(
        consumerKey = "test_key",
        consumerSecret = "test_secret",
        httpClient = buildClient(engine),
    )

    // ── startOAuthFlow ────────────────────────────────────────────────────────

    @Test
    fun `startOAuthFlow returns authorise URL on success`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "oauth_token=req_token&oauth_token_secret=req_secret&oauth_callback_confirmed=true",
                status = HttpStatusCode.OK,
                headers = formHeaders(),
            )
        }

        val url = makeService(engine).startOAuthFlow()

        url shouldStartWith "https://www.discogs.com/oauth/authorize"
        url shouldContain "oauth_token=req_token"
    }

    @Test
    fun `startOAuthFlow throws DiscogsException on HTTP error`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.Unauthorized)
        }

        assertFailsWith<DiscogsException> {
            makeService(engine).startOAuthFlow()
        }
    }

    @Test
    fun `startOAuthFlow throws DiscogsException when oauth_token missing from response`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "oauth_token_secret=secret_only",
                status = HttpStatusCode.OK,
                headers = formHeaders(),
            )
        }

        assertFailsWith<DiscogsException> {
            makeService(engine).startOAuthFlow()
        }
    }

    // ── completeOAuthFlow ─────────────────────────────────────────────────────

    @Test
    fun `completeOAuthFlow returns access tokens on success`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ ->
            requestCount++
            when (requestCount) {
                1 -> respond(
                    content = "oauth_token=req_tok&oauth_token_secret=req_sec",
                    status = HttpStatusCode.OK,
                    headers = formHeaders(),
                )
                else -> respond(
                    content = "oauth_token=access_tok&oauth_token_secret=access_sec",
                    status = HttpStatusCode.OK,
                    headers = formHeaders(),
                )
            }
        }

        val service = makeService(engine)
        service.startOAuthFlow()
        val tokens = service.completeOAuthFlow("123456")

        tokens.token shouldBe "access_tok"
        tokens.secret shouldBe "access_sec"
    }

    @Test
    fun `completeOAuthFlow throws IllegalStateException when called before startOAuthFlow`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }

        assertFailsWith<IllegalStateException> {
            makeService(engine).completeOAuthFlow("pin")
        }
    }

    @Test
    fun `completeOAuthFlow throws DiscogsException on HTTP error`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ ->
            requestCount++
            when (requestCount) {
                1 -> respond(
                    content = "oauth_token=req_tok&oauth_token_secret=req_sec",
                    status = HttpStatusCode.OK,
                    headers = formHeaders(),
                )
                else -> respondError(HttpStatusCode.Unauthorized)
            }
        }

        val service = makeService(engine)
        service.startOAuthFlow()

        assertFailsWith<DiscogsException> {
            service.completeOAuthFlow("bad_pin")
        }
    }

    // ── getIdentity ───────────────────────────────────────────────────────────

    @Test
    fun `getIdentity returns username on success`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"username":"vinyl_collector","id":1,"resource_url":""}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        service.getIdentity() shouldBe "vinyl_collector"
    }

    @Test
    fun `getIdentity throws IllegalStateException when not authenticated`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.Unauthorized) }

        assertFailsWith<IllegalStateException> {
            makeService(engine).getIdentity()
        }
    }

    // ── getCollection ─────────────────────────────────────────────────────────

    @Test
    fun `getCollection returns parsed items and pagination`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """
                    {
                      "pagination": {"pages": 2, "items": 30},
                      "releases": [
                        {
                          "instance_id": 1,
                          "id": 10,
                          "basic_information": {
                            "title": "Nevermind",
                            "artists": [{"name": "Nirvana"}],
                            "formats": [{"name": "Vinyl", "descriptions": []}],
                            "thumb": "https://img.discogs.com/nvm.jpg",
                            "year": 1991,
                            "labels": [{"name": "DGC"}]
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        val result = service.getCollection("testuser", page = 1)

        result.totalPages shouldBe 2
        result.totalItems shouldBe 30
        result.items.size shouldBe 1
        result.items.first().title shouldBe "Nevermind"
        result.items.first().artists shouldBe listOf("Nirvana")
    }

    @Test
    fun `getCollection returns empty list when releases is empty`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"pagination": {"pages": 0, "items": 0}, "releases": []}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        val result = service.getCollection("testuser")

        result.items.size shouldBe 0
        result.totalPages shouldBe 0
        result.totalItems shouldBe 0
    }

    @Test
    fun `getCollection throws DiscogsException on API error`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.TooManyRequests)
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        assertFailsWith<DiscogsException> {
            service.getCollection("testuser")
        }
    }

    // ── getCollection with format descriptions ────────────────────────────────

    @Test
    fun `getCollection displays format descriptions when available`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """
                    {
                      "pagination": {"pages": 1, "items": 1},
                      "releases": [
                        {
                          "instance_id": 1,
                          "id": 10,
                          "basic_information": {
                            "title": "Abbey Road",
                            "artists": [{"name": "The Beatles"}],
                            "formats": [{"name": "Vinyl", "descriptions": ["LP", "Album"]}],
                            "thumb": "https://img.discogs.com/abbey.jpg",
                            "year": 1969,
                            "labels": [{"name": "Apple"}]
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        val result = service.getCollection("testuser", page = 1)

        result.items.size shouldBe 1
        result.items.first().formats.first() shouldBe "Vinyl (LP, Album)"
    }

    @Test
    fun `getCollection handles null format descriptions gracefully`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """
                    {
                      "pagination": {"pages": 1, "items": 1},
                      "releases": [
                        {
                          "instance_id": 1,
                          "id": 10,
                          "basic_information": {
                            "title": "Dark Side",
                            "artists": [{"name": "Pink Floyd"}],
                            "formats": [{"name": "Vinyl", "descriptions": null}],
                            "thumb": "https://img.discogs.com/dark.jpg",
                            "year": 1973,
                            "labels": [{"name": "Harvest"}]
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        val result = service.getCollection("testuser", page = 1)

        result.items.size shouldBe 1
        result.items.first().formats.first() shouldBe "Vinyl"
    }

    @Test
    fun `getCollection handles unescaped special characters in record titles with lenient JSON parsing`() = runTest {
        // This JSON has an unescaped apostrophe in "Rock'n'Roll Genossen"
        // Standard JSON parsing would fail here, but lenient mode should handle it
        val engine = MockEngine { _ ->
            respond(
                content = """
                    {
                      "pagination": {"pages": 1, "items": 1},
                      "releases": [
                        {
                          "instance_id": 1,
                          "id": 123,
                          "basic_information": {
                            "title": "Rock'n'Roll Genossen",
                            "artists": [{"name": "Various"}],
                            "formats": [{"name": "Vinyl", "descriptions": ["LP"]}],
                            "thumb": "https://img.discogs.com/rock.jpg",
                            "year": 1970,
                            "labels": [{"name": "Krautrock"}]
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }

        val service = makeService(engine)
        service.setCredentials("tok", "sec")

        val result = service.getCollection("testuser", page = 1)

        result.items.size shouldBe 1
        result.items.first().title shouldBe "Rock'n'Roll Genossen"
        result.items.first().formats.first() shouldBe "Vinyl (LP)"
    }

    // ── clearCredentials ──────────────────────────────────────────────────────

    @Test
    fun `clearCredentials makes isAuthenticated false`() {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.BadRequest) }
        val service = makeService(engine)

        service.setCredentials("token", "secret")
        service.isAuthenticated shouldBe true

        service.clearCredentials()
        service.isAuthenticated shouldBe false
    }
}
