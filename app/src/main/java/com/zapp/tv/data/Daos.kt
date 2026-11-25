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
)

private const val NOW_FILTER =
    "p.playlistId = c.playlistId AND p.channelEpgId = c.epgId AND p.start <= :now AND p.stop > :now " +
        "ORDER BY p.start DESC LIMIT 1"

private const val NOW_COLUMNS =
    "(SELECT p.title FROM programmes p WHERE " + NOW_FILTER + ") AS nowTitle, " +
        "(SELECT p.start FROM programmes p WHERE " + NOW_FILTER + ") AS nowStart, " +
        "(SELECT p.stop FROM programmes p WHERE " + NOW_FILTER + ") AS nowStop"

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
            "FROM channels c WHERE c.playlistId = :playlistId AND (:categoryId IS NULL OR c.categoryId = :categoryId) " +
            "ORDER BY c.number",
    )
    fun pagedRows(playlistId: Long, categoryId: Long?, now: Long): PagingSource<Int, ChannelRow>

    /** Only channels with a programme on right now (guide "On now" filter). */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM channels c WHERE c.playlistId = :playlistId AND (:categoryId IS NULL OR c.categoryId = :categoryId) " +
            "AND c.epgId IS NOT NULL AND EXISTS(SELECT 1 FROM programmes p WHERE p.playlistId = c.playlistId " +
            "AND p.channelEpgId = c.epgId AND p.start <= :now AND p.stop > :now) ORDER BY c.number",
    )
    fun pagedOnNowRows(playlistId: Long, categoryId: Long?, now: Long): PagingSource<Int, ChannelRow>

    /** Channel search across every playlist; the current playlist comes first. */
    @Query(
        "SELECT c.*, " + NOW_COLUMNS + ", " +
            "EXISTS(SELECT 1 FROM favorites f WHERE f.playlistId = c.playlistId AND f.streamUrl = c.streamUrl) AS isFavorite " +
            "FROM channels c WHERE c.name LIKE '%' || :query || '%' " +
            "ORDER BY (c.playlistId = :preferPlaylist) DESC, (c.name LIKE :query || '%') DESC, c.number LIMIT :limit",
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
            "WHERE r.playlistId = :playlistId GROUP BY r.id ORDER BY r.watchedAt DESC",
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
            "AND p.start <= :now AND p.stop > :now) ORDER BY c.number LIMIT :limit",
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

    @Query(
        "SELECT * FROM channels WHERE playlistId = :playlistId AND streamUrl = :streamUrl " +
            "AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY number LIMIT 1",
    )
    suspend fun findInScope(playlistId: Long, categoryId: Long?, streamUrl: String): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId) AND number > :number ORDER BY number ASC LIMIT 1")
    suspend fun next(playlistId: Long, categoryId: Long?, number: Int): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId) AND number < :number ORDER BY number DESC LIMIT 1")
    suspend fun previous(playlistId: Long, categoryId: Long?, number: Int): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY number ASC LIMIT 1")
    suspend fun first(playlistId: Long, categoryId: Long?): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId) ORDER BY number DESC LIMIT 1")
    suspend fun last(playlistId: Long, categoryId: Long?): Channel?

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId) AND number < :number")
    suspend fun indexOf(playlistId: Long, categoryId: Long?, number: Int): Int

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Insert
    suspend fun insertAll(items: List<Channel>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT playlistId, COUNT(*) AS total FROM channels GROUP BY playlistId")
    fun observeCounts(): Flow<List<PlaylistCount>>

    @Query("SELECT categoryId, COUNT(*) AS total FROM channels WHERE playlistId = :playlistId GROUP BY categoryId")
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
