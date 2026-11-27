package com.zapp.tv.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class PlaylistCount(val playlistId: Long, val total: Int)

data class CategoryCount(val categoryId: Long?, val total: Int)

data class IdName(val id: Long, val name: String)

/** A search result from the TV guide, with the channel it's on. */
data class ProgrammeHit(
    val programmeId: Long,
    val title: String,
    val start: Long,
    val stop: Long,
    val icon: String?,
    val channelId: Long,
    val channelName: String,
    val channelLogo: String?,
)

/** A channel plus what is on it right now, for list rows. */
data class ChannelRow(
    @Embedded val channel: Channel,
    val nowTitle: String?,
    val nowStart: Long?,
    val nowStop: Long?,
    val isFavorite: Boolean,
    /** What's on after this (title and start), for the wide channel list. */
    val nextTitle: String? = null,
    val nextStart: Long? = null,
)

/** A channel's place in a list (for zapping, and for moving channels in your own order). */
data class ChannelOrder(val id: Long, val streamUrl: String, val number: Int, val position: Double?)

private const val NOW_FILTER =
    "p.playlistId = c.playlistId AND p.channelEpgId = c.epgId AND p.start <= :now AND p.stop > :now " +
        "ORDER BY p.start DESC LIMIT 1"

private const val NEXT_FILTER =
    "p.playlistId = c.playlistId AND p.channelEpgId = c.epgId AND p.start > :now ORDER BY p.start LIMIT 1"

private const val NOW_COLUMNS =
    "(SELECT p.title FROM programmes p WHERE " + NOW_FILTER + ") AS nowTitle, " +
        "(SELECT p.start FROM programmes p WHERE " + NOW_FILTER + ") AS nowStart, " +
        "(SELECT p.stop FROM programmes p WHERE " + NOW_FILTER + ") AS nowStop, " +
        "(SELECT p.title FROM programmes p WHERE " + NEXT_FILTER + ") AS nextTitle, " +
        "(SELECT p.start FROM programmes p WHERE " + NEXT_FILTER + ") AS nextStart"

// Your own choices for each channel (hidden, your order), joined as "o".
private const val CHANNEL_PREFS =
    "LEFT JOIN live_prefs o ON o.playlistId = c.playlistId AND o.kind = 'ch' AND o.ref = c.streamUrl "

private const val SHOWN = "(o.hidden IS NULL OR o.hidden = 0) "

private const val HIDDEN_CATEGORY_IDS =
    "SELECT k.id FROM categories k INNER JOIN live_prefs h ON h.playlistId = k.playlistId AND h.kind = 'cat' " +
        "AND h.ref = k.remoteId WHERE k.playlistId = :playlistId AND h.hidden = 1"

// One playlist's channels in a category (or all of them), without hidden channels. All channels also
// leaves out hidden categories; a hidden category opened on its own still lists its channels.
private const val IN_SCOPE =
    "c.playlistId = :playlistId AND (:categoryId IS NULL OR c.categoryId = :categoryId) AND " + SHOWN +
        "AND (:categoryId IS NOT NULL OR c.categoryId IS NULL OR c.categoryId NOT IN (" + HIDDEN_CATEGORY_IDS + ")) "

// :sort 0 by number, 1 by name, 2 your own order (moved channels take the place you gave them).
// The id last keeps the order exact, so pages of a long list never overlap.
private const val CHANNEL_ORDER =
    "ORDER BY (CASE WHEN :sort = 1 THEN c.name ELSE '' END) COLLATE NOCASE, " +
        "(CASE WHEN :sort = 2 THEN COALESCE(o.position, c.number) ELSE 0 END), c.number, c.id"

