package com.zapp.tv.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.zapp.tv.BuildConfig
import com.zapp.tv.ZappApp
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.FAVORITES
import com.zapp.tv.data.GuideInfo
import com.zapp.tv.data.ProgrammeHit
import com.zapp.tv.data.Playlist
import com.zapp.tv.data.RECENT
import com.zapp.tv.data.Programme
import com.zapp.tv.data.Scope
import com.zapp.tv.data.SetupServer
import com.zapp.tv.data.TYPE_XTREAM
import com.zapp.tv.data.Updater
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val release: Updater.Release, val progress: Float) : UpdateState
    data class Failed(val message: String, val release: Updater.Release?) : UpdateState
}

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

    /** Guide grid: "On now only" filter, remembered between visits. */
    private val liveOnlyFlow = MutableStateFlow(settings.guideLiveOnly)
    val guideLiveOnly: StateFlow<Boolean> = liveOnlyFlow

    fun toggleGuideLiveOnly() {
        val v = !liveOnlyFlow.value
        liveOnlyFlow.value = v
        settings.guideLiveOnly = v
    }

    val guideChannels: Flow<PagingData<ChannelRow>> = combine(scopeFlow.filterNotNull(), liveOnlyFlow) { s, live -> s to live }
        .flatMapLatest { (s, live) -> repo.guideChannels(s, live) }
        .cachedIn(viewModelScope)

    suspend fun guideInfo(playlistId: Long): GuideInfo = repo.guideInfo(playlistId)

    suspend fun guideUrl(playlistId: Long): String = repo.guideUrlShown(playlistId)

    fun saveGuideUrl(playlistId: Long, url: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.setGuideUrl(playlistId, url)
            onDone()
            if (url.isBlank()) showToast("Guide link removed") else refreshGuide(listOf(playlistId), manual = true)
        }
    }

    // ------------------------------------------------------------ search

    data class SearchResults(val query: String, val channels: List<ChannelRow>, val programmes: List<ProgrammeHit>)

    private val searchQuery = MutableStateFlow("")
    var searchText by mutableStateOf("")
        private set

    fun setSearch(text: String) {
        searchText = text
        searchQuery.value = text
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val searchResults: StateFlow<SearchResults?> = searchQuery
        .debounce(300)
        .map { it.trim() }
        .distinctUntilChanged()
        .map { q ->
            if (q.length < 2) {
                null
            } else {
                val prefer = scopeFlow.value?.playlistId ?: -1L
                SearchResults(q, repo.searchChannels(q, prefer), repo.searchProgrammes(q))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun channelById(id: Long): Channel? = repo.channel(id)

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
                        add(CategoryItem(RECENT, "Recently watched", -1))
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

    // ------------------------------------------------------------ home rows

    /** Ticks once a minute so "on now" rows move on. */
    private val minute = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val homeRecent: StateFlow<List<ChannelRow>> = minute
        .flatMapLatest { now -> repo.recentRows(now, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val homeFavorites: StateFlow<List<ChannelRow>> = minute
        .flatMapLatest { now -> repo.favoriteRows(now, 30) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val homeOnNow: StateFlow<List<ChannelRow>> = combine(minute, scopeFlow.map { it?.playlistId }.distinctUntilChanged()) { now, pid -> now to pid }
        .flatMapLatest { (now, pid) -> if (pid == null) flowOf(emptyList()) else repo.onNowRows(pid, now, 30) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Bring older data up to date (encrypt logins) before anything reads it.
            repo.upgradeStoredData()
            repo.playlists.collect { list ->
                repo.cacheLogins(list)
                loaded = true
                ensureScope(list)
            }
        }
        // Quietly look for a new app version at most every six hours.
        viewModelScope.launch {
            delay(8_000)
            if (System.currentTimeMillis() - settings.lastUpdateCheck > 6 * 3_600_000L) checkForUpdates(manual = false)
        }
        // Automatic guide updates, checked at start and every 30 minutes while the app is open.
        viewModelScope.launch {
            delay(5_000)
            while (true) {
                val hours = settings.guideHours
                if (hours > 0) {
                    val stale = repo.playlistsOnce().filter { System.currentTimeMillis() - it.epgUpdated > hours * 3_600_000L }
                    refreshGuide(stale.map { it.id }, manual = false)
                }
                delay(30 * 60_000L)
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

    /** Jump straight to a list, e.g. from the Home screen, so zapping follows that list. */
    fun setScope(playlistId: Long, categoryId: Long?) {
        if (settings.currentPlaylistId != playlistId) settings.currentPlaylistId = playlistId
        scopeFlow.value = com.zapp.tv.data.Scope(playlistId, categoryId)
    }

    fun resolveUrl(channel: Channel): String = repo.playableUrl(channel)

    fun recordWatched(channel: Channel) {
        viewModelScope.launch { repo.recordWatched(channel) }
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

    // ------------------------------------------------------------ app updates

    val currentBuild: Int = BuildConfig.VERSION_CODE

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private var downloadedUpdate: Pair<Int, File>? = null

    fun checkForUpdates(manual: Boolean) {
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        update = UpdateState.Checking
        viewModelScope.launch {
            update = try {
                val release = zapp.updater.latest()
                settings.lastUpdateCheck = System.currentTimeMillis()
                if (release.build > currentBuild) {
                    if (!manual) showToast("An update is ready. Install it from Settings.")
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(e.message ?: "Couldn't check for updates.", null) else UpdateState.Idle
            }
        }
    }

    fun installUpdate() {
        val release = when (val u = update) {
            is UpdateState.Available -> u.release
            is UpdateState.Failed -> u.release
            else -> null
        } ?: return
        viewModelScope.launch {
            try {
                val cached = downloadedUpdate
                val file = if (cached != null && cached.first == release.build && cached.second.exists()) {
                    cached.second
                } else {
                    update = UpdateState.Downloading(release, 0f)
                    zapp.updater.download(release) { p ->
                        viewModelScope.launch(Dispatchers.Main) { update = UpdateState.Downloading(release, p) }
                    }.also { downloadedUpdate = release.build to it }
                }
                update = when (zapp.updater.install(file)) {
                    Updater.InstallResult.Started -> UpdateState.Available(release)
                    Updater.InstallResult.NeedsPermission -> UpdateState.Failed(
                        "Zapp needs permission to install updates. Allow it on the screen that opened, then press Install again. " +
                            "On Fire TV: Settings, My Fire TV, Developer options, Install unknown apps, Zapp.",
                        release,
                    )
                    Updater.InstallResult.NoInstaller -> UpdateState.Failed(
                        "This TV has no app installer. Use the Downloader app with the link below instead.",
                        release,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update = UpdateState.Failed(e.message ?: "The update couldn't be downloaded.", release)
            }
        }
    }

    // ------------------------------------------------------------ crash reports

    var lastCrash by mutableStateOf(com.zapp.tv.data.CrashReporter.read(app))
        private set

    fun clearCrash() {
        com.zapp.tv.data.CrashReporter.clear(getApplication())
        lastCrash = null
    }

    override fun crashReport(): String? = lastCrash

    init {
        if (lastCrash != null) {
            viewModelScope.launch {
                delay(2_500)
                showToast("Zapp closed unexpectedly last time. The report is in Settings, Updates and about.")
            }
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
        zapp.media.installedAddons().forEach {
            array.put(JSONObject().put("name", it.manifest.name).put("type", "addon"))
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
                "debrid" -> {
                    val key = body.optString("debridKey").trim()
                    if (key.length < 8) fail("Paste the API key first.")
                    when (body.optString("debridService")) {
                        "pm" -> settings.setSecret("pmKey", key)
                        "tb" -> settings.setSecret("tbKey", key)
                        else -> fail("Choose Premiumize or TorBox.")
                    }
                    val name = if (body.optString("debridService") == "pm") "Premiumize" else "TorBox"
                    showToast("$name connected from your phone")
                    return@withContext "$name is connected. Torrent sources will play through it."
                }
                "stremio" -> {
                    val email = body.optString("stremioEmail").trim()
                    val pass = body.optString("stremioPassword")
                    if (email.isBlank() || pass.isBlank()) fail("Enter your Stremio email and password.")
                    zapp.stremio.signIn(email, pass)
                    onStremioSignedIn?.invoke()
                    showToast("Stremio connected from your phone")
                    return@withContext "Signed in to Stremio. Your library and Continue watching are coming across now."
                }
                "simkl" -> {
                    val id = body.optString("simklClientId").filter { it.isLetterOrDigit() }
                    if (id.length < 10) fail("That doesn't look like a Simkl Client ID.")
                    settings.simklClientId = id
                    showToast("Simkl app ID saved. Connect Simkl in Settings, Accounts and sync.")
                    return@withContext "Saved. On your TV, go to Settings, Accounts and sync, then Connect Simkl."
                }
                "addon" -> {
                    val link = body.optString("addonUrl")
                    if (link.isBlank()) fail("Paste the add-on install link first.")
                    val addon = zapp.media.addAddon(link)
                    showToast("Installed ${addon.manifest.name} from your phone")
                    return@withContext "Installed ${addon.manifest.name}. Its catalogues are on your TV now."
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

    /** Set by the screen so a Stremio sign-in from the phone starts a sync straight away. */
    var onStremioSignedIn: (() -> Unit)? = null

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    override fun onCleared() {
        setupServer.stop()
        super.onCleared()
    }
}
