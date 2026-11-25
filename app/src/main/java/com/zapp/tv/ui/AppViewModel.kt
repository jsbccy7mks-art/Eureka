package com.zapp.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.zapp.tv.ZappApp
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.FAVORITES
import com.zapp.tv.data.Playlist
import com.zapp.tv.data.Programme
import com.zapp.tv.data.Scope
import com.zapp.tv.data.SetupServer
import com.zapp.tv.data.TYPE_XTREAM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistItem(val playlist: Playlist, val channelCount: Int)

data class CategoryItem(val id: Long?, val name: String, val count: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app), SetupServer.Handler {

    private val zapp = app as ZappApp
    private val repo = zapp.repository
    val settings = zapp.settings
    val setupServer = SetupServer(app, this)

    // ------------------------------------------------------------ playlists

    val playlists: StateFlow<List<PlaylistItem>> =
        combine(repo.playlists, repo.channelCounts) { list, counts ->
            val byId = counts.associate { it.playlistId to it.total }
            list.map { PlaylistItem(it, byId[it.id] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** False until the database has answered once, so the welcome screen doesn't flash. */
    var loaded by mutableStateOf(false)
        private set

    // ------------------------------------------------------------ what is being browsed

    private val scopeFlow = MutableStateFlow<Scope?>(null)
    val scope: StateFlow<Scope?> = scopeFlow

    val channels: Flow<PagingData<ChannelRow>> = scopeFlow
        .filterNotNull()
        .flatMapLatest { repo.channels(it) }
        .cachedIn(viewModelScope)

    val categories: StateFlow<List<CategoryItem>> = scopeFlow
        .map { it?.playlistId }
        .distinctUntilChanged()
        .flatMapLatest { pid ->
            if (pid == null) {
                flowOf(emptyList())
            } else {
                combine(repo.categories(pid), repo.categoryCounts(pid), repo.favoriteCount(pid)) { cats, counts, favs ->
                    val byCategory = counts.associate { it.categoryId to it.total }
                    buildList {
                        add(CategoryItem(FAVORITES, "Favourites", favs))
                        add(CategoryItem(null, "All channels", counts.sumOf { it.total }))
                        cats.forEach { add(CategoryItem(it.id, it.name, byCategory[it.id] ?: 0)) }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last channel watched or focused, so lists can return to it. */
    var lastChannelUrl: String? = null

    // ------------------------------------------------------------ status

    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var guideStatus by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    fun showToast(text: String) {
        toast = ToastMessage(text, System.nanoTime())
    }

    fun clearError() {
        error = null
    }

    init {
        viewModelScope.launch {
            repo.playlists.collect { list ->
                loaded = true
                ensureScope(list)
            }
        }
        viewModelScope.launch {
            val list = repo.playlistsOnce()
            if (settings.autoGuide) {
                val stale = list.filter { System.currentTimeMillis() - it.epgUpdated > 12 * 3_600_000L }
                refreshGuide(stale.map { it.id }, manual = false)
            }
        }
        // Keep "now playing" titles current.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                repo.refreshNowPlaying()
            }
        }
    }

    private fun ensureScope(list: List<Playlist>) {
        if (list.isEmpty()) {
            scopeFlow.value = null
            return
        }
        val current = scopeFlow.value
        if (current == null || list.none { it.id == current.playlistId }) {
            val saved = settings.currentPlaylistId
            val pid = list.firstOrNull { it.id == saved }?.id ?: list.first().id
            scopeFlow.value = Scope(pid, null)
        }
    }

    fun selectPlaylist(id: Long) {
        settings.currentPlaylistId = id
        if (scopeFlow.value?.playlistId != id) scopeFlow.value = Scope(id, null)
    }

    fun nextPlaylist() {
        val list = playlists.value
        if (list.size < 2) return
        val i = list.indexOfFirst { it.playlist.id == scopeFlow.value?.playlistId }
        val next = list[(i + 1).mod(list.size)]
        selectPlaylist(next.playlist.id)
        showToast("Watching ${next.playlist.name}")
    }

    fun selectCategory(categoryId: Long?) {
        val s = scopeFlow.value ?: return
        if (s.categoryId != categoryId) scopeFlow.value = s.copy(categoryId = categoryId)
    }

    suspend fun neighbor(current: Channel, forward: Boolean): Channel? {
        val s = scopeFlow.value ?: return null
        return repo.neighbor(s, current, forward)
    }

    suspend fun indexInScope(streamUrl: String): Int {
        val s = scopeFlow.value ?: return -1
        return repo.indexInScope(s, streamUrl)
    }

    suspend fun nowNext(channel: Channel): List<Programme> = repo.nowNext(channel)

    suspend fun programmes(playlistId: Long, epgId: String, from: Long, to: Long): List<Programme> =
        repo.programmes(playlistId, epgId, from, to)

    suspend fun isFavorite(channel: Channel): Boolean = repo.isFavorite(channel)

    fun toggleFavorite(channel: Channel, onChanged: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val now = repo.toggleFavorite(channel)
            onChanged(now)
            showToast(if (now) "Added ${channel.name} to Favourites" else "Removed ${channel.name} from Favourites")
        }
    }

    // ------------------------------------------------------------ playlist actions

    private fun launchBusy(message: String, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy != null) return
        busy = message
        error = null
        viewModelScope.launch {
            try {
                block()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
            }
        }
    }

    fun addXtream(name: String, server: String, user: String, pass: String, format: String, epg: String, onDone: () -> Unit) =
        launchBusy("Downloading channels…", onDone) {
            val id = repo.addXtream(name, server, user, pass, format, epg)
            selectPlaylist(id)
            refreshGuide(listOf(id), manual = false)
        }

    fun addM3u(name: String, url: String, epg: String, onDone: () -> Unit) =
        launchBusy("Downloading channels…", onDone) {
            val id = repo.addM3u(name, url, epg)
            selectPlaylist(id)
            refreshGuide(listOf(id), manual = false)
        }

    fun refresh(playlistId: Long) = launchBusy("Refreshing channels…") {
        repo.refresh(playlistId)
        showToast("Channels refreshed")
        refreshGuide(listOf(playlistId), manual = false)
    }

    fun refreshAll() = launchBusy("Refreshing channels…") {
        val list = repo.playlistsOnce()
        list.forEach { repo.refresh(it.id) }
        showToast("All playlists refreshed")
        refreshGuide(list.map { it.id }, manual = false)
    }

    fun delete(playlistId: Long) = launchBusy("Removing playlist…") {
        repo.delete(playlistId)
        showToast("Playlist removed")
    }

    fun refreshGuideAll() {
        viewModelScope.launch { refreshGuide(repo.playlistsOnce().map { it.id }, manual = true) }
    }

    fun refreshGuideFor(playlistId: Long) = refreshGuide(listOf(playlistId), manual = true)

    /** Guide downloads run in the background; the top bar shows a small status while they do. */
    private fun refreshGuide(ids: List<Long>, manual: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            for (id in ids) {
                guideStatus = "Updating guide"
                try {
                    val n = repo.refreshGuide(id)
                    if (manual) {
                        showToast(if (n < 0) "This playlist has no guide link" else "Guide updated")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (manual) showToast("Guide update failed: ${e.message ?: "unknown error"}")
                } finally {
                    guideStatus = null
                }
            }
            repo.refreshNowPlaying()
        }
    }

    // ------------------------------------------------------------ phone setup

    fun startSetup() = setupServer.start(viewModelScope)

    fun stopSetup() = setupServer.stop()

    override suspend fun playlistsForPhone(): JSONArray {
        val array = JSONArray()
        repo.playlistsOnce().forEach {
            array.put(JSONObject().put("name", it.name).put("type", it.type))
        }
        return array
    }

    override suspend fun addFromPhone(body: JSONObject): String = withContext(Dispatchers.Main) {
        if (busy != null) fail("Your TV is busy right now. Try again in a moment.")
        busy = "Adding a playlist from your phone…"
        try {
            val name = body.optString("name")
            val epg = body.optString("epg")
            val id = when (body.optString("type")) {
                TYPE_XTREAM -> {
                    val server = body.optString("server")
                    val user = body.optString("username")
                    val pass = body.optString("password")
                    if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                        fail("Fill in the server address, username and password.")
                    }
                    repo.addXtream(name, server, user, pass, body.optString("format", "ts"), epg)
                }
                "m3u" -> {
                    val url = body.optString("url")
                    if (url.isBlank()) fail("Paste the playlist link first.")
                    repo.addM3u(name, url, epg)
                }
                else -> fail("Choose Xtream login or M3U link.")
            }
            val count = repo.channelCount(id)
            selectPlaylist(id)
            refreshGuide(listOf(id), manual = false)
            showToast("Playlist added from your phone")
            "Added ${formatCount(count)} channels. They're ready on your TV."
        } finally {
            busy = null
        }
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    override fun onCleared() {
        setupServer.stop()
        super.onCleared()
    }
}