// For lists across playlists (search): hidden channels and channels in hidden categories left out.
private const val NOT_HIDDEN_ANYWHERE =
    "NOT EXISTS(SELECT 1 FROM live_prefs h WHERE h.playlistId = c.playlistId AND h.kind = 'ch' AND h.ref = c.streamUrl AND h.hidden = 1) " +
        "AND NOT EXISTS(SELECT 1 FROM categories k INNER JOIN live_prefs h ON h.playlistId = k.playlistId AND h.kind = 'cat' " +
        "AND h.ref = k.remoteId AND h.hidden = 1 WHERE k.id = c.categoryId) "

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): Playlist?

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Query("UPDATE playlists SET epgUrl = :epgUrl WHERE id = :id")
    suspend fun setEpgUrl(id: Long, epgUrl: String?)

    @Query("UPDATE playlists SET epgUpdated = :time WHERE id = :id")
    suspend fun setEpgUpdated(id: Long, time: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE playlistId = :playlistId ORDER BY sortOrder")
    fun observeForPlaylist(playlistId: Long): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId ORDER BY sortOrder")
    suspend fun forPlaylist(playlistId: Long): List<Category>

    @Insert
    suspend fun insertAll(items: List<Category>): List<Long>

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}

@Dao
interface ChannelDao {
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM channels c " + CHANNEL_PREFS + "WHERE " + IN_SCOPE + CHANNEL_ORDER,
    )
    fun pagedRows(playlistId: Long, categoryId: Long?, now: Long, sort: Int): PagingSource<Int, ChannelRow>

    /** Only channels with a programme on right now (guide "On now" filter). */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM channels c " + CHANNEL_PREFS + "WHERE " + IN_SCOPE +
            "AND c.epgId IS NOT NULL AND EXISTS(SELECT 1 FROM programmes p WHERE p.playlistId = c.playlistId " +
            "AND p.channelEpgId = c.epgId AND p.start <= :now AND p.stop > :now) " + CHANNEL_ORDER,
    )
    fun pagedOnNowRows(playlistId: Long, categoryId: Long?, now: Long, sort: Int): PagingSource<Int, ChannelRow>

    /** Channel search across every playlist; the current playlist comes first. */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM channels c WHERE c.name LIKE '%' || :query || '%' AND " + NOT_HIDDEN_ANYWHERE +
            "ORDER BY (c.playlistId = :preferPlaylist) DESC, (c.name LIKE :query || '%') DESC, c.number, c.id LIMIT :limit",
    )
    suspend fun searchRows(query: String, preferPlaylist: Long, now: Long, limit: Int): List<ChannelRow>

    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", 1 AS isFavorite " +
            "FROM favorites f INNER JOIN channels c ON c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl " +
            "WHERE f.playlistId = :playlistId GROUP BY f.id ORDER BY f.id",
    )
    fun pagedFavoriteRows(playlistId: Long, now: Long): PagingSource<Int, ChannelRow>

    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM recent_channels r INNER JOIN channels c ON c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl " +
            "WHERE r.playlistId = :playlistId GROUP BY r.id ORDER BY r.watchedAt DESC, r.id DESC",
    )
    fun pagedRecentRows(playlistId: Long, now: Long): PagingSource<Int, ChannelRow>

    /** Recently watched across every playlist, for the Home screen. */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM recent_channels r INNER JOIN channels c ON c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl " +
            "GROUP BY r.id ORDER BY r.watchedAt DESC LIMIT :limit",
    )
    fun observeRecentRows(now: Long, limit: Int): Flow<List<ChannelRow>>

    /** Favourites across every playlist, for the Home screen. */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", 1 AS isFavorite " +
            "FROM favorites f INNER JOIN channels c ON c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl " +
            "GROUP BY f.id ORDER BY f.id LIMIT :limit",
    )
    fun observeFavoriteRows(now: Long, limit: Int): Flow<List<ChannelRow>>

    /** Channels in one playlist with a programme on right now, for the Home "On now" row. */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM channels c WHERE c.playlistId = :playlistId AND c.epgId IS NOT NULL AND EXISTS(" +
            "SELECT 1 FROM programmes p WHERE p.playlistId = c.playlistId AND p.channelEpgId = c.epgId " +
            "AND p.start <= :now AND p.stop > :now) AND " + NOT_HIDDEN_ANYWHERE + "ORDER BY c.number LIMIT :limit",
    )
    fun observeOnNowRows(playlistId: Long, now: Long, limit: Int): Flow<List<ChannelRow>>

    @Query(
        "SELECT c.* FROM recent_channels r INNER JOIN channels c ON c.playlistId = r.playlistId AND c.streamUrl = r.streamUrl " +
            "WHERE r.playlistId = :playlistId GROUP BY r.id ORDER BY r.watchedAt DESC",
    )
    suspend fun recentChannels(playlistId: Long): List<Channel>

    /** One-time upgrade: replaces the login-bearing part of Xtream links with a short token. */
    @Query(
        "UPDATE channels SET streamUrl = 'xt:' || substr(streamUrl, length(:prefix) + 1) " +
            "WHERE playlistId = :playlistId AND substr(streamUrl, 1, length(:prefix)) = :prefix",
    )
    suspend fun tokenizeStreams(playlistId: Long, prefix: String)

    @Query(
        "SELECT c.* FROM favorites f INNER JOIN channels c ON c.playlistId = f.playlistId AND c.streamUrl = f.streamUrl " +
            "WHERE f.playlistId = :playlistId GROUP BY f.id ORDER BY f.id",
    )
    suspend fun favoriteChannels(playlistId: Long): List<Channel>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND name = :name ORDER BY number LIMIT 1")
    suspend fun named(playlistId: Long, name: String): Channel?

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND streamUrl = :streamUrl " +
            "AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY number LIMIT 1",
    )
    suspend fun findInScope(playlistId: Long, categoryId: Long?, streamUrl: String): Channel?

    /** Every channel in a list, in the order it's shown (for zapping and moving channels). */
    @Query("SELECT c.id, c.streamUrl, c.number, o.position FROM channels c " + CHANNEL_PREFS + "WHERE " + IN_SCOPE + CHANNEL_ORDER)
    suspend fun order(playlistId: Long, categoryId: Long?, sort: Int): List<ChannelOrder>

    /** Channels you've hidden in a playlist, for Settings. */
    @Query(
        "SELECT c.* FROM live_prefs h INNER JOIN channels c ON c.playlistId = h.playlistId AND c.streamUrl = h.ref " +
            "WHERE h.playlistId = :playlistId AND h.kind = 'ch' AND h.hidden = 1 GROUP BY h.ref ORDER BY c.number",
    )
    fun observeHidden(playlistId: Long): Flow<List<Channel>>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Insert
    suspend fun insertAll(items: List<Channel>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT playlistId, COUNT(*) AS total FROM channels GROUP BY playlistId")
    fun observeCounts(): Flow<List<PlaylistCount>>

    @Query(
        "SELECT c.categoryId, COUNT(*) AS total FROM channels c " + CHANNEL_PREFS +
            "WHERE c.playlistId = :playlistId AND " + SHOWN + "GROUP BY c.categoryId",
    )
    fun observeCategoryCounts(playlistId: Long): Flow<List<CategoryCount>>

    @Query("SELECT DISTINCT epgId FROM channels WHERE playlistId = :playlistId AND epgId IS NOT NULL AND epgId != ''")
    suspend fun epgIds(playlistId: Long): List<String>

    @Query("SELECT id, name FROM channels WHERE playlistId = :playlistId AND (epgId IS NULL OR epgId = '')")
    suspend fun withoutEpg(playlistId: Long): List<IdName>

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    suspend fun setEpgId(id: Long, epgId: String)
}

