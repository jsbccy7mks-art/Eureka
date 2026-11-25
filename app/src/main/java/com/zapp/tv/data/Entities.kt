package com.zapp.tv.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val TYPE_XTREAM = "xtream"
const val TYPE_M3U = "m3u"

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** Xtream: server base URL. M3U: playlist URL (encrypted, see [Secrets]). */
    val url: String,
    /** Encrypted, see [Secrets]. */
    val username: String? = null,
    /** Encrypted, see [Secrets]. */
    val password: String? = null,
    /** Xtream stream container: "ts" or "m3u8". */
    val format: String = "ts",
    /** XMLTV guide link (encrypted). Empty for Xtream means "use the provider's own guide". */
    val epgUrl: String? = null,
    val lastUpdated: Long = 0,
    val epgUpdated: Long = 0,
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["playlistId"])],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "number"]),
        Index(value = ["categoryId"]),
    ],
)
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val categoryId: Long?,
    /** Position of the channel inside its playlist (1-based). */
    val number: Int,
    val name: String,
    val logo: String?,
    /**
     * For Xtream this is a token like "xt:1234.ts"; the real link (which contains the login)
     * is only built at play time. For M3U it is the link from the playlist.
     */
    val streamUrl: String,
    val epgId: String?,
)

/** One TV guide entry. Keyed by the guide's channel id, so it survives channel list refreshes. */
@Entity(
    tableName = "programmes",
    indices = [Index(value = ["playlistId", "channelEpgId", "start"])],
)
data class Programme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelEpgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    /** Programme image from the guide, when the guide provides one. */
    val icon: String? = null,
)

/**
 * Guide rows being downloaded. They are swapped into [Programme] in one step at the end, so
 * channel lists don't reload over and over while a big guide is coming in.
 */
@Entity(tableName = "programmes_staging")
data class StagedProgramme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelEpgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val icon: String?,
)

/** An installed Stremio add-on. The manifest link can hold the user's own settings, so it is encrypted. */
@Entity(tableName = "addons")
data class Addon(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manifestUrl: String,
    val addonId: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val manifestJson: String,
    val enabled: Boolean,
    val sortOrder: Int,
    val addedAt: Long,
)

/** How far a film or episode has been watched. [videoId] is the film id or "showId:season:episode". */
@Entity(
    tableName = "watch_progress",
    indices = [Index(value = ["videoId"], unique = true)],
)
data class WatchProgress(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val metaId: String,
    val type: String,
    val name: String,
    val episodeLabel: String?,
    val poster: String?,
    val background: String?,
    val position: Long,
    val duration: Long,
    val watched: Boolean,
    /** Removed from Continue watching by the user. */
    val hidden: Boolean,
    val updatedAt: Long,
)

/** Films and series saved to the library. */
@Entity(
    tableName = "library",
    indices = [Index(value = ["metaId"], unique = true)],
)
data class LibraryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val addedAt: Long,
)

/** Favourites are keyed by stream link, which stays the same when a playlist is refreshed. */
@Entity(
    tableName = "favorites",
    indices = [Index(value = ["playlistId", "streamUrl"], unique = true)],
)
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val streamUrl: String,
    val name: String,
    val addedAt: Long,
)

/** Channels you've watched, newest first. Keyed by stream link like favourites. */
@Entity(
    tableName = "recent_channels",
    indices = [Index(value = ["playlistId", "streamUrl"], unique = true)],
)
data class RecentChannel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val streamUrl: String,
    val name: String,
    val logo: String?,
    val watchedAt: Long,
)
