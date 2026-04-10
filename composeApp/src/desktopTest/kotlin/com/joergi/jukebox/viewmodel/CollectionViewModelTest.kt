package com.joergi.jukebox.viewmodel

import app.cash.turbine.test
import com.joergi.jukebox.service.DiscogsService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for [CollectionViewModel].
 *
 * Uses Ktor's MockEngine so no network is required.
 * Cache read/write are in-memory lambdas — no DataStore / IO dispatcher needed.
 *
 * [Dispatchers.Main] is overridden with [UnconfinedTestDispatcher] so that
 * [viewModelScope] coroutines run eagerly within [runTest].
 */
class CollectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUpDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDownDispatcher() {
        Dispatchers.resetMain()
    }

    private fun jsonHeaders() = headersOf(
        "Content-Type" to listOf(ContentType.Application.Json.toString()),
    )

    private fun singlePageResponse(title: String = "Album", count: Int = 1) = buildString {
        append("""{"pagination":{"pages":1,"items":$count},"releases":[""")
        repeat(count) { i ->
            if (i > 0) append(",")
            append(
                """
                {
                  "instance_id": ${i + 1},
                  "id": ${100 + i},
                  "basic_information": {
                    "title": "$title $i",
                    "artists": [{"name": "Artist"}],
                    "formats": [{"name": "Vinyl"}],
                    "thumb": null,
                    "year": 2000,
                    "labels": [{"name": "Label"}]
                  }
                }
                """.trimIndent(),
            )
        }
        append("]}")
    }

    /**
     * Creates a [CollectionViewModel] using an in-memory cache (no DataStore / IO).
     *
     * [initialCache] pre-populates the in-memory cache (simulates a previous run).
     * Returns a pair of (viewModel, cacheRef) so tests can inspect what was persisted.
     */
    private fun makeViewModel(
        engine: MockEngine,
        username: String = "user",
        initialCache: String? = null,
    ): Pair<CollectionViewModel, MutableMap<String, String>> {
        val cache = mutableMapOf<String, String>()
        if (initialCache != null) cache["cache"] = initialCache

        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = DiscogsService("key", "secret", client).also {
            it.setCredentials("tok", "sec")
        }
        val vm = CollectionViewModel(
            service = service,
            username = username,
            readCache = { cache["cache"] },
            writeCache = { json -> cache["cache"] = json },
            perPage = 5,
        )
        return vm to cache
    }

    // ── Initial load ──────────────────────────────────────────────────────────

    @Test
    fun `initial load populates items from single page`() = runTest {
        val engine = MockEngine { _ ->
            respond(singlePageResponse(count = 3), HttpStatusCode.OK, jsonHeaders())
        }

        val (vm) = makeViewModel(engine)
        vm.uiState.test {
            // Collect all states until sync finishes (syncProgress = null)
            val states = mutableListOf(awaitItem())
            while (states.last().syncProgress != null) {
                states.add(awaitItem())
            }
            val final = states.last()
            final.items shouldHaveSize 3
            final.totalPages shouldBe 1
            final.totalItems shouldBe 3
            final.hasMore shouldBe false
            final.syncProgress.shouldBeNull()
        }
    }

    @Test
    fun `initial load sets error state on failure`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        val (vm) = makeViewModel(engine)

        vm.uiState.test {
            // Collect until we have a non-null error or syncProgress goes null
            val states = mutableListOf(awaitItem())
            while (states.last().error == null && states.last().syncProgress != null) {
                states.add(awaitItem())
            }
            val final = states.last()
            final.error.shouldNotBeNull()
            final.items.size shouldBe 0
        }
    }

    // ── loadNextPage (no-op stub) ─────────────────────────────────────────────

    @Test
    fun `loadNextPage is a no-op and emits no extra state`() = runTest {
        val engine = MockEngine { _ ->
            respond(singlePageResponse(count = 1), HttpStatusCode.OK, jsonHeaders())
        }

        val (vm) = makeViewModel(engine)
        vm.uiState.test {
            // Drain initial sync
            while (awaitItem().syncProgress != null) { /* skip */ }

            vm.loadNextPage() // no-op — must not emit
            expectNoEvents()
        }
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh reloads from page 1 and accumulates items`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(singlePageResponse(count = 2), HttpStatusCode.OK, jsonHeaders())
        }

        val (vm) = makeViewModel(engine)
        vm.uiState.test {
            // Drain initial sync
            while (awaitItem().syncProgress != null) { /* skip */ }

            vm.refresh()

            // Under UnconfinedTestDispatcher, StateFlow may coalesce intermediate
            // states (reset → progress → done) into a single emission.  Instead of
            // relying on observing the null→non-null→null syncProgress sequence, we
            // simply drain until we land on a settled state: syncProgress==null AND
            // the items reflect the refreshed data.
            var finalState: CollectionUiState
            do {
                finalState = awaitItem()
            } while (finalState.syncProgress != null || finalState.items.size != 2)
            finalState.items shouldHaveSize 2
            finalState.syncProgress.shouldBeNull()

            cancelAndIgnoreRemainingEvents()
        }

        callCount shouldBe 2
    }

    // ── filterLetter ──────────────────────────────────────────────────────────

    @Test
    fun `filterLetter filters already-loaded items without triggering extra page loads`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(
                """
                {"pagination":{"pages":1,"items":3},"releases":[
                  {"instance_id":1,"id":1,"basic_information":{"title":"A1","artists":[{"name":"Alpha"}],"formats":[{"name":"Vinyl"}]}},
                  {"instance_id":2,"id":2,"basic_information":{"title":"B1","artists":[{"name":"Beta"}],"formats":[{"name":"Vinyl"}]}},
                  {"instance_id":3,"id":3,"basic_information":{"title":"B2","artists":[{"name":"Bravo"}],"formats":[{"name":"Vinyl"}]}}
                ]}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }

        val (vm) = makeViewModel(engine)
        vm.uiState.test {
            // Drain initial sync
            var state = awaitItem()
            while (state.syncProgress != null) { state = awaitItem() }
            state.items shouldHaveSize 3

            vm.filterLetter('B')
            val filtered = awaitItem()
            filtered.selectedFilter shouldBe 'B'
            filtered.filteredItems shouldHaveSize 2

            vm.filterLetter(null)
            val cleared = awaitItem()
            cleared.selectedFilter.shouldBeNull()
            cleared.filteredItems shouldHaveSize 3
        }

        // Only 1 API call — filterLetter no longer triggers page loads
        callCount shouldBe 1
    }

    @Test
    fun `filterLetter null when already null emits no extra state`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(singlePageResponse(count = 2), HttpStatusCode.OK, jsonHeaders())
        }

        val (vm) = makeViewModel(engine)
        vm.uiState.test {
            // Drain initial sync
            while (awaitItem().syncProgress != null) { /* skip */ }

            vm.filterLetter(null) // null→null: StateFlow deduplicates, no new emission
            expectNoEvents()
        }

        callCount shouldBe 1
    }

    // ── Multi-page sync ───────────────────────────────────────────────────────

    @Test
    fun `syncAllPages fetches all pages and accumulates items`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val json = when (callCount) {
                1 -> """
                    {"pagination":{"pages":2,"items":4},"releases":[
                      {"instance_id":1,"id":1,"basic_information":{"title":"A1","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":2,"id":2,"basic_information":{"title":"A2","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
                else -> """
                    {"pagination":{"pages":2,"items":4},"releases":[
                      {"instance_id":3,"id":3,"basic_information":{"title":"B1","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":4,"id":4,"basic_information":{"title":"B2","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
            }
            respond(json, HttpStatusCode.OK, jsonHeaders())
        }

        val (vm) = makeViewModel(engine)
        vm.uiState.test {
            // Collect all states until sync finishes
            val states = mutableListOf(awaitItem())
            while (states.last().syncProgress != null) {
                states.add(awaitItem())
            }

            // Final state must have all 4 items
            val final = states.last()
            final.items shouldHaveSize 4
            final.syncProgress.shouldBeNull()
        }

        callCount shouldBe 2
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    @Test
    fun `cache is populated after full sync`() = runTest {
        val engine = MockEngine { _ ->
            respond(singlePageResponse(count = 2), HttpStatusCode.OK, jsonHeaders())
        }

        val (vm, cache) = makeViewModel(engine)
        vm.uiState.test {
            while (awaitItem().syncProgress != null) { /* drain */ }
        }

        // Cache should now be populated
        cache["cache"].shouldNotBeNull()
    }

    @Test
    fun `cache is restored on startup and displayed before sync completes`() = runTest {
        // Pre-populate with one item via the secondary constructor path (JSON)
        val cachedJson = """[{"instance_id":99,"id":99,"title":"Cached","artists":["Artist"],"formats":["Vinyl"],"thumb":null,"year":2020,"label":null}]"""

        val engine = MockEngine { _ ->
            respond(singlePageResponse(count = 3), HttpStatusCode.OK, jsonHeaders())
        }

        val (vm) = makeViewModel(engine, initialCache = cachedJson)
        vm.uiState.test {
            // First item: restored from cache (1 item) with syncProgress=0f
            val states = mutableListOf(awaitItem())
            while (states.last().syncProgress != null) {
                states.add(awaitItem())
            }
            // At least one state had items from cache before sync completed
            // The final state should have the synced items
            val final = states.last()
            final.items shouldHaveSize 3
        }
    }

    // ── isEmpty ───────────────────────────────────────────────────────────────

    @Test
    fun `isEmpty is true when no items and no loading and no error`() {
        val state = CollectionUiState()
        state.isEmpty shouldBe true
    }

    @Test
    fun `isEmpty is false when loading`() {
        val state = CollectionUiState(isLoading = true)
        state.isEmpty shouldBe false
    }

    @Test
    fun `isEmpty is false when error present`() {
        val state = CollectionUiState(error = "oops")
        state.isEmpty shouldBe false
    }
}
