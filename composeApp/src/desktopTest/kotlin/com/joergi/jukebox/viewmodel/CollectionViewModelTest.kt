package com.joergi.jukebox.viewmodel

import app.cash.turbine.test
import com.joergi.jukebox.service.DiscogsService
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * Unit tests for [CollectionViewModel].
 *
 * Uses Ktor's MockEngine so no network is required.
 */
class CollectionViewModelTest {

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

    private fun makeViewModel(engine: MockEngine, username: String = "user"): CollectionViewModel {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = DiscogsService("key", "secret", client).also {
            it.setCredentials("tok", "sec")
        }
        return CollectionViewModel(service = service, username = username, perPage = 5)
    }

    // ── Initial load ──────────────────────────────────────────────────────────

    @Test
    fun `initial load populates items from first page`() = runTest {
        val engine = MockEngine { _ ->
            respond(singlePageResponse(count = 3), HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        vm.uiState.test {
            val loading = awaitItem()
            loading.isLoading shouldBe true
            loading.items.size shouldBe 0

            val loaded = awaitItem()
            loaded.isLoading shouldBe false
            loaded.items shouldHaveSize 3
            loaded.totalPages shouldBe 1
            loaded.totalItems shouldBe 3
            loaded.hasMore shouldBe false
        }
    }

    @Test
    fun `initial load sets error state on failure`() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) }
        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading
            val error = awaitItem()
            error.isLoading shouldBe false
            error.error.shouldNotBeNull()
            error.items.size shouldBe 0
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    @Test
    fun `loadNextPage appends items to existing list`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val json = when (callCount) {
                1 -> """
                    {"pagination":{"pages":2,"items":6},"releases":[
                      {"instance_id":1,"id":1,"basic_information":{"title":"A1","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":2,"id":2,"basic_information":{"title":"A2","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
                else -> """
                    {"pagination":{"pages":2,"items":6},"releases":[
                      {"instance_id":3,"id":3,"basic_information":{"title":"B1","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":4,"id":4,"basic_information":{"title":"B2","artists":[{"name":"X"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
            }
            respond(json, HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading page 1
            val page1 = awaitItem()
            page1.items shouldHaveSize 2
            page1.hasMore shouldBe true

            vm.loadNextPage()
            awaitItem() // loading page 2
            val page2 = awaitItem()
            page2.items shouldHaveSize 4
            page2.hasMore shouldBe false
        }
    }

    @Test
    fun `loadNextPage is a no-op when already loading`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(singlePageResponse(count = 1), HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        // Call loadNextPage while first load is in flight — state has isLoading=true
        // The second call should be ignored.
        vm.uiState.test {
            val loading = awaitItem()
            loading.isLoading shouldBe true
            vm.loadNextPage() // should be ignored
            val loaded = awaitItem()
            loaded.items shouldHaveSize 1
        }

        callCount shouldBe 1
    }

    @Test
    fun `loadNextPage is a no-op when all pages loaded`() = runTest {
        val engine = MockEngine { _ ->
            respond(singlePageResponse(count = 1), HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded, totalPages == 1, currentPage == 1 -> !hasMore
            vm.loadNextPage() // should be no-op
            expectNoEvents()
        }
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh resets state and reloads from page 1`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(singlePageResponse(count = 2), HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded

            vm.refresh()

            val resetting = awaitItem()
            resetting.items.size shouldBe 0 // cleared
            resetting.isLoading shouldBe true

            val reloaded = awaitItem()
            reloaded.items shouldHaveSize 2
        }

        callCount shouldBe 2
    }

    // ── filterLetter ──────────────────────────────────────────────────────────

    @Test
    fun `filterLetter loads all remaining pages when filter is set`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val json = when (callCount) {
                1 -> """
                    {"pagination":{"pages":3,"items":6},"releases":[
                      {"instance_id":1,"id":1,"basic_information":{"title":"A1","artists":[{"name":"Alpha"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":2,"id":2,"basic_information":{"title":"B1","artists":[{"name":"Beta"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
                2 -> """
                    {"pagination":{"pages":3,"items":6},"releases":[
                      {"instance_id":3,"id":3,"basic_information":{"title":"B2","artists":[{"name":"Bravo"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":4,"id":4,"basic_information":{"title":"C1","artists":[{"name":"Charlie"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
                else -> """
                    {"pagination":{"pages":3,"items":6},"releases":[
                      {"instance_id":5,"id":5,"basic_information":{"title":"B3","artists":[{"name":"Bongo"}],"formats":[{"name":"Vinyl"}]}},
                      {"instance_id":6,"id":6,"basic_information":{"title":"D1","artists":[{"name":"Delta"}],"formats":[{"name":"Vinyl"}]}}
                    ]}
                """.trimIndent()
            }
            respond(json, HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading page 1
            val page1 = awaitItem()
            page1.items shouldHaveSize 2
            page1.hasMore shouldBe true

            // Setting a filter emits a state update for selectedFilter first,
            // then triggers loading of all remaining pages automatically.
            vm.filterLetter('B')

            // State with selectedFilter='B' but still only page1 items
            val withFilter = awaitItem()
            withFilter.selectedFilter shouldBe 'B'
            withFilter.items shouldHaveSize 2

            // Pages 2 and 3 should be fetched automatically
            awaitItem() // loading page 2
            val page2 = awaitItem()
            page2.items shouldHaveSize 4

            awaitItem() // loading page 3
            val page3 = awaitItem()
            page3.items shouldHaveSize 6
            page3.hasMore shouldBe false

            // Filtered items should include all 3 "B" artists across all pages
            page3.filteredItems shouldHaveSize 3
        }

        callCount shouldBe 3
    }

    @Test
    fun `filterLetter null does not trigger extra page loads`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond(singlePageResponse(count = 2), HttpStatusCode.OK, jsonHeaders())
        }

        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded

            vm.filterLetter(null)
            // Clearing filter (null) should not trigger any additional network calls
            expectNoEvents()
        }

        callCount shouldBe 1
    }

    @Test
    fun `filterLetter shows all loaded items when collection is already fully loaded`() = runTest {
        val engine = MockEngine { _ ->
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

        val vm = makeViewModel(engine)

        vm.uiState.test {
            awaitItem() // loading
            val loaded = awaitItem()
            loaded.items shouldHaveSize 3
            loaded.hasMore shouldBe false

            vm.filterLetter('B')
            // No extra pages to load; filter is applied immediately
            val filtered = awaitItem()
            filtered.selectedFilter shouldBe 'B'
            filtered.filteredItems shouldHaveSize 2
        }
    }

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