@Dao
interface ProgrammeDao {
    /** Guide search: programmes on now or later whose title matches. */
    @Query(
        "SELECT p.id AS programmeId, p.title AS title, p.start AS start, p.stop AS stop, p.icon AS icon, " +
            "c.id AS channelId, c.name AS channelName, c.logo AS channelLogo " +
            "FROM programmes p INNER JOIN channels c ON c.playlistId = p.playlistId AND c.epgId = p.channelEpgId " +
            "WHERE p.playlistId > 0 AND p.stop > :now AND p.title LIKE '%' || :query || '%' " +
            "GROUP BY p.id ORDER BY p.start LIMIT :limit",
    )
    suspend fun search(query: String, now: Long, limit: Int): List<ProgrammeHit>

    @Query("SELECT COUNT(*) FROM programmes WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    /** Blocking on purpose: called from the streaming guide parser on a background thread. */
    @Insert
    fun insertStagingBlocking(items: List<StagedProgramme>)

    @Query("DELETE FROM programmes_staging WHERE playlistId = :playlistId")
    suspend fun clearStaging(playlistId: Long)

    @Query(
        "INSERT INTO programmes (playlistId, channelEpgId, start, stop, title, description, icon) " +
            "SELECT playlistId, channelEpgId, start, stop, title, description, icon " +
            "FROM programmes_staging WHERE playlistId = :playlistId",
    )
    suspend fun promoteStaging(playlistId: Long)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("UPDATE programmes SET playlistId = :to WHERE playlistId = :from")
    suspend fun movePlaylist(from: Long, to: Long)

    @Query("DELETE FROM programmes WHERE stop < :before AND playlistId > 0")
    suspend fun deleteEndedBefore(before: Long)

    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND channelEpgId = :epgId " +
            "AND stop > :from AND start < :to ORDER BY start",
    )
    suspend fun range(playlistId: Long, epgId: String, from: Long, to: Long): List<Programme>

