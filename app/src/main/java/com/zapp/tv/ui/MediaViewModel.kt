package com.zapp.tv.ui

import kotlinx.coroutines.async
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zapp.tv.ZappApp
import com.zapp.tv.addons.CatalogRef
import com.zapp.tv.addons.InstalledAddon
import com.zapp.tv.addons.MetaDetail
import com.zapp.tv.addons.MetaPreview
import com.zapp.tv.addons.StreamBatch
import com.zapp.tv.addons.Video
import com.zapp.tv.data.LibraryItem
import com.zapp.tv.data.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"

/** One Home screen row as the user arranged it. */
/**
 * One Home row: whether it shows, its card size (0 small, 1 medium, 2 large) and whether its
 * cards are posters or landscape artwork.
 */
const val CONTINUE_ROW = "continue"

data class HomeRowSetting(
    val id: String,
    val title: String,
    val on: Boolean,
    val size: Int = 1,
    val landscape: Boolean = false,
    /** Shown as a Top 10: big numbers beside the first ten posters. Null means not chosen yet. */
    val top10: Boolean? = null,
)

class MediaViewModel(app: Application) : AndroidViewModel(app) {
    private val zapp = app as ZappApp
    private val repo = zapp.media
    val settings = zapp.settings

    val addons: StateFlow<List<InstalledAddon>> =
        repo.addons.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val continueWatching: StateFlow<List<WatchProgress>> =
        repo.continueWatching().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val library: StateFlow<List<LibraryItem>> =
        repo.library.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------ profiles

    val profileStore = zapp.profiles
    val profiles: StateFlow<List<com.zapp.tv.data.Profile>> =
        profileStore.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The profile in use (null until profiles have loaded). */
    val currentProfile: com.zapp.tv.data.Profile?
        get() = profiles.value.firstOrNull { it.id == settings.profileId } ?: profiles.value.firstOrNull()

    val isKids: Boolean get() = currentProfile?.kids == true

    fun switchProfile(id: Long) {
        settings.profileId = id
    }

    fun addProfile(name: String, color: Int, kids: Boolean) = viewModelScope.launch { profileStore.add(name, color, kids) }
    fun updateProfile(p: com.zapp.tv.data.Profile) = viewModelScope.launch { profileStore.update(p) }
    fun setPin(p: com.zapp.tv.data.Profile, pin: String?) = viewModelScope.launch { profileStore.setPin(p, pin) }
    fun deleteProfile(id: Long) = viewModelScope.launch { profileStore.delete(id) }
    fun pinMatches(p: com.zapp.tv.data.Profile, entered: String) = profileStore.pinMatches(p, entered)

    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun clearMessage() {
        message = null
    }

    // ------------------------------------------------------------ Simkl

    sealed interface SimklState {
        data object NeedsAppId : SimklState
        data object SignedOut : SimklState
        data class WaitingForPhone(val code: String, val link: String, val shortLink: String) : SimklState
        data class SignedIn(val user: String) : SimklState
        data class Failed(val message: String) : SimklState
    }

    private val simkl = zapp.simkl

    var simklState by mutableStateOf(currentSimklState())
        private set
    var simklSyncing by mutableStateOf(false)
        private set
    var simklMessage by mutableStateOf<String?>(null)
        private set
    private var signInJob: Job? = null

    private fun currentSimklState(): SimklState = when {
        settings.simklClientId.isBlank() -> SimklState.NeedsAppId
        simkl.client.signedIn -> SimklState.SignedIn(settings.simklUser)
        else -> SimklState.SignedOut
    }

    fun refreshSimklState() {
        if (simklState !is SimklState.WaitingForPhone) simklState = currentSimklState()
    }

