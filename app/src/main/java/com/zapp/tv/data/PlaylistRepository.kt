package com.zapp.tv.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

const val FAVORITES = -2L

/** What a channel list shows: one playlist, and either all channels, a category, or favourites. */
data class Scope(val playlistId: Long, val categoryId: Long?) {
    val favorites: Boolean get() = categoryId == FAVORITES
}

class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
) {
    private val xtream = XtreamClient(http)
    private val guideLock = Mutex()

    @Volatile
    private var activeSource: PagingSource<Int, ChannelRow>? = null

    val playlists: Flow<List<Playlist>> = db.playlistDao().observeAll()
    val channelCounts: Flow<List<PlaylistCount>> = db.channelDao().observeCounts()

    suspend fun playlistsOnce(): List<Playlist> = db.playlistDao().getAll()

    fun categories(playlistId: Long): Flow<List<Category>> = db.categoryDao().observeForPlaylist(playlistId)

    fun categoryCounts(playlistId: Long): Flow<List<CategoryCount>> =
        db.channelDao().observeCategoryCounts(playlistId)

    fun favoriteCount(playlistId: Long): Flow<Int> = db.favoriteDao().observeCount(playlistId)

    suspend fun channelCount(playlistId: Long): Int = db.channelDao().count(playlistId)

    // ------------------------------------------------------------------ channel lists

    fun channels(scope: Scope): Flow<PagingData<ChannelRow>> =
        Pager(
            PagingConfig(
                pageSize = 50,
                prefetchDistance = 50,
                initialLoadSize = 100,
                enablePlaceholders = true,
                jumpThreshold = 150,
            ),
        ) {
            val now = System.currentTimeMillis()
            val dao = db.channelDao()
            val source =
                if (scope.favorites) dao.pagedFavoriteRows(scope.playlistId, now)
                else dao.pagedRows(scope.playlistId, scope.categoryId, now)
            activeSource = source
            source
        }.flow

    /** Re-reads the visible page so "now playing" titles move on, without losing the scroll position. */
    fun refreshNowPlaying() {
        activeSource?.invalidate()
    }

    suspend fun neighbor(scope: Scope, current: Channel, forward: Boolean): Channel? {
        if (scope.favorites) {
            val list = db.channelDao().favoriteChannels(scope.playlistId)
            if (list.isEmpty()) return null
            val i = list.indexOfFirst { it.streamUrl == current.streamUrl }
            if (i < 0) return list.first()
            val step = if (forward) 1 else -1
            return list[(i + step + list.size) % list.size]
        }
        val dao = db.channelDao()
        val pid = scope.playlistId
        val cat = scope.categoryId
        return if (forward) {
            dao.next(pid, cat, current.number) ?: dao.first(pid, cat)
        } else {
            dao.previous(pid, cat, current.number) ?: dao.last(pid, cat)
        }
    }

    /** Position of a channel inside a list, or -1. */
    suspend fun indexInScope(scope: Scope, streamUrl: String): Int {
        if (scope.favorites) {
            return db.channelDao().favoriteChannels(scope.playlistId).indexOfFirst { it.streamUrl == streamUrl }
        }
        val channel = db.channelDao().findInScope(scope.playlistId, scope.categoryId, streamUrl) ?: return -1
        return db.channelDao().indexOf(scope.playlistId, scope.categoryId, channel.number)
    }

    // ------------------------------------------------------------------ guide lookups

    suspend fun nowNext(channel: Channel): List<Programme> {
        val epgId = channel.epgId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return db.programmeDao().upcoming(channel.playlistId, epgId, System.currentTimeMillis(), 2)
    }

    suspend fun programmes(playlistId: Long, epgId: String, from: Long, to: Long): List<Programme> =
        db.programmeDao().range(playlistId, epgId, from, to)

    // ------------------------------------------------------------------ favourites

    suspend fun isFavorite(channel: Channel): Boolean =
        db.favoriteDao().exists(channel.playlistId, channel.streamUrl)

    /** Returns true when the channel is now a favourite. */
    suspend fun toggleFavorite(channel: Channel): Boolean {
        val dao = db.favoriteDao()
        return if (dao.exists(channel.playlistId, channel.streamUrl)) {
            dao.delete(channel.playlistId, channel.streamUrl)
            false
        } else {
            dao.insert(
                Favorite(
                    playlistId = channel.playlistId,
                    streamUrl = channel.streamUrl,
                    name = channel.name,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            true
        }
    }

    // ------------------------------------------------------------------ adding playlists

    suspend fun addXtream(
        name: String,
        server: String,
        user: String,
        pass: String,
        format: String,
        epgUrl: String?,
    ): Long = withContext(Dispatchers.IO) {
        val normalized = xtream.normalizeServer(server)
        xtream.authenticate(normalized, user.trim(), pass.trim())
        val playlist = Playlist(
            name = name.trim().ifBlank { hostName(normalized) ?: "Xtream playlist" },
            type = TYPE_XTREAM,
            url = normalized,
            username = user.trim(),
            password = pass.trim(),
            format = if (format == "m3u8") "m3u8" else "ts",
            epgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() },
        )
        val id = db.playlistDao().insert(playlist)
        try {
            importXtream(playlist.copy(id = id))
        } catch (e: Exception) {
            db.playlistDao().delete(id)
            throw e
        }
        id
    }

    suspend fun addM3u(name: String, url: String, epgUrl: String?): Long = withContext(Dispatchers.IO) {
        val cleaned = withScheme(url.trim())
        val playlist = Playlist(
            name = name.trim().ifBlank { hostName(cleaned) ?: "M3U playlist" },
            type = TYPE_M3U,
            url = cleaned,
            epgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { withScheme(it) },
        )
        val id = db.playlistDao().insert(playlist)
        try {
            importM3u(playlist.copy(id = id))
        } catch (e: Exception) {
            db.playlistDao().delete(id)
            throw e
        }
        id
    }

    suspend fun refresh(playlistId: Long) {
        withContext(Dispatchers.IO) {
            val p = db.playlistDao().get(playlistId) ?: return@withContext
            if (p.type == TYPE_XTREAM) importXtream(p) else importM3u(p)
        }
    }

    suspend fun delete(playlistId: Long) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.programmeDao().deleteForPlaylist(playlistId)
                db.programmeDao().deleteForPlaylist(-playlistId)
                db.favoriteDao().deleteForPlaylist(playlistId)
                db.playlistDao().delete(playlistId) // categories and channels cascade
            }
        }
    }

    private fun withScheme(s: String): String =
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "http://$s"

    private fun hostName(url: String): String? =
        url.substringAfter("://").substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ importing channels

    private suspend fun importXtream(p: Playlist) {
        val server = p.url
        val user = p.username.orEmpty()
        val pass = p.password.orEmpty()

        // Download first, replace afterwards, so a failed refresh never wipes a working playlist.
        val cats = xtream.liveCategories(server, user, pass)
        val streams = xtream.liveStreams(server, user, pass)
        if (streams.isEmpty()) error("This account has no live channels.")

        val categoryEntities = cats.mapIndexed { i, c ->
            Category(playlistId = p.id, remoteId = c.id, name = c.name.ifBlank { "Other" }, sortOrder = i)
        }

        db.withTransaction {
            db.channelDao().deleteForPlaylist(p.id)
            db.categoryDao().deleteForPlaylist(p.id)

            val ids = db.categoryDao().insertAll(categoryEntities)
            val categoryIdByRemote = HashMap<String, Long>()
            cats.forEachIndexed { i, c -> categoryIdByRemote[c.id] = ids[i] }

            streams.chunked(1000).forEachIndexed { chunkIndex, chunk ->
                val rows = chunk.mapIndexed { i, s ->
                    Channel(
                        playlistId = p.id,
                        categoryId = s.categoryId?.let { categoryIdByRemote[it] },
                        number = chunkIndex * 1000 + i + 1,
                        name = s.name.ifBlank { "Channel ${s.id}" },
                        logo = s.icon?.takeIf { it.isNotBlank() },
                        streamUrl = xtream.streamUrl(server, user, pass, s.id, p.format),
                        epgId = s.epgId?.takeIf { it.isNotBlank() },
                    )
                }
                db.channelDao().insertAll(rows)
            }

            db.playlistDao().update(p.copy(lastUpdated = System.currentTimeMillis()))
        }
    }

    private suspend fun importM3u(p: Playlist) {
        val request = Request.Builder().url(p.url).header("User-Agent", USER_AGENT).build()
        val result = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The server answered with HTTP ${response.code}.")
            val body = response.body ?: error("The server sent an empty answer.")
            body.charStream().buffered().use { M3uParser.parse(it) }
        }
        if (result.channels.isEmpty()) error("No channels found. Check that the link is an M3U playlist.")

        fun ParsedChannel.groupName(): String = group ?: "Uncategorized"

        val groups = result.channels.map { it.groupName() }.distinct()
        val categoryEntities = groups.mapIndexed { i, g ->
            Category(playlistId = p.id, remoteId = g, name = g, sortOrder = i)
        }

        db.withTransaction {
            db.channelDao().deleteForPlaylist(p.id)
            db.categoryDao().deleteForPlaylist(p.id)

            val ids = db.categoryDao().insertAll(categoryEntities)
            val categoryIdByGroup = HashMap<String, Long>()
            groups.forEachIndexed { i, g -> categoryIdByGroup[g] = ids[i] }

            result.channels.chunked(1000).forEachIndexed { chunkIndex, chunk ->
                val rows = chunk.mapIndexed { i, c ->
                    Channel(
                        playlistId = p.id,
                        categoryId = categoryIdByGroup[c.groupName()],
                        number = chunkIndex * 1000 + i + 1,
                        name = c.name,
                        logo = c.logo,
                        streamUrl = c.url,
                        epgId = c.epgId,
                    )
                }
                db.channelDao().insertAll(rows)
            }

            db.playlistDao().update(
                p.copy(
                    epgUrl = p.epgUrl?.takeIf { it.isNotBlank() } ?: result.epgUrl,
                    lastUpdated = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ TV guide

    private fun guideUrlFor(p: Playlist): String? {
        p.epgUrl?.takeIf { it.isNotBlank() }?.let { return it }
        if (p.type == TYPE_XTREAM) {
            return xtream.epgUrl(p.url, p.username.orEmpty(), p.password.orEmpty())
        }
        return null
    }

    fun hasGuideSource(p: Playlist): Boolean = guideUrlFor(p) != null

    /**
     * Downloads and stores the guide for one playlist. Returns the number of programmes stored,
     * or -1 when the playlist has no guide link.
     *
     * New rows are written under a temporary negative playlist id and swapped in at the end,
     * so the old guide stays visible while the new one downloads.
     */
    suspend fun refreshGuide(playlistId: Long): Int = guideLock.withLock {
        withContext(Dispatchers.IO) {
            val p = db.playlistDao().get(playlistId) ?: return@withContext 0
            val url = guideUrlFor(p) ?: return@withContext -1

            val now = System.currentTimeMillis()
            val from = now - 3 * HOUR
            val to = now + 48 * HOUR
            val staging = -playlistId
            val programmeDao = db.programmeDao()
            programmeDao.deleteForPlaylist(staging)

            val wanted = HashSet(db.channelDao().epgIds(playlistId))
            val unmatched = HashMap<String, MutableList<Long>>()
            for (c in db.channelDao().withoutEpg(playlistId)) {
                val key = EpgParser.normalizeName(c.name)
                if (key.isNotEmpty()) unmatched.getOrPut(key) { ArrayList() }.add(c.id)
            }
            val newMappings = HashMap<Long, String>()
            val batch = ArrayList<Programme>(2000)
            var total = 0

            fun flush() {
                if (batch.isEmpty()) return
                programmeDao.insertBlocking(ArrayList(batch))
                total += batch.size
                batch.clear()
            }

            try {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("The guide server answered with HTTP ${response.code}.")
                    val body = response.body ?: error("The guide server sent an empty answer.")
                    val raw = BufferedInputStream(body.byteStream(), 64 * 1024)
                    raw.mark(4)
                    val b1 = raw.read()
                    val b2 = raw.read()
                    raw.reset()
                    val input = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
                    input.use {
                        EpgParser.parse(
                            input = it,
                            onChannel = { id, names ->
                                if (id !in wanted && unmatched.isNotEmpty()) {
                                    var matched = false
                                    for (n in names) {
                                        val ids = unmatched.remove(EpgParser.normalizeName(n)) ?: continue
                                        ids.forEach { channelId -> newMappings[channelId] = id }
                                        matched = true
                                    }
                                    if (matched) wanted.add(id)
                                }
                            },
                            onProgramme = { channel, start, stop, title, description ->
                                if (stop > from && start < to && channel in wanted) {
                                    batch.add(
                                        Programme(
                                            playlistId = staging,
                                            channelEpgId = channel,
                                            start = start,
                                            stop = stop,
                                            title = title.ifBlank { "Untitled" },
                                            description = description,
                                        ),
                                    )
                                    if (batch.size >= 2000) flush()
                                }
                            },
                        )
                    }
                }
                flush()
            } catch (e: Exception) {
                programmeDao.deleteForPlaylist(staging)
                throw e
            }

            if (total == 0) {
                programmeDao.deleteForPlaylist(staging)
                error("The guide downloaded, but none of its channels match this playlist.")
            }

            db.withTransaction {
                programmeDao.deleteForPlaylist(playlistId)
                programmeDao.movePlaylist(staging, playlistId)
                newMappings.forEach { (channelId, epgId) -> db.channelDao().setEpgId(channelId, epgId) }
                programmeDao.deleteEndedBefore(now - 6 * HOUR)
                db.playlistDao().setEpgUpdated(playlistId, System.currentTimeMillis())
            }
            total
        }
    }

    companion object {
        const val HOUR = 3_600_000L
    }
}