    @Query(
        "SELECT * FROM programmes WHERE playlistId = :playlistId AND channelEpgId = :epgId " +
            "AND stop > :now ORDER BY start LIMIT :limit",
    )
    suspend fun upcoming(playlistId: Long, epgId: String, now: Long, limit: Int): List<Programme>
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND streamUrl = :streamUrl")
    suspend fun delete(playlistId: Long, streamUrl: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE playlistId = :playlistId AND streamUrl = :streamUrl)")
    suspend fun exists(playlistId: Long, streamUrl: String): Boolean

    @Query("SELECT COUNT(*) FROM favorites WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query(
        "UPDATE favorites SET streamUrl = 'xt:' || substr(streamUrl, length(:prefix) + 1) " +
            "WHERE playlistId = :playlistId AND substr(streamUrl, 1, length(:prefix)) = :prefix",
    )
    suspend fun tokenizeStreams(playlistId: Long, prefix: String)
}

@Dao
interface RecentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recent: RecentChannel)

    @Query(
        "DELETE FROM recent_channels WHERE id NOT IN " +
            "(SELECT id FROM recent_channels ORDER BY watchedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query(
        "UPDATE recent_channels SET streamUrl = 'xt:' || substr(streamUrl, length(:prefix) + 1) " +
            "WHERE playlistId = :playlistId AND substr(streamUrl, 1, length(:prefix)) = :prefix",
    )
    suspend fun tokenizeStreams(playlistId: Long, prefix: String)
}

@Dao
interface MediaDao {
    // ---- add-ons
    @Query("SELECT * FROM addons ORDER BY sortOrder, id")
    fun observeAddons(): Flow<List<Addon>>

    @Query("SELECT * FROM addons ORDER BY sortOrder, id")
    suspend fun addons(): List<Addon>

    @Insert
    suspend fun insertAddon(addon: Addon): Long

    @Update
    suspend fun updateAddon(addon: Addon)

    @Update
    suspend fun updateAddons(addons: List<Addon>)

    @Query("DELETE FROM addons WHERE id = :id")
    suspend fun deleteAddon(id: Long)

