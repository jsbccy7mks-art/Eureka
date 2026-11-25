package com.zapp.tv.ui

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
data class HomeRowSetting(val id: String, val title: String, val on: Boolean)

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
        if (!simkl.client.signedIn) return
        viewModelScope.launch { simkl.scrobble(event, info.videoId, info.type, info.title, percent) }
    }

    init {
        // Sync with Simkl shortly after start and every 30 minutes while Zapp is open.
        viewModelScope.launch {
            delay(6_000)
            while (true) {
                if (simkl.client.signedIn) syncSimkl()
                delay(30 * 60_000L)
            }
        }
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

    suspend fun page(ref: CatalogRef, skip: Int): List<MetaPreview> = repo.catalogPage(ref, skip)

    suspend fun meta(type: String, id: String): MetaDetail? = repo.meta(type, id)

    fun streams(type: String, videoId: String): Flow<StreamBatch> = repo.streams(type, videoId)

    // ------------------------------------------------------------ search

    private val searchQuery = MutableStateFlow("")

    fun search(text: String) {
        searchQuery.value = text.trim()
    }

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<Pair<CatalogRef, List<MetaPreview>>>?> = searchQuery
        .debounce(450)
        .distinctUntilChanged()
        .map { q -> if (q.length < 2) null else repo.search(q) }
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
        val builtIn = listOf(
            "continue" to "Continue watching (films and series)",
            "live:recent" to "Continue watching (channels)",
            "live:favorites" to "Favourite channels",
        )
        val tail = listOf("live:onnow" to "On now")
        val available = LinkedHashMap<String, String>()
        builtIn.forEach { available[it.first] = it.second }
        catalogs.forEach { available[it.key] = it.title }
        tail.forEach { available[it.first] = it.second }

        val saved = parseLayout(settings.homeRowsJson)
        val result = ArrayList<HomeRowSetting>()
        for ((id, on) in saved) {
            val title = available[id] ?: continue
            result.add(HomeRowSetting(id, title, on))
        }
        for ((id, title) in available) {
            if (result.none { it.id == id }) result.add(HomeRowSetting(id, title, true))
        }
        return result
    }

    fun saveLayout(rows: List<HomeRowSetting>) {
        val arr = JSONArray()
        rows.forEach { arr.put(JSONObject().put("id", it.id).put("on", it.on)) }
        settings.homeRowsJson = arr.toString()
    }

    private fun parseLayout(json: String): List<Pair<String, Boolean>> = try {
        if (json.isBlank()) {
            emptyList()
        } else {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { it.optString("id") to it.optBoolean("on", true) }
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
