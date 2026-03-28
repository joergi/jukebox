package com.joergi.jukebox.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single release in the user's Discogs collection.
 *
 * Mirrors the Flutter [CollectionItem] model. Fields are parsed directly from
 * the Discogs `/users/{username}/collection/folders/0/releases` endpoint.
 */
@Serializable
data class CollectionItem(
    val instanceId: Int,
    val id: Int,
    val title: String,
    val artists: List<String>,
    val formats: List<String>,
    val thumb: String?,
    val year: Int?,
    val label: String?,
) {
    companion object {
        /** Parse a single release JSON node from the Discogs API response. */
        fun fromJson(json: DiscogsReleaseJson): CollectionItem {
            val info = json.basicInformation
            return CollectionItem(
                instanceId = json.instanceId,
                id = json.id,
                title = info.title,
                artists = info.artists.map { it.name },
                formats = info.formats.map { it.name },
                thumb = info.thumb?.ifBlank { null },
                year = info.year?.takeIf { it != 0 },
                label = info.labels?.firstOrNull()?.name,
            )
        }
    }
}

// ── Raw JSON shapes used only for deserialization ───────────────────────────

@Serializable
data class DiscogsReleaseJson(
    @SerialName("instance_id") val instanceId: Int,
    val id: Int,
    @SerialName("basic_information") val basicInformation: DiscogsBasicInfoJson,
)

@Serializable
data class DiscogsBasicInfoJson(
    val title: String,
    val artists: List<DiscogsArtistJson> = emptyList(),
    val formats: List<DiscogsFormatJson> = emptyList(),
    val thumb: String? = null,
    val year: Int? = null,
    val labels: List<DiscogsLabelJson>? = null,
)

@Serializable
data class DiscogsArtistJson(val name: String)

@Serializable
data class DiscogsFormatJson(val name: String)

@Serializable
data class DiscogsLabelJson(val name: String)

@Serializable
data class DiscogsCollectionResponse(
    val pagination: DiscogsPaginationJson,
    val releases: List<DiscogsReleaseJson>,
)

@Serializable
data class DiscogsPaginationJson(
    val pages: Int,
    val items: Int,
)

@Serializable
data class DiscogsIdentityJson(
    val username: String,
)