    // ---- watch progress
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: WatchProgress)

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun progress(profileId: Long, videoId: String): WatchProgress?

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND metaId = :metaId")
    fun observeProgressFor(profileId: Long, metaId: String): Flow<List<WatchProgress>>

    /** Unfinished films and episodes, newest first, one entry per film or series. */
    @Query(
        "SELECT * FROM watch_progress WHERE profileId = :profileId AND hidden = 0 AND watched = 0 AND (position > 30000 OR percent > 1 OR percent < 0) " +
            "AND id = (SELECT w2.id FROM watch_progress w2 WHERE w2.profileId = :profileId AND w2.metaId = watch_progress.metaId " +
            "AND w2.hidden = 0 AND w2.watched = 0 AND (w2.position > 30000 OR w2.percent > 1 OR w2.percent < 0) " +
            "ORDER BY w2.updatedAt DESC LIMIT 1) " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    fun observeContinueWatching(profileId: Long, limit: Int): Flow<List<WatchProgress>>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId")
    suspend fun allProgress(profileId: Long): List<WatchProgress>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgressList(items: List<WatchProgress>)

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND metaId = :metaId")
    suspend fun progressOfMeta(profileId: Long, metaId: String): List<WatchProgress>

    @Query("DELETE FROM watch_progress WHERE id = :id")
    suspend fun deleteProgressRow(id: Long)

    /** Everything watched or started, for the marks on posters. */
    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId")
    fun observeAllProgress(profileId: Long): Flow<List<WatchProgress>>

    /** Films marked watched, and series marked watched as a whole (a row keyed by the series id). */
    @Query("SELECT DISTINCT metaId FROM watch_progress WHERE profileId = :profileId AND watched = 1 AND videoId = metaId")
    fun observeWatchedTitles(profileId: Long): Flow<List<String>>

    /** Titles you've definitely watched: films and series marked watched, newest first. */
    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND watched = 1 AND videoId = metaId ORDER BY updatedAt DESC")
    suspend fun watchedTitleRows(profileId: Long): List<WatchProgress>

    /** Series you've watched at least [min] episodes of (one row each, the latest), newest first. */
    @Query("SELECT * FROM watch_progress WHERE id IN (SELECT MAX(id) FROM watch_progress WHERE profileId = :profileId AND watched = 1 AND type = 'series' AND videoId != metaId GROUP BY metaId HAVING COUNT(*) >= :min) ORDER BY updatedAt DESC")
    suspend fun seriesWatchedRows(profileId: Long, min: Int): List<WatchProgress>

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND metaId = :metaId AND watched = 1")
    suspend fun deleteWatched(profileId: Long, metaId: String)

    /** When each title was last played (for sorting the library by "Recently watched"). */
    @Query("SELECT metaId, MAX(updatedAt) AS at FROM watch_progress WHERE profileId = :profileId AND (percent IS NULL OR percent >= 0) GROUP BY metaId")
    fun observeLastWatched(profileId: Long): Flow<List<LastWatched>>

    @Query("SELECT * FROM library WHERE profileId = :profileId")
    suspend fun allLibrary(profileId: Long): List<LibraryItem>

    @Query("SELECT metaId FROM library WHERE profileId = :profileId")
    suspend fun libraryIds(profileId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToLibraryList(items: List<LibraryItem>)

    @Query("UPDATE watch_progress SET hidden = 1 WHERE profileId = :profileId AND metaId = :metaId")
    suspend fun hideFromContinue(profileId: Long, metaId: String)

    // ---- library
    @Query("SELECT * FROM library WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun observeLibrary(profileId: Long): Flow<List<LibraryItem>>

    @Query("SELECT EXISTS(SELECT 1 FROM library WHERE profileId = :profileId AND metaId = :metaId)")
    fun observeInLibrary(profileId: Long, metaId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToLibrary(item: LibraryItem)

    @Query("DELETE FROM library WHERE profileId = :profileId AND metaId = :metaId")
    suspend fun removeFromLibrary(profileId: Long, metaId: String)
}

/** A title and when it was last played. */
data class LastWatched(val metaId: String, val at: Long)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    suspend fun all(): List<Profile>

    @Insert
    suspend fun insert(profile: Profile): Long

    @Update
    suspend fun update(profile: Profile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM watch_progress WHERE profileId = :id")
    suspend fun deleteProgress(id: Long)

    @Query("DELETE FROM library WHERE profileId = :id")
    suspend fun deleteLibrary(id: Long)
}

@Dao
interface LivePrefDao {
    @Query("SELECT * FROM live_prefs WHERE playlistId = :playlistId AND kind = :kind")
    fun observe(playlistId: Long, kind: String): Flow<List<LivePref>>

    @Query("SELECT * FROM live_prefs WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun all(playlistId: Long, kind: String): List<LivePref>

    @Query("SELECT * FROM live_prefs WHERE playlistId = :playlistId AND kind = :kind AND ref = :ref")
    suspend fun get(playlistId: Long, kind: String, ref: String): LivePref?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(items: List<LivePref>)

    @Query("DELETE FROM live_prefs WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun clear(playlistId: Long, kind: String)

    @Query("DELETE FROM live_prefs WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)
}
