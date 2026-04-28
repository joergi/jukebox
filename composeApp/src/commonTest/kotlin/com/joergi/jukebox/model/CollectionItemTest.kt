package com.joergi.jukebox.model

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * Unit tests for [CollectionItem] and its associated JSON deserialization.
 *
 * All tests run in commonTest (no platform-specific APIs needed).
 */
class CollectionItemTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    // ── fromJson ──────────────────────────────────────────────────────────────

    @Test
    fun `fromJson maps all fields from a full release JSON node`() {
        val release = DiscogsReleaseJson(
            instanceId = 42,
            id = 7,
            basicInformation = DiscogsBasicInfoJson(
                title = "OK Computer",
                artists = listOf(DiscogsArtistJson("Radiohead")),
                formats = listOf(
                    DiscogsFormatJson("Vinyl", listOf("LP", "Album")),
                    DiscogsFormatJson("Box Set", emptyList())
                ),
                thumb = "https://example.com/thumb.jpg",
                year = 1997,
                labels = listOf(DiscogsLabelJson("Parlophone")),
            ),
        )

        val item = CollectionItem.fromJson(release)

        item.instanceId shouldBe 42
        item.id shouldBe 7
        item.title shouldBe "OK Computer"
        item.artists shouldContainExactly listOf("Radiohead")
        item.formats shouldContainExactly listOf("Vinyl (LP, Album)", "Box Set")
        item.thumb shouldBe "https://example.com/thumb.jpg"
        item.year shouldBe 1997
        item.label shouldBe "Parlophone"
    }

    @Test
    fun `fromJson uses first label name when multiple labels exist`() {
        val release = makeRelease(
            labels = listOf(DiscogsLabelJson("First"), DiscogsLabelJson("Second")),
        )
        CollectionItem.fromJson(release).label shouldBe "First"
    }

    @Test
    fun `fromJson sets label to null when labels list is null`() {
        val release = makeRelease(labels = null)
        CollectionItem.fromJson(release).label.shouldBeNull()
    }

    @Test
    fun `fromJson sets label to null when labels list is empty`() {
        val release = makeRelease(labels = emptyList())
        CollectionItem.fromJson(release).label.shouldBeNull()
    }

    @Test
    fun `fromJson sets thumb to null when thumb is blank`() {
        val release = makeRelease(thumb = "   ")
        CollectionItem.fromJson(release).thumb.shouldBeNull()
    }

    @Test
    fun `fromJson sets thumb to null when thumb is null`() {
        val release = makeRelease(thumb = null)
        CollectionItem.fromJson(release).thumb.shouldBeNull()
    }

    @Test
    fun `fromJson sets year to null when year is 0`() {
        val release = makeRelease(year = 0)
        CollectionItem.fromJson(release).year.shouldBeNull()
    }

    @Test
    fun `fromJson sets year to null when year is null`() {
        val release = makeRelease(year = null)
        CollectionItem.fromJson(release).year.shouldBeNull()
    }

    @Test
    fun `fromJson maps empty artists and formats to empty lists`() {
        val release = makeRelease(artists = emptyList(), formats = emptyList())
        val item = CollectionItem.fromJson(release)
        item.artists.shouldBeEmpty()
        item.formats.shouldBeEmpty()
    }

    // ── JSON deserialization ──────────────────────────────────────────────────

    @Test
    fun `DiscogsCollectionResponse deserializes full JSON correctly`() {
        val json = """
            {
              "pagination": { "pages": 3, "items": 75 },
              "releases": [
                {
                  "instance_id": 1,
                  "id": 100,
                  "basic_information": {
                    "title": "The Dark Side of the Moon",
                    "artists": [{ "name": "Pink Floyd" }],
                    "formats": [{ "name": "Vinyl", "descriptions": ["LP", "Album"] }],
                    "thumb": "https://img.discogs.com/dsotm.jpg",
                    "year": 1973,
                    "labels": [{ "name": "Harvest" }]
                  }
                }
              ]
            }
        """.trimIndent()

        val response = lenientJson.decodeFromString<DiscogsCollectionResponse>(json)

        response.pagination.pages shouldBe 3
        response.pagination.items shouldBe 75
        response.releases.size shouldBe 1

        val release = response.releases.first()
        release.instanceId shouldBe 1
        release.id shouldBe 100
        release.basicInformation.title shouldBe "The Dark Side of the Moon"
        release.basicInformation.artists.first().name shouldBe "Pink Floyd"
        release.basicInformation.year shouldBe 1973
    }

    @Test
    fun `DiscogsCollectionResponse deserializes empty releases list`() {
        val json = """{"pagination": {"pages": 0, "items": 0}, "releases": []}"""
        val response = lenientJson.decodeFromString<DiscogsCollectionResponse>(json)
        response.releases.shouldBeEmpty()
        response.pagination.items shouldBe 0
    }

    @Test
    fun `DiscogsBasicInfoJson uses defaults for missing optional fields`() {
        val json = """{"title": "Unknown Album"}"""
        val info = lenientJson.decodeFromString<DiscogsBasicInfoJson>(json)
        info.title shouldBe "Unknown Album"
        info.artists.shouldBeEmpty()
        info.formats.shouldBeEmpty()
        info.thumb.shouldBeNull()
        info.year.shouldBeNull()
        info.labels.shouldBeNull()
    }

    @Test
    fun `DiscogsIdentityJson deserializes username`() {
        val json = """{"username": "testuser", "id": 999, "resource_url": "https://api.discogs.com/users/testuser"}"""
        val identity = lenientJson.decodeFromString<DiscogsIdentityJson>(json)
        identity.username shouldBe "testuser"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeRelease(
        instanceId: Int = 1,
        id: Int = 1,
        title: String = "Test Album",
        artists: List<DiscogsArtistJson> = listOf(DiscogsArtistJson("Artist")),
        formats: List<DiscogsFormatJson> = listOf(DiscogsFormatJson("Vinyl", emptyList())),
        thumb: String? = "https://example.com/img.jpg",
        year: Int? = 2000,
        labels: List<DiscogsLabelJson>? = listOf(DiscogsLabelJson("Label")),
    ) = DiscogsReleaseJson(
        instanceId = instanceId,
        id = id,
        basicInformation = DiscogsBasicInfoJson(
            title = title,
            artists = artists,
            formats = formats,
            thumb = thumb,
            year = year,
            labels = labels,
        ),
    )
}
