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
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

const val FAVORITES = -2L
const val RECENT = -3L

/** What a channel list shows: one playlist, and all channels, a category, favourites or recently watched. */
data class GuideInfo(val hasSource: Boolean, val programmes: Int, val updated: Long)

data class Scope(val playlistId: Long, val categoryId: Long?) {
    val favorites: Boolean get() = categoryId == FAVORITES
    val recent: Boolean get() = categoryId == RECENT
}

private data class XtreamLogin(val server: String, val user: String, val pass: String)

class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
) {
    private val xtream = XtreamClient(http)
    private val guideLock = Mutex()

    @Volatile
    private var activeSource: PagingSource<Int, ChannelRow>? = null

    /** Decrypted Xtream logins, kept in memory only, used to build playable links. */
    private val logins = ConcurrentHashMap<Long, XtreamLogin>()

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
            val source = when {
                scope.favorites -> dao.pagedFavoriteRows(scope.playlistId, now)
                scope.recent -> dao.pagedRecentRows(scope.playlistId, now)
                else -> dao.pagedRows(scope.playlistId, scope.categoryId, now)
            }
            activeSource = source
            source
        }.flow

    /** Re-reads the visible page so "now playing" titles move on, without losing the scroll position. */
    fun refreshNowPlaying() {
        activeSource?.invalidate()
    }

    suspend fun neighbor(scope: Scope, current: Channel, forward: Boolean): Channel? {
        if (scope.favorites || scope.recent) {
            val list =
                if (scope.favorites) db.channelDao().favoriteChannels(scope.playlistId)
                else db.channelDao().recentChannels(scope.playlistId)
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
        if (scope.recent) {
            return db.channelDao().recentChannels(scope.playlistId).indexOfFirst { it.streamUrl == streamUrl }
        }
        val channel = db.channelDao().findInScope(scope.playlistId, scope.categoryId, streamUrl) ?: return -1
        return db.channelDao().indexOf(scope.playlistId, scope.categoryId, channel.number)
    }

    // ------------------------------------------------------------------ guide lookups

    suspend fun searchChannels(query: String, preferPlaylist: Long): List<ChannelRow> =
        db.channelDao().searchRows(query.trim(), preferPlaylist, System.currentTimeMillis(), 60)

    suspend fun searchProgrammes(query: String): List<ProgrammeHit> =
        db.programmeDao().search(query.trim(), System.currentTimeMillis(), 40)

    suspend fun channel(id: Long): Channel? = db.channelDao().get(id)

    /** Guide channels: everything in the list, or only channels with a programme on now. */
    fun guideChannels(scope: Scope, liveOnly: Boolean): Flow<PagingData<ChannelRow>> {
        if (!liveOnly || scope.favorites || scope.recent) return channels(scope)
        return Pager(
            PagingConfig(pageSize = 50, prefetchDistance = 50, initialLoadSize = 100, enablePlaceholders = true, jumpThreshold = 150),
        ) {
            db.channelDao().pagedOnNowRows(scope.playlistId, scope.categoryId, System.currentTimeMillis())
        }.flow
    }

    /** What the guide has for a playlist, so screens can explain an empty guide. */
    suspend fun guideInfo(playlistId: Long): GuideInfo {
        val p = db.playlistDao().get(playlistId) ?: return GuideInfo(hasSource = false, programmes = 0, updated = 0)
        return GuideInfo(
            hasSource = guideUrlFor(p) != null,
            programmes = db.programmeDao().count(playlistId),
            updated = p.epgUpdated,
        )
    }

    suspend fun setGuideUrl(playlistId: Long, url: String) {
        val trimmed = url.trim()
        db.playlistDao().setEpgUrl(playlistId, if (trimmed.isEmpty()) null else Secrets.encrypt(withScheme(trimmed)))
    }

    suspend fun guideUrlShown(playlistId: Long): String =
        db.playlistDao().get(playlistId)?.epgUrl?.let { Secrets.decrypt(it) }.orEmpty()

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

    // ------------------------------------------------------------------ playing

    /** Turns a stored link into one the player can open. Xtream logins are only added here. */
    fun playableUrl(channel: Channel): String {
        val url = channel.streamUrl
        if (!url.startsWith("xt:")) return url
        val login = logins[channel.playlistId] ?: return url
        return xtream.livePrefix(login.server, login.user, login.pass) + url.removePrefix("xt:")
    }

    /** Whether this past programme can be replayed on its channel. */
    fun canCatchUp(channel: Channel, start: Long, stop: Long, now: Long = System.currentTimeMillis()): Boolean =
        channel.catchupDays > 0 && stop <= now && start >= now - channel.catchupDays * 86_400_000L &&
            (channel.streamUrl.startsWith("xt:") || channel.catchupSource != null)

    /** The replay link for a past programme, or null when the channel has no catch-up. */
    fun catchupUrl(channel: Channel, start: Long, stop: Long): String? {
        val minutes = ((stop - start) / 60_000).coerceAtLeast(1)
        val url = channel.streamUrl
        if (url.startsWith("xt:")) {
            val login = logins[channel.playlistId] ?: return null
            val id = url.removePrefix("xt:").substringBefore('.')
            val at = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).format(start)
            return "${login.server}/timeshift/${android.net.Uri.encode(login.user)}/${android.net.Uri.encode(login.pass)}/$minutes/$at/$id.ts"
        }
        val source = channel.catchupSource ?: return null
        if (source == "flussonic") {
            val base = url.substringBeforeLast('/')
            return "$base/archive-${start / 1000}-${(stop - start) / 1000}.m3u8"
        }
        val template = if (source.startsWith("append:")) url + source.removePrefix("append:") else source
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        fun two(n: Int) = n.toString().padStart(2, '0')
        return template
            .replace("{utc}", (start / 1000).toString())
            .replace("\${start}", (start / 1000).toString())
            .replace("{start}", (start / 1000).toString())
            .replace("{utcend}", (stop / 1000).toString())
            .replace("\${end}", (stop / 1000).toString())
            .replace("{end}", (stop / 1000).toString())
            .replace("{lutc}", (System.currentTimeMillis() / 1000).toString())
            .replace("\${timestamp}", (System.currentTimeMillis() / 1000).toString())
            .replace("{duration}", ((stop - start) / 1000).toString())
            .replace("\${duration}", ((stop - start) / 1000).toString())
            .replace("{Y}", cal.get(java.util.Calendar.YEAR).toString())
            .replace("{m}", two(cal.get(java.util.Calendar.MONTH) + 1))
            .replace("{d}", two(cal.get(java.util.Calendar.DAY_OF_MONTH)))
            .replace("{H}", two(cal.get(java.util.Calendar.HOUR_OF_DAY)))
            .replace("{M}", two(cal.get(java.util.Calendar.MINUTE)))
            .replace("{S}", two(cal.get(java.util.Calendar.SECOND)))
    }

    /** Keeps the in-memory login cache in step with the database. */
    fun cacheLogins(playlists: List<Playlist>) {
        val ids = HashSet<Long>()
        for (p in playlists) {
            if (p.type != TYPE_XTREAM) continue
            ids.add(p.id)
            logins[p.id] = XtreamLogin(p.url, Secrets.decrypt(p.username).orEmpty(), Secrets.decrypt(p.password).orEmpty())
        }
        logins.keys.retainAll(ids)
    }

    suspend fun recordWatched(channel: Channel) {
        db.recentDao().upsert(
            RecentChannel(
                playlistId = channel.playlistId,
                streamUrl = channel.streamUrl,
                name = channel.name,
                logo = channel.logo,
                watchedAt = System.currentTimeMillis(),
            ),
        )
        db.recentDao().trim(40)
    }

    fun recentRows(now: Long, limit: Int): Flow<List<ChannelRow>> = db.channelDao().observeRecentRows(now, limit)

    fun favoriteRows(now: Long, limit: Int): Flow<List<ChannelRow>> = db.channelDao().observeFavoriteRows(now, limit)

    fun onNowRows(playlistId: Long, now: Long, limit: Int): Flow<List<ChannelRow>> =
        db.channelDao().observeOnNowRows(playlistId, now, limit)

    // ------------------------------------------------------------------ one-time upgrade

    /**
     * Brings data from older versions up to date without losing anything:
     * encrypts stored logins and replaces logins inside Xtream links with short tokens.
     * Safe to run on every start; it only changes what still needs changing.
     */
    suspend fun upgradeStoredData() = withContext(Dispatchers.IO) {
        for (p in db.playlistDao().getAll()) {
            try {
                if (p.type == TYPE_XTREAM) {
                    val user = Secrets.decrypt(p.username).orEmpty()
                    val pass = Secrets.decrypt(p.password).orEmpty()
                    if (user.isNotEmpty() || pass.isNotEmpty()) {
                        val prefix = xtream.livePrefix(p.url, user, pass)
                        db.withTransaction {
                            db.channelDao().tokenizeStreams(p.id, prefix)
                            db.favoriteDao().tokenizeStreams(p.id, prefix)
                            db.recentDao().tokenizeStreams(p.id, prefix)
                        }
                    }
                    if (!Secrets.isEncrypted(p.username) || !Secrets.isEncrypted(p.password)) {
                        db.playlistDao().update(
                            p.copy(username = Secrets.encrypt(p.username), password = Secrets.encrypt(p.password), epgUrl = Secrets.encrypt(p.epgUrl)),
                        )
                    }
                } else if (!Secrets.isEncrypted(p.url) || (p.epgUrl != null && !Secrets.isEncrypted(p.epgUrl))) {
                    db.playlistDao().update(p.copy(url = Secrets.encrypt(p.url) ?: p.url, epgUrl = Secrets.encrypt(p.epgUrl)))
                }
            } catch (e: Exception) {
                // Leave this playlist as it was; it still works with plain values.
            }
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
            username = Secrets.encrypt(user.trim()),
            password = Secrets.encrypt(pass.trim()),
            format = if (format == "m3u8") "m3u8" else "ts",
            epgUrl = Secrets.encrypt(epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { withScheme(it) }),
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
            url = Secrets.encrypt(cleaned) ?: cleaned,
            epgUrl = Secrets.encrypt(epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { withScheme(it) }),
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
            logins.remove(playlistId)
            db.withTransaction {
                db.programmeDao().deleteForPlaylist(playlistId)
                db.programmeDao().clearStaging(playlistId)
                db.favoriteDao().deleteForPlaylist(playlistId)
                db.recentDao().deleteForPlaylist(playlistId)
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
        val user = Secrets.decrypt(p.username).orEmpty()
        val pass = Secrets.decrypt(p.password).orEmpty()
        if (user.isEmpty() || pass.isEmpty()) error("The saved login can't be read. Remove this playlist and add it again.")
        logins[p.id] = XtreamLogin(server, user, pass)

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
                        streamUrl = "xt:${s.id}.${p.format}",
                        epgId = s.epgId?.takeIf { it.isNotBlank() },
                        catchupDays = s.archiveDays,
                    )
                }
                db.channelDao().insertAll(rows)
            }

            db.playlistDao().update(p.copy(lastUpdated = System.currentTimeMillis()))
        }
    }

    private suspend fun importM3u(p: Playlist) {
        val link = Secrets.decrypt(p.url).orEmpty()
        if (link.isEmpty()) error("The saved playlist link can't be read. Remove this playlist and add it again.")
        val request = Request.Builder().url(link).header("User-Agent", USER_AGENT).build()
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
                        catchupDays = c.catchupDays,
                        catchupSource = c.catchupSource,
                    )
                }
                db.channelDao().insertAll(rows)
            }

            db.playlistDao().update(
                p.copy(
                    epgUrl = p.epgUrl?.takeIf { it.isNotBlank() } ?: Secrets.encrypt(result.epgUrl),
                    lastUpdated = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ TV guide

    private fun guideUrlFor(p: Playlist): String? {
        Secrets.decrypt(p.epgUrl)?.takeIf { it.isNotBlank() }?.let { return it }
        if (p.type == TYPE_XTREAM) {
            return xtream.epgUrl(p.url, Secrets.decrypt(p.username).orEmpty(), Secrets.decrypt(p.password).orEmpty())
        }
        return null
    }

    fun hasGuideSource(p: Playlist): Boolean = guideUrlFor(p) != null

    /**
     * Downloads and stores the guide for one playlist. Returns the number of programmes stored,
     * or -1 when the playlist has no guide link.
     *
     * New rows go into a separate staging table and are swapped in with one transaction at the end:
     * the old guide stays visible meanwhile, and open channel lists only reload once.
     */
    suspend fun refreshGuide(playlistId: Long): Int = guideLock.withLock {
        withContext(Dispatchers.IO) {
            val p = db.playlistDao().get(playlistId) ?: return@withContext 0
            val url = guideUrlFor(p) ?: return@withContext -1

            val now = System.currentTimeMillis()
            val from = now - 3 * HOUR
            val to = now + 48 * HOUR
            val programmeDao = db.programmeDao()
            programmeDao.clearStaging(playlistId)

            // Guide ids are matched without regard to case; programmes are stored under the channel's own spelling.
            val canonical = HashMap<String, String>()
            for (id in db.channelDao().epgIds(playlistId)) canonical[id.lowercase()] = id
            val unmatched = HashMap<String, MutableList<Long>>()
            for (c in db.channelDao().withoutEpg(playlistId)) {
                val key = EpgParser.normalizeName(c.name)
                if (key.isNotEmpty()) unmatched.getOrPut(key) { ArrayList() }.add(c.id)
            }
            val newMappings = HashMap<Long, String>()
            val batch = ArrayList<StagedProgramme>(2000)
            var total = 0

            fun flush() {
                if (batch.isEmpty()) return
                programmeDao.insertStagingBlocking(ArrayList(batch))
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
                                if (id.lowercase() !in canonical && unmatched.isNotEmpty()) {
                                    var matched = false
                                    for (n in names) {
                                        val ids = unmatched.remove(EpgParser.normalizeName(n)) ?: continue
                                        ids.forEach { channelId -> newMappings[channelId] = id }
                                        matched = true
                                    }
                                    if (matched) canonical[id.lowercase()] = id
                                }
                            },
                            onProgramme = { channel, start, stop, title, description, icon ->
                                val target = if (stop > from && start < to) canonical[channel.lowercase()] else null
                                if (target != null) {
                                    batch.add(
                                        StagedProgramme(
                                            playlistId = playlistId,
                                            channelEpgId = target,
                                            start = start,
                                            stop = stop,
                                            title = title.ifBlank { "Untitled" },
                                            description = description,
                                            icon = icon,
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
                programmeDao.clearStaging(playlistId)
                throw e
            }

            if (total == 0) {
                programmeDao.clearStaging(playlistId)
                error("The guide downloaded, but none of its channels match this playlist.")
            }

            db.withTransaction {
                programmeDao.deleteForPlaylist(playlistId)
                programmeDao.promoteStaging(playlistId)
                programmeDao.clearStaging(playlistId)
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
