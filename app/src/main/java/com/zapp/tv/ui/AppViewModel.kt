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
import com.zapp.tv.data.ChannelSort
import com.zapp.tv.data.CategoryView
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistItem(val playlist: Playlist, val channelCount: Int)

/** A line in the category list. [ref] is the category's own id (for hiding and moving it). */
data class CategoryItem(val id: Long?, val name: String, val count: Int, val ref: String? = null, val hidden: Boolean = false)

/** The "Hidden categories" line at the bottom of the category list. */
const val HIDDEN_CATEGORIES = -4L

/** A programme you asked to be told about when it starts. Channel id and name only, no links. */
data class Reminder(val playlistId: Long, val channelId: Long, val channelName: String, val title: String, val start: Long, val stop: Long)

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

    /** How channel lists are ordered (Settings, Live TV): number, name or your own order. */
    private val sortFlow = MutableStateFlow(settings.channelSort)
    val channelSort: StateFlow<Int> = sortFlow

    fun setChannelSort(sort: Int) {
        settings.channelSort = sort
        sortFlow.value = sort
    }

    val channels: Flow<PagingData<ChannelRow>> = combine(scopeFlow.filterNotNull(), sortFlow) { s, sort -> s to sort }
        .flatMapLatest { (s, sort) -> repo.channels(s, sort) }
        .cachedIn(viewModelScope)

    /** Guide grid: "On now only" filter, remembered between visits. */
    private val liveOnlyFlow = MutableStateFlow(settings.guideLiveOnly)
    val guideLiveOnly: StateFlow<Boolean> = liveOnlyFlow

    fun toggleGuideLiveOnly() {
        val v = !liveOnlyFlow.value
        liveOnlyFlow.value = v
        settings.guideLiveOnly = v
    }

    val guideChannels: Flow<PagingData<ChannelRow>> = combine(scopeFlow.filterNotNull(), liveOnlyFlow, sortFlow) { s, live, sort -> Triple(s, live, sort) }
        .flatMapLatest { (s, live, sort) -> repo.guideChannels(s, live, sort) }
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

    // ------------------------------------------------------------ sports

    val sports get() = zapp.sports
    val football get() = zapp.football
    var followedTeams by mutableStateOf<List<com.zapp.tv.data.SportTeam>>(emptyList())
        private set
    var followedLeagues by mutableStateOf<List<String>>(emptyList())
        private set
    private var teamFixtures by mutableStateOf<List<com.zapp.tv.data.SportEvent>>(emptyList())
    private var leagueFixtures by mutableStateOf<List<com.zapp.tv.data.SportEvent>>(emptyList())

    /** Matches for the teams and leagues you follow. A team's match that's also in a followed league is shown once. */
    val fixtures: List<com.zapp.tv.data.SportEvent>
        get() {
            val leagues = leagueFixtures
            if (leagues.isEmpty()) return teamFixtures
            fun k(n: String?) = n?.lowercase()?.filter { it.isLetter() }?.take(4)
            val extra = teamFixtures.filterNot { t ->
                leagues.any { l -> kotlin.math.abs(l.start - t.start) < 45 * 60_000L && (k(l.home) == k(t.home) || k(l.away) == k(t.away)) }
            }
            return (leagues + extra).sortedBy { it.start }
        }
    var fixturesLoading by mutableStateOf(false)
        private set
    private var fixturesAt = 0L

    /**
     * Reloads what this profile follows and its matches. Teams are fetched at most every 10 minutes
     * unless asked; leagues are cached by [com.zapp.tv.data.FootballData] (a minute while anything is live).
     */
    fun refreshSports(force: Boolean = false) {
        followedTeams = sports.followed()
        followedLeagues = football.followed()
        if (followedTeams.isEmpty()) teamFixtures = emptyList()
        if (followedLeagues.isEmpty() || !football.hasKey) leagueFixtures = emptyList()
        val teamsDue = followedTeams.isNotEmpty() && (force || System.currentTimeMillis() - fixturesAt > 10 * 60_000L || teamFixtures.isEmpty())
        val leaguesDue = followedLeagues.isNotEmpty() && football.hasKey
        if (!teamsDue && !leaguesDue) return
        fixturesLoading = true
        viewModelScope.launch {
            try {
                if (leaguesDue) leagueFixtures = football.matches(followedLeagues, force)
                if (teamsDue) {
                    teamFixtures = sports.fixtures(followedTeams)
                    fixturesAt = System.currentTimeMillis()
                }
            } finally {
                fixturesLoading = false
            }
        }
    }

    /** Live scores: refetches the leagues (the client keeps calls inside the free plan's limit). */
    fun refreshLiveScores() {
        if (followedLeagues.isEmpty() || !football.hasKey) return
        viewModelScope.launch { leagueFixtures = football.matches(followedLeagues, force = true) }
    }

    fun followLeague(code: String, on: Boolean) {
        val list = football.followed().filter { it != code } + if (on) listOf(code) else emptyList()
        football.setFollowed(list)
        refreshSports(force = true)
    }

    /** Sets (or removes) a reminder for a match found in the guide. */
    fun remindHit(h: com.zapp.tv.data.ProgrammeHit) {
        viewModelScope.launch {
            val ch = repo.channel(h.channelId) ?: return@launch
            val on = reminders.any { it.playlistId == ch.playlistId && it.channelName == ch.name && it.start == h.start }
            reminders = if (on) {
                reminders.filterNot { it.playlistId == ch.playlistId && it.channelName == ch.name && it.start == h.start }
            } else {
                reminders + Reminder(ch.playlistId, ch.id, ch.name, h.title, h.start, h.stop)
            }
            saveReminders()
            val at = android.text.format.DateFormat.getTimeFormat(getApplication<Application>()).format(h.start)
            showToast(if (on) "Reminder removed" else "We'll remind you at $at when it starts on ${ch.name}")
        }
    }

    fun hasReminder(h: com.zapp.tv.data.ProgrammeHit): Boolean = reminders.any { it.channelId == h.channelId && it.start == h.start }

    fun follow(team: com.zapp.tv.data.SportTeam, on: Boolean) {
        val list = sports.followed().filter { it.id != team.id } + if (on) listOf(team) else emptyList()
        sports.setFollowed(list)
        refreshSports(force = true)
    }

    /** Channels in your guide showing this match. */
    suspend fun channelsFor(e: com.zapp.tv.data.SportEvent): List<com.zapp.tv.data.ProgrammeHit> =
        runCatching { repo.channelsFor(e.home, e.away, e.start) }.getOrDefault(emptyList())

    val categories: StateFlow<List<CategoryItem>> = scopeFlow
        .map { it?.playlistId }
        .distinctUntilChanged()
        .flatMapLatest { pid ->
            if (pid == null) {
                flowOf(emptyList())
            } else {
                combine(repo.categoryViews(pid), repo.categoryCounts(pid), repo.favoriteCount(pid)) { cats, counts, favs ->
                    val byCategory = counts.associate { it.categoryId to it.total }
                    val (hidden, shown) = cats.partition { it.hidden }
                    fun item(v: CategoryView) = CategoryItem(v.category.id, v.category.name, byCategory[v.category.id] ?: 0, v.category.remoteId, v.hidden)
                    buildList {
                        add(CategoryItem(RECENT, "Recently watched", -1))
                        add(CategoryItem(FAVORITES, "Favourites", favs))
                        // All channels leaves out hidden categories, so its count does too.
                        add(CategoryItem(null, "All channels", shown.sumOf { byCategory[it.category.id] ?: 0 } + (byCategory[null] ?: 0)))
                        shown.forEach { add(item(it)) }
                        if (hidden.isNotEmpty()) {
                            add(CategoryItem(HIDDEN_CATEGORIES, "Hidden categories", hidden.size))
                            hidden.forEach { add(item(it)) }
                        }
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

    fun showToast(text: String, durationMs: Long = 2600) {
        toast = ToastMessage(text, System.nanoTime(), durationMs)
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
        // Quietly look for a new app version 20 seconds after opening (once things have settled),
        // then every four hours while the app stays open.
        viewModelScope.launch {
            delay(20_000)
            while (true) {
                checkForUpdates(manual = false)
                delay(4 * 3_600_000L)
            }
        }
        viewModelScope.launch { watchReminders() }
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

    fun canCatchUp(channel: Channel, start: Long, stop: Long): Boolean = repo.canCatchUp(channel, start, stop)

    fun catchupUrl(channel: Channel, start: Long, stop: Long): String? = repo.catchupUrl(channel, start, stop)

    fun recordWatched(channel: Channel) {
        viewModelScope.launch { repo.recordWatched(channel) }
    }

    fun selectCategory(categoryId: Long?) {
        val s = scopeFlow.value ?: return
        if (s.categoryId != categoryId) scopeFlow.value = s.copy(categoryId = categoryId)
    }

    suspend fun neighbor(current: Channel, forward: Boolean): Channel? {
        val s = scopeFlow.value ?: return null
        return repo.neighbor(s, current, forward, sortFlow.value)
    }

    suspend fun indexInScope(streamUrl: String): Int {
        val s = scopeFlow.value ?: return -1
        return repo.indexInScope(s, streamUrl, sortFlow.value)
    }

    // ------------------------------------------------------------ hiding and moving

    fun categoryViews(playlistId: Long): Flow<List<CategoryView>> = repo.categoryViews(playlistId)

    fun hiddenChannels(playlistId: Long): Flow<List<Channel>> = repo.hiddenChannels(playlistId)

    fun setCategoryHidden(playlistId: Long, ref: String, name: String, hidden: Boolean, quiet: Boolean = false) {
        viewModelScope.launch {
            repo.setCategoryHidden(playlistId, ref, hidden)
            if (!quiet) showToast(if (hidden) "$name hidden. It's under Hidden categories." else "$name shown again")
        }
    }

    // Moves are done one at a time, so quick presses all count.
    private val moveLock = Mutex()

    fun moveCategory(playlistId: Long, ref: String, by: Int) {
        viewModelScope.launch { moveLock.withLock { repo.moveCategory(playlistId, ref, by) } }
    }

    fun resetCategoryOrder(playlistId: Long) {
        viewModelScope.launch {
            repo.resetCategoryOrder(playlistId)
            showToast("Categories are back in the playlist's order")
        }
    }

    fun setChannelHidden(channel: Channel, hidden: Boolean) {
        viewModelScope.launch {
            repo.setChannelHidden(channel, hidden)
            showToast(if (hidden) "${channel.name} hidden. Show it again in Settings, Live TV." else "${channel.name} shown again")
        }
    }

    /** Moving channels needs your own order; sorting by number switches to it (the order stays the same). */
    fun useOwnOrder() {
        if (sortFlow.value == ChannelSort.OWN) return
        setChannelSort(ChannelSort.OWN)
        showToast("Channels now follow your own order. You can change this in Settings, Live TV.", durationMs = 4_000)
    }

    /** Moves a channel one place up or down in the list you're in. True when it moved. */
    suspend fun moveChannel(channel: Channel, by: Int): Boolean {
        val s = scopeFlow.value ?: return false
        return moveLock.withLock { repo.moveChannel(s, channel, by) }
    }

    suspend fun lastWatchedChannel(): Channel? {
        val s = scopeFlow.value ?: return null
        return repo.lastWatched(s.playlistId)
    }

    // ------------------------------------------------------------ reminders

    var reminders by mutableStateOf(loadReminders())
        private set

    /** A reminder whose programme is starting, for the "switch over?" pop-up. */
    var dueReminder by mutableStateOf<Reminder?>(null)
        private set

    fun hasReminder(channel: Channel, p: Programme): Boolean =
        reminders.any { it.playlistId == channel.playlistId && it.channelName == channel.name && it.start == p.start }

    fun toggleReminder(channel: Channel, p: Programme) {
        val on = hasReminder(channel, p)
        reminders = if (on) {
            reminders.filterNot { it.playlistId == channel.playlistId && it.channelName == channel.name && it.start == p.start }
        } else {
            reminders + Reminder(channel.playlistId, channel.id, channel.name, p.title, p.start, p.stop)
        }
        saveReminders()
        val at = android.text.format.DateFormat.getTimeFormat(getApplication<Application>()).format(p.start)
        showToast(if (on) "Reminder removed" else "We'll remind you at $at when ${p.title} starts")
    }

    fun dismissReminder() {
        dueReminder = null
    }

    /** The channel a reminder is for (found by name if the playlist has been refreshed since). */
    suspend fun channelFor(r: Reminder): Channel? =
        repo.channel(r.channelId)?.takeIf { it.name == r.channelName } ?: repo.channelNamed(r.playlistId, r.channelName)

    private fun loadReminders(): List<Reminder> = runCatching {
        val a = JSONArray(settings.remindersJson.ifBlank { "[]" })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Reminder(o.getLong("p"), o.getLong("c"), o.getString("n"), o.getString("t"), o.getLong("s"), o.getLong("e"))
        }
    }.getOrDefault(emptyList())

    private fun saveReminders() {
        val a = JSONArray()
        reminders.forEach { r ->
            a.put(JSONObject().put("p", r.playlistId).put("c", r.channelId).put("n", r.channelName).put("t", r.title).put("s", r.start).put("e", r.stop))
        }
        settings.remindersJson = a.toString()
    }

    /** Fires reminders as their programmes start (half a minute early), and forgets ones that have ended. */
    private suspend fun watchReminders() {
        while (true) {
            // Waits first: this starts while the view model is still being built.
            delay(10_000)
            val now = System.currentTimeMillis()
            if (reminders.isNotEmpty()) {
                val due = reminders.filter { it.start - 30_000 <= now }
                if (due.isNotEmpty()) {
                    reminders = reminders - due.toSet()
                    saveReminders()
                    // Only ones still on air are worth a pop-up (not ones missed while the TV was off).
                    due.filter { it.stop > now && now - it.start < 15 * 60_000L }.maxByOrNull { it.start }?.let { dueReminder = it }
                }
            }
        }
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

    /** A new build was found by the background check and hasn't been mentioned yet. */
    var updateNotice by mutableStateOf(false)
        private set
    private var noticedBuild = -1

    /** Shows the "update ready" message (called once nothing is playing). */
    fun showUpdateNotice() {
        if (!updateNotice) return
        updateNotice = false
        showToast("A new version of Axon is ready. Install it from Settings, System.", durationMs = 8_000)
    }

    fun checkForUpdates(manual: Boolean) {
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        update = UpdateState.Checking
        viewModelScope.launch {
            update = try {
                val release = zapp.updater.latest()
                settings.lastUpdateCheck = System.currentTimeMillis()
                if (release.build > currentBuild) {
                    // Told once per new build; the message waits until nothing is playing.
                    if (!manual && noticedBuild != release.build) {
                        noticedBuild = release.build
                        updateNotice = true
                    }
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
                        "Axon needs permission to install updates. Allow it on the screen that opened, then press Install again. " +
                            "On Fire TV: Settings, My Fire TV, Developer options, Install unknown apps, Axon.",
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

    override fun connectionsForPhone(): JSONObject {
        fun st(on: Boolean, text: String) = JSONObject().put("on", on).put("text", text)
        val pid = settings.profileId
        val nuvio = zapp.nuvio
        val debrid = zapp.debrid.connected.map { it.name }
        return JSONObject()
            .put("stremio", if (zapp.stremio.signedIn) st(true, "Connected" + settings.stremioUser.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty()) else st(false, "Not connected"))
            .put("nuvio", if (nuvio.signedIn(pid)) st(true, "Connected" + nuvio.user(pid).takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty() + nuvio.linkedProfileName(pid).takeIf { it.isNotBlank() }?.let { ", profile $it" }.orEmpty()) else st(false, "Not connected"))
            .put("simkl", when {
                zapp.simkl.client.signedIn -> st(true, "Connected")
                settings.simklClientId.isNotBlank() -> st(false, "App ID saved. Connect Simkl on the TV (Settings, Accounts).")
                else -> st(false, "Not connected")
            })
            .put("debrid", if (debrid.isNotEmpty()) st(true, "Connected: " + debrid.joinToString(", ")) else st(false, "Not connected"))
            .put("tmdb", if (zapp.tmdb.enabled) st(true, "Connected") else st(false, "Not connected"))
            .put("mdblist", if (zapp.mdblist.enabled) st(true, "Connected") else st(false, "Not connected"))
            .put("seekr", when {
                !zapp.seekr.hasKey -> st(false, "Not connected")
                !settings.seekrOn -> st(false, "Key saved, switched off on the TV")
                else -> st(true, "Connected")
            })
            .put("football", if (zapp.football.hasKey) st(true, "Connected") else st(false, "Not connected"))
            .put("sportsdb", if (settings.secret("sportsDbKey").isNotBlank()) st(true, "Using your own key") else st(false, "Using the free shared key"))
    }

    init {
        if (lastCrash != null) {
            viewModelScope.launch {
                delay(2_500)
                showToast("Axon closed unexpectedly last time. The report is in Settings, System.")
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
                "tmdb" -> {
                    val key = body.optString("tmdbKey").trim()
                    if (key.length < 16) fail("Paste your TMDB API key or read access token.")
                    settings.setSecret("tmdbKey", key)
                    if (!zapp.tmdb.check()) {
                        settings.setSecret("tmdbKey", "")
                        fail("TMDB didn't accept that key. Copy it again from themoviedb.org/settings/api.")
                    }
                    showToast("TMDB connected from your phone")
                    return@withContext "TMDB is connected. Details pages now show cast photos, reviews and more."
                }
                "seekr" -> {
                    val key = body.optString("seekrKey").trim()
                    if (key.length < 12) fail("Paste your Seekr API key.")
                    if (!zapp.seekr.check(key)) fail("Seekr didn't accept that key. Copy it again from your Seekr account.")
                    settings.setSecret(com.zapp.tv.data.SeekrPreviews.KEY, key)
                    settings.seekrOn = true
                    zapp.seekr.keyChanged()
                    showToast("Seekr connected from your phone")
                    return@withContext "Seekr is connected. The seek bar now shows pictures while you skip through films and episodes."
                }
                "football" -> {
                    val key = body.optString("footballKey").trim()
                    if (key.length < 16) fail("Paste your football-data.org API token.")
                    if (!zapp.football.check(key)) fail("football-data.org didn't accept that token. Copy it again from the email they sent you.")
                    settings.setSecret(com.zapp.tv.data.FootballData.KEY, key)
                    if (zapp.football.followed().isEmpty()) zapp.football.setFollowed(listOf("PL", "ELC"))
                    refreshSports(force = true)
                    showToast("Football connected from your phone")
                    return@withContext "Connected. Sports now shows the Premier League and Championship. Choose more leagues on the TV under Sports, Leagues."
                }
                "sportsdb" -> {
                    val key = body.optString("sportsDbKey").trim()
                    if (key.length < 3) fail("Paste your TheSportsDB key.")
                    settings.setSecret("sportsDbKey", key)
                    refreshSports(force = true)
                    showToast("TheSportsDB key saved")
                    return@withContext "Saved. Sports now uses your own TheSportsDB key."
                }
                "mdblist" -> {
                    val key = body.optString("mdblistKey").trim()
                    if (key.length < 10) fail("Paste your MDBList API key.")
                    settings.setSecret("mdblistKey", key)
                    if (!zapp.mdblist.check()) {
                        settings.setSecret("mdblistKey", "")
                        fail("MDBList didn't accept that key. Copy it again from mdblist.com/preferences.")
                    }
                    showToast("MDBList connected from your phone")
                    return@withContext "MDBList is connected. Details pages now show IMDb, Rotten Tomatoes, Metacritic and Letterboxd scores."
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
                "nuvio" -> {
                    val email = body.optString("nuvioEmail").trim()
                    val pass = body.optString("nuvioPassword")
                    if (email.isBlank() || pass.isBlank()) fail("Enter your Nuvio email and password.")
                    val pid = settings.profileId
                    zapp.nuvio.signIn(pid, body.optString("nuvioServer"), email, pass)
                    onNuvioSignedIn?.invoke()
                    showToast("Nuvio connected from your phone")
                    return@withContext if (zapp.nuvio.linkedProfile(pid) != 0) {
                        "Connected to Nuvio. Your progress, watched list and library are syncing now."
                    } else {
                        "Connected to Nuvio. On your TV, choose which Nuvio profile to sync with."
                    }
                }
                "simkl" -> {
                    val id = body.optString("simklClientId").filter { it.isLetterOrDigit() }
                    if (id.length < 10) fail("That doesn't look like a Simkl Client ID.")
                    settings.simklClientId = id
                    showToast("Simkl app ID saved. Connect Simkl in Settings, Accounts.")
                    return@withContext "Saved. On your TV, go to Settings, Accounts, then Connect Simkl."
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

    /** Set by the screen so a Nuvio sign-in from the phone shows the profile choice and syncs. */
    var onNuvioSignedIn: (() -> Unit)? = null

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    override fun onCleared() {
        setupServer.stop()
        super.onCleared()
    }
}
