package com.joergi.jukebox.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.joergi.jukebox.util.TimeProvider

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

// ── Collection Synchronization Models ───────────────────────────────────────

/**
 * Metadata about collection synchronization state.
 * Persisted to storage for tracking sync progress and detecting changes.
 */
@Serializable
data class CollectionSyncMetadata(
    /** Total number of records in the user's collection */
    val totalCount: Int = 0,

    /** Timestamp (milliseconds) of the last successful incremental sync */
    val lastSyncedAt: Long = 0L,

    /** Timestamp of the last full collection resync */
    val lastFullSyncAt: Long = 0L,

    /** List of release IDs from the last "newest 50" fetch */
    val newestFiftyIds: List<Int> = emptyList(),

    /** Count of new records detected in last sync (for UI badge) */
    val newRecordsCount: Int = 0
) {
    companion object {
        /** Default metadata for first-time users */
        fun empty() = CollectionSyncMetadata()
    }
}

/**
 * Represents the current state of collection synchronization.
 * Used to update UI with sync progress indicators.
 */
sealed class SyncState {
    /** No sync operation in progress */
    data object Idle : SyncState()

    /** Phase 1: Loading collection from local cache */
    data object LoadingCache : SyncState()

    /** Phase 2: Fetching newest 50 records from API */
    data object FetchingNewest : SyncState()

    /** Phase 3: Validating and merging new records */
    data object Validating : SyncState()

    /** Phase 4: Performing full collection resync (validation failed) */
    data object FullResync : SyncState()

    /** Sync completed successfully */
    data object Complete : SyncState()

    /** Sync encountered an error */
    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : SyncState()
}

/**
 * Metadata returned by Discogs API about a collection folder.
 * Used to validate sync success.
 */
@Serializable
data class CollectionMetadata(
    /** Total number of items in the collection folder */
    val totalItemCount: Int = 0
)

/**
 * Formats a timestamp as a human-readable relative time string.
 * Example: "2 hours ago", "just now", "1 hour ago"
 */
fun Long.formatSyncTime(): String {
    val now = TimeProvider.currentTimeMillis()
    val differenceMs = now - this

    return when {
        differenceMs < 60_000 -> "just now"
        differenceMs < 3_600_000 -> {
            val minutes = differenceMs / 60_000
            if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }
        differenceMs < 86_400_000 -> {
            val hours = differenceMs / 3_600_000
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        else -> {
            val days = differenceMs / 86_400_000
            if (days == 1L) "1 day ago" else "$days days ago"
        }
    }
}