    /** Starts the device sign-in: shows a code and QR link, then waits for approval on the phone. */
    fun connectSimkl() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            try {
                val code = simkl.client.deviceCode()
                simklState = SimklState.WaitingForPhone(code.userCode, code.verificationUriComplete, code.verificationUri)
                var interval = code.interval * 1000L
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval)
                    when (val r = simkl.client.poll(code.deviceCode)) {
                        is com.zapp.tv.data.SimklClient.Poll.Done -> {
                            simkl.client.saveTokens(r.access, r.refresh)
                            settings.simklUser = simkl.client.userName()
                            simklState = SimklState.SignedIn(settings.simklUser)
                            syncSimkl(force = true)
                            return@launch
                        }
                        com.zapp.tv.data.SimklClient.Poll.SlowDown -> interval += 5_000
                        com.zapp.tv.data.SimklClient.Poll.Expired -> break
                        is com.zapp.tv.data.SimklClient.Poll.Failed -> {
                            simklState = SimklState.Failed(r.message)
                            return@launch
                        }
                        com.zapp.tv.data.SimklClient.Poll.Pending -> Unit
                    }
                }
                simklState = SimklState.Failed("The code ran out of time. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                simklState = SimklState.Failed(e.message ?: "Couldn't reach Simkl.")
            }
        }
    }

    fun cancelSimklSignIn() {
        signInJob?.cancel()
        simklState = currentSimklState()
    }

    fun disconnectSimkl() {
        simkl.client.signOut()
        simklState = currentSimklState()
        simklMessage = null
    }

    fun syncSimkl(force: Boolean = false) {
        if (simklSyncing || !simkl.client.signedIn) return
        simklSyncing = true
        viewModelScope.launch {
            try {
                val s = simkl.pull(force)
                simklMessage = when {
                    s == null -> "Already up to date"
                    s.watched + s.inProgress + s.library == 0 -> "Up to date"
                    else -> "Brought in ${s.watched} watched, ${s.inProgress} in progress, ${s.library} for your library"
                }
            } catch (e: com.zapp.tv.data.SimklClient.NotSignedIn) {
                simklState = currentSimklState()
                simklMessage = "Simkl signed Zapp out. Connect it again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                simklMessage = e.message ?: "Couldn't reach Simkl."
            } finally {
                simklSyncing = false
            }
        }
    }

    /** Called by the player: "start", "pause" or "stop" with how far through the video it is. */
    fun scrobble(event: String, info: MediaInfo, percent: Double) {
        // Simkl is linked to the first profile only.
        if (!simkl.client.signedIn || settings.profileId != 1L) return
        viewModelScope.launch { simkl.scrobble(event, info.videoId, info.type, info.title, percent) }
    }

    init {
        // Sync with Simkl shortly after start and every 30 minutes while Zapp is open.
        viewModelScope.launch {
            delay(6_000)
            while (true) {
                if (simkl.client.signedIn) syncSimkl()
                if (stremio.signedIn) syncStremio(quiet = true)
                delay(30 * 60_000L)
            }
        }
    }

    // ------------------------------------------------------------ Stremio

    val stremio = zapp.stremio
    var stremioStatus by mutableStateOf<String?>(null)
        private set
    var stremioWorking by mutableStateOf(false)
        private set
    /** Bumped when the Stremio sign-in changes, so screens re-read it. */
    var stremioVersion by mutableStateOf(0)
        private set

    fun syncStremio(quiet: Boolean = false) {
        if (stremioWorking || !stremio.signedIn) return
        stremioWorking = true
        if (!quiet) stremioStatus = "Syncing with Stremio…"
        viewModelScope.launch {
            try {
                val s = stremio.pull()
                stremioStatus = "Synced: ${s.library} new in library, ${s.inProgress} in progress updated."
            } catch (e: Exception) {
                stremioStatus = e.message ?: "Couldn't reach Stremio."
            } finally {
                stremioWorking = false
            }
        }
    }

    fun stremioSignedInFromPhone() {
        stremioVersion++
        syncStremio()
    }

    fun signOutStremio() {
        stremio.signOut()
        stremioStatus = null
        stremioVersion++
    }

    // ------------------------------------------------------------ Nuvio import

    var nuvioStatus by mutableStateOf<String?>(null)
        private set
    var nuvioWorking by mutableStateOf(false)
        private set

    /** Copies add-ons from Nuvio TV's "Manage from phone" page into Zapp, skipping ones already here. */
    fun importFromNuvio(otherDevice: String?) {
        if (nuvioWorking) return
        nuvioWorking = true
        nuvioStatus = "Looking for Nuvio…"
        viewModelScope.launch {
            try {
                val found = repo.nuvio.find(otherDevice)
                if (found == null) {
                    nuvioStatus = "Couldn't find Nuvio's add-on page. In Nuvio, open Add-ons and choose Manage from phone, " +
                        "then press Home (don't close it) and try again."
                    return@launch
                }
                if (found.addons.isEmpty()) {
                    nuvioStatus = "Found Nuvio, but it has no add-ons to bring over."
                    return@launch
                }
                val added = ArrayList<String>()
                val skipped = ArrayList<String>()
                val failed = ArrayList<String>()
                for ((url, name) in found.addons) {
                    nuvioStatus = "Adding $name…"
                    try {
                        repo.addAddon(url)
                        added.add(name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e.message?.contains("already installed") == true) skipped.add(name) else failed.add(name)
                    }
                }
                nuvioStatus = buildString {
                    append(if (added.isEmpty()) "Nothing new to add." else "Brought over ${added.size}: ${added.joinToString(", ")}.")
                    if (skipped.isNotEmpty()) append(" Already here: ${skipped.size}.")
                    if (failed.isNotEmpty()) append(" Couldn't add: ${failed.joinToString(", ")}.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nuvioStatus = e.message ?: "Something went wrong reading Nuvio's list."
            } finally {
                nuvioWorking = false
            }
        }
    }

    // ------------------------------------------------------------ debrid services

    sealed interface DebridSignIn {
        data object None : DebridSignIn
        data class Waiting(val service: String, val code: String, val link: String, val shortLink: String) : DebridSignIn
        data class Failed(val message: String) : DebridSignIn
    }

    val debrid = zapp.debrid
    var debridSignIn by mutableStateOf<DebridSignIn>(DebridSignIn.None)
        private set
    private var debridJob: Job? = null

    /** True when at least one debrid service is connected (re-read when sign-ins change). */
    val debridConnected: Boolean
        get() {
            settings.debridVersion
            return debrid.connected.isNotEmpty()
        }

    fun debridNames(): List<String> {
        settings.debridVersion
        return debrid.connected.map { it.name }
    }

    fun connectRealDebrid() {
        debridJob?.cancel()
        debridJob = viewModelScope.launch {
            try {
                val d = debrid.realDebrid.startSignIn()
                debridSignIn = DebridSignIn.Waiting("Real-Debrid", d.userCode, d.directUrl, d.url)
                val deadline = System.currentTimeMillis() + d.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(d.interval * 1000L)
                    if (debrid.realDebrid.poll(d)) {
                        debridSignIn = DebridSignIn.None
                        message = "Real-Debrid connected"
                        return@launch
                    }
                }
                debridSignIn = DebridSignIn.Failed("The code ran out of time. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debridSignIn = DebridSignIn.Failed(e.message ?: "Couldn't reach Real-Debrid.")
            }
        }
    }

    fun connectAllDebrid() {
        debridJob?.cancel()
        debridJob = viewModelScope.launch {
            try {
                val p = debrid.allDebrid.startSignIn()
                debridSignIn = DebridSignIn.Waiting("AllDebrid", p.pin, p.url, "alldebrid.com/pin")
                val deadline = System.currentTimeMillis() + p.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(5_000)
                    if (debrid.allDebrid.poll(p)) {
                        debridSignIn = DebridSignIn.None
                        message = "AllDebrid connected"
                        return@launch
                    }
                }
                debridSignIn = DebridSignIn.Failed("The PIN ran out of time. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debridSignIn = DebridSignIn.Failed(e.message ?: "Couldn't reach AllDebrid.")
            }
        }
    }

    fun cancelDebridSignIn() {
        debridJob?.cancel()
        debridSignIn = DebridSignIn.None
    }

    fun disconnectDebrid(id: String) {
        debrid.all.firstOrNull { it.id == id }?.signOut()
    }

    var resolving by mutableStateOf<String?>(null)
        private set

    /** Turns a torrent source into a playable link through the connected debrid service(s). */
    fun resolveTorrent(stream: com.zapp.tv.addons.StreamItem, title: String, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val hash = stream.infoHash ?: return onError("This source has no torrent to fetch.")
        if (resolving != null) return
        resolving = "Getting it from ${debrid.connected.firstOrNull()?.name ?: "your debrid service"}…"
        viewModelScope.launch {
            try {
                val (_, url) = debrid.resolve(com.zapp.tv.data.TorrentRef(hash.lowercase(), stream.fileIdx, stream.filename, title))
                onReady(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't play this source.")
            } finally {
                resolving = null
            }
        }
    }

    // ------------------------------------------------------------ add-ons

    fun addAddon(url: String, onDone: () -> Unit = {}) {
        if (busy != null) return
        busy = "Installing add-on…"
        message = null
        viewModelScope.launch {
            try {
                val a = repo.addAddon(url)
                message = "Installed ${a.manifest.name}"
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "That add-on couldn't be installed."
            } finally {
                busy = null
            }
        }
    }

    fun removeAddon(id: Long) = viewModelScope.launch { repo.removeAddon(id) }

    fun setEnabled(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(id, enabled) }

    fun move(id: Long, up: Boolean) = viewModelScope.launch { repo.move(id, up) }

    fun refreshAddons() {
        if (busy != null) return
        busy = "Refreshing add-ons…"
        viewModelScope.launch {
            try {
                repo.refreshManifests()
                message = "Add-ons refreshed"
            } finally {
                busy = null
            }
        }
    }

    // ------------------------------------------------------------ browsing

    suspend fun catalogs(type: String?): List<CatalogRef> = repo.catalogs(type)

    suspend fun page(ref: CatalogRef, skip: Int): List<MetaPreview> =
        repo.catalogPage(ref, skip).let { list -> if (isKids) list.filter { com.zapp.tv.data.ProfileStore.kidsSafe(it.genres) } else list }

    suspend fun meta(type: String, id: String): MetaDetail? = repo.meta(type, id)

    fun streams(type: String, videoId: String): Flow<StreamBatch> = repo.streams(type, videoId)

    // Subtitles are fetched while the sources load, so they're ready when playback starts.
    private val subtitleJobs = HashMap<String, kotlinx.coroutines.Deferred<List<com.zapp.tv.addons.ExternalSubtitle>>>()

    fun prefetchSubtitles(type: String, videoId: String) {
        if (subtitleJobs.containsKey(videoId)) return
        val langs = setOf("en", com.zapp.tv.addons.normalizeLang(settings.subtitleLanguage.ifBlank { "en" }))
        subtitleJobs[videoId] = viewModelScope.async {
            try {
                repo.subtitles(type, videoId, langs)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The prefetched subtitles, waiting at most [waitMs] for them. */
    suspend fun subtitlesFor(type: String, videoId: String, waitMs: Long = 1_500): List<com.zapp.tv.addons.ExternalSubtitle> {
        prefetchSubtitles(type, videoId)
        return kotlinx.coroutines.withTimeoutOrNull(waitMs) { subtitleJobs[videoId]?.await() }.orEmpty()
    }

    // ------------------------------------------------------------ search

    private val searchQuery = MutableStateFlow("")

    fun search(text: String) {
        searchQuery.value = text.trim()
    }

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<Pair<CatalogRef, List<MetaPreview>>>?> = searchQuery
        .debounce(450)
        .distinctUntilChanged()
        .map { q ->
            if (q.length < 2) {
                null
            } else {
                repo.search(q).let { res ->
                    if (!isKids) res else res.map { (ref, list) -> ref to list.filter { com.zapp.tv.data.ProfileStore.kidsSafe(it.genres) } }.filter { it.second.isNotEmpty() }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------ progress and library

    suspend fun progress(videoId: String): WatchProgress? = repo.progress(videoId)

    fun progressFor(metaId: String): Flow<List<WatchProgress>> = repo.progressFor(metaId)

    fun saveProgress(info: MediaInfo, position: Long, duration: Long) {
        if (duration <= 0) return
        viewModelScope.launch {
            repo.saveProgress(
                WatchProgress(
                    videoId = info.videoId,
                    metaId = info.metaId,
                    type = info.type,
                    name = info.title,
                    episodeLabel = info.episodeLabel,
                    poster = info.poster,
                    background = info.background,
                    position = position,
                    duration = duration,
                    watched = position >= duration * 0.9,
                    hidden = false,
                    updatedAt = System.currentTimeMillis(),
                    percent = position * 100.0 / duration,
                ),
            )
        }
    }

    fun hideFromContinue(metaId: String) = viewModelScope.launch { repo.hideFromContinue(metaId) }

    fun inLibrary(metaId: String): Flow<Boolean> = repo.inLibrary(metaId)

    fun toggleLibrary(meta: MetaPreview, currentlyIn: Boolean) = viewModelScope.launch {
        repo.toggleLibrary(meta, currentlyIn)
        if (!currentlyIn && simkl.client.signedIn) simkl.addToWatchlist(meta.id, meta.type)
    }

    // ------------------------------------------------------------ Home layout

    /**
     * Every row Home can show, in the user's order. Rows the user hasn't arranged yet are added
     * after the arranged ones (new add-on catalogues show up switched on).
     */
    fun homeLayout(catalogs: List<CatalogRef>): List<HomeRowSetting> {
        val available = LinkedHashMap<String, String>()
        available[CONTINUE_ROW] = "Continue watching"
        catalogs.forEach { available[it.key] = it.title }

        val saved = parseLayout(settings.homeRowsJson)
        val result = ArrayList<HomeRowSetting>()
        for (r in saved) {
            val title = available[r.id] ?: continue
            result.add(r.copy(title = title))
        }
        for ((id, title) in available) {
            if (result.none { it.id == id }) result.add(HomeRowSetting(id, title, true, landscape = id == CONTINUE_ROW))
        }
        // A first "top" or "trending" catalogue becomes a Top 10 until you choose otherwise.
        val firstTop = result.firstOrNull { it.id != CONTINUE_ROW && Regex("\\b(top|trending)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.title) }?.id
        for (i in result.indices) if (result[i].top10 == null) result[i] = result[i].copy(top10 = result[i].id == firstTop)
        // Continue watching is always the first row.
        val cont = result.first { it.id == CONTINUE_ROW }
        result.remove(cont)
        result.add(0, cont)
        return result
    }

    fun saveLayout(rows: List<HomeRowSetting>) {
        val arr = JSONArray()
        rows.forEach {
            arr.put(JSONObject().put("id", it.id).put("on", it.on).put("size", it.size).put("landscape", it.landscape).apply { it.top10?.let { t -> put("top10", t) } })
        }
        settings.homeRowsJson = arr.toString()
    }

    /** Change one row and save. */
    fun updateRow(all: List<HomeRowSetting>, id: String, change: (HomeRowSetting) -> HomeRowSetting) =
        saveLayout(all.map { if (it.id == id) change(it) else it })

    /** Move a row up (-1) or down (+1) among the catalogue rows; Continue watching stays first. */
    fun moveRow(all: List<HomeRowSetting>, id: String, by: Int) {
        val i = all.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 1 || j < 1 || j >= all.size) return
        val m = all.toMutableList()
        m.add(j, m.removeAt(i))
        saveLayout(m)
    }

    private fun parseLayout(json: String): List<HomeRowSetting> = try {
        if (json.isBlank()) {
            emptyList()
        } else {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    val id = it.optString("id")
                    HomeRowSetting(
                        id = id,
                        title = "",
                        on = it.optBoolean("on", true),
                        size = it.optInt("size", 1).coerceIn(0, 2),
                        landscape = it.optBoolean("landscape", id == CONTINUE_ROW),
                        top10 = if (it.has("top10")) it.optBoolean("top10") else null,
                    )
                }
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The episode after [current] in [videos], if any. */
    fun nextEpisode(videos: List<Video>, currentId: String): Video? {
        val i = videos.indexOfFirst { it.id == currentId }
        if (i < 0) return null
        return videos.drop(i + 1).firstOrNull { (it.season ?: 1) > 0 }
    }
}
