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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
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
const val BECAUSE_ROW = "because"
const val NEW_EPISODES_ROW = "newEpisodes"
const val LIBRARY_ROW = "library"
const val COLLECTIONS_ROW = "collections"

/** The collection shelves, in order. Each can also be a Home row ("collections:<id>"). */
val COLLECTION_SHELVES = listOf(
    "yours" to "Your collections",
    "seasonal" to "In season",
    "finish" to "Finish what you started",
    "networks" to "Streaming services",
    "superheroes" to "Superheroes",
    "action" to "Action and adventure",
    "scifi" to "Sci-fi",
    "fantasy" to "Fantasy",
    "horror" to "Horror",
    "family" to "Animation and family",
    "comedy" to "Comedy",
    "crime" to "Crime and thriller",
    "more" to "More collections",
    "studios" to "Studios",
    "decades" to "Decades",
    "themes" to "Themes",
)
const val SPORTS_ROW = "sports"
const val FOR_YOU_ROW = "forYou"
const val LOVED_ROW = "loved"
const val GEMS_ROW = "gems"
const val SEASON_ROW = "season"

data class HomeRowSetting(
    val id: String,
    val title: String,
    val on: Boolean,
    val size: Int = 1,
    val landscape: Boolean = false,
    /** Shown as a Top 10: big numbers beside the first ten posters. Null means not chosen yet. */
    val top10: Boolean? = null,
    /** Your own name for the row (null: its usual name). */
    val name: String? = null,
    /** Where it comes from, for Home layout: "Smart row", an add-on's name, "Collections"… */
    val source: String = "",
) {
    /** The name shown: yours, else its own. */
    val label: String get() = name ?: title
}

class MediaViewModel(app: Application) : AndroidViewModel(app) {
    private val zapp = app as ZappApp
    private val repo = zapp.media
    val settings = zapp.settings

    val addons: StateFlow<List<InstalledAddon>> =
        repo.addons.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val continueWatching: StateFlow<List<WatchProgress>> =
        repo.continueWatching().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Titles synced from Simkl or Stremio often arrive without pictures: fill them in from your
    // add-ons once, and save them so Continue watching always has artwork.
    private val artworkTried = HashSet<String>()

    /** The show's own artwork and logo for Continue watching cards (by title id). */
    data class ShowArt(val background: String?, val logo: String?, val name: String)
    val showArt = androidx.compose.runtime.mutableStateMapOf<String, ShowArt>()

    init {
        viewModelScope.launch {
            continueWatching.collect { list ->
                for (p in list) {
                    if (!artworkTried.add(p.metaId)) continue
                    val d = runCatching { meta(p.type, p.metaId) }.getOrNull() ?: continue
                    showArt[p.metaId] = ShowArt(d.preview.background, d.preview.logo, d.preview.name)
                    // Synced titles sometimes arrive with no picture, or with only their id as a name.
                    val badName = p.name.isBlank() || Regex("^tt\\d+").containsMatchIn(p.name)
                    if (p.background == null || p.poster == null || badName) {
                        val thumb = d.videos.firstOrNull { it.id == p.videoId }?.thumbnail
                        repo.saveProgress(
                            p.copy(
                                name = if (badName) d.preview.name else p.name,
                                poster = p.poster ?: d.preview.poster,
                                background = p.background ?: thumb ?: d.preview.background,
                            ),
                        )
                    }
                }
            }
        }
    }

    val library: StateFlow<List<LibraryItem>> =
        repo.library.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** When each title was last played (library sort). */
    val lastWatched: StateFlow<Map<String, Long>> =
        repo.lastWatched.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Release years for library titles (library sort), filled in as details arrive. */
    val libraryYears = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    private val libraryTried = HashSet<String>()

    init {
        // Synced library titles often arrive without a poster: fill them in once from your add-ons
        // (or TMDB) and save them. The year comes along for sorting.
        viewModelScope.launch {
            library.collect { list ->
                for (item in list) {
                    if (!libraryTried.add(item.metaId)) continue
                    val d = runCatching { meta(item.type, item.metaId) }.getOrNull() ?: continue
                    d.preview.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }?.let { libraryYears[item.metaId] = it }
                    val badName = item.name.isBlank() || Regex("^tt\\d+").containsMatchIn(item.name)
                    if (item.poster == null || badName) {
                        repo.updateLibraryItem(item.copy(poster = item.poster ?: d.preview.poster, name = if (badName) d.preview.name else item.name))
                    }
                }
            }
        }
    }

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
        ratings.load()
        recomputeTaste()
        nuvioVersion++
        syncNuvio(quiet = true)
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
                simklMessage = "Simkl signed Axon out. Connect it again."
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
    fun scrobble(event: String, info: MediaInfo, percent: Double, durationMs: Long = 0L) {
        if (info.videoId.startsWith("catchup:")) return // TV replays aren't films or series
        // Stremio: send where you got to when you pause or stop.
        if (event != "start") flushNuvio()
        if (event != "start" && stremio.signedIn && durationMs > 0) {
            val d = durationMs
            viewModelScope.launch {
                runCatching {
                    stremio.pushProgress(info.metaId, info.videoId, info.type, info.title, info.poster, (d * percent / 100).toLong(), d)
                }
            }
        }
        if (!simkl.client.signedIn) return
        viewModelScope.launch { simkl.scrobble(event, info.videoId, info.type, info.title, percent) }
    }

    init {
        // Sync with Simkl shortly after start and every 30 minutes while Zapp is open.
        viewModelScope.launch {
            delay(6_000)
            while (true) {
                if (simkl.client.signedIn) syncSimkl()
                if (stremio.signedIn) syncStremio(quiet = true)
                syncNuvio(quiet = true)
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

    // ------------------------------------------------------------ Nuvio sync

    val nuvio = zapp.nuvio
    var nuvioSyncStatus by mutableStateOf<String?>(null)
        private set
    var nuvioSyncing by mutableStateOf(false)
        private set
    /** Bumped when the Nuvio connection changes, so screens re-read it. */
    var nuvioVersion by mutableStateOf(0)
        private set
    var nuvioProfiles by mutableStateOf<List<com.zapp.tv.data.NuvioSync.NuvioProfile>>(emptyList())
        private set
    private var nuvioLastFlush = 0L

    fun nuvioReady(): Boolean {
        val pid = settings.profileId
        return nuvio.signedIn(pid) && nuvio.linkedProfile(pid) != 0
    }

    fun syncNuvio(quiet: Boolean = false, everything: Boolean = false) {
        val pid = settings.profileId
        if (nuvioSyncing || !nuvio.signedIn(pid) || nuvio.linkedProfile(pid) == 0) return
        nuvioSyncing = true
        if (!quiet) nuvioSyncStatus = if (everything) "Pulling everything from Nuvio…" else "Syncing with Nuvio…"
        viewModelScope.launch {
            try {
                val s = if (everything) nuvio.pullAgain(pid) else nuvio.sync(pid)
                nuvioSyncStatus = if (s.progress + s.watched + s.library + s.removed + s.sent == 0) {
                    "Up to date"
                } else {
                    buildList {
                        if (s.progress > 0) add("${s.progress} in progress")
                        if (s.watched > 0) add("${s.watched} watched")
                        if (s.library > 0) add("${s.library} for your library")
                        if (s.removed > 0) add("${s.removed} removed")
                        if (s.sent > 0) add("${s.sent} sent to Nuvio")
                    }.joinToString(", ").replaceFirstChar { it.uppercase() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nuvioSyncStatus = e.message ?: "Couldn't reach Nuvio."
            } finally {
                nuvioSyncing = false
                nuvioVersion++
            }
        }
    }

    /** Sends queued changes now, or (when [force] is false) at most about once a minute. */
    fun flushNuvio(force: Boolean = true) {
        val pid = settings.profileId
        if (!nuvio.signedIn(pid) || nuvio.linkedProfile(pid) == 0) return
        val now = System.currentTimeMillis()
        if (!force && now - nuvioLastFlush < 60_000) return
        nuvioLastFlush = now
        viewModelScope.launch {
            try {
                nuvio.flush(pid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stays queued; sent on the next sync or flush.
            }
        }
    }

    /** Back to Zapp after it was in the background. */
    fun onAppResumed() {
        val pid = settings.profileId
        if (System.currentTimeMillis() - nuvio.lastSync(pid) > 2 * 60_000) syncNuvio(quiet = true)
    }

    fun loadNuvioProfiles() {
        val pid = settings.profileId
        if (!nuvio.signedIn(pid)) return
        viewModelScope.launch {
            try {
                nuvioProfiles = nuvio.profiles(pid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nuvioSyncStatus = e.message
            }
        }
    }

    fun linkNuvioProfile(p: com.zapp.tv.data.NuvioSync.NuvioProfile) = viewModelScope.launch {
        nuvio.linkProfile(settings.profileId, p)
        nuvioVersion++
        syncNuvio()
    }

    fun nuvioSignedInFromPhone() {
        nuvioVersion++
        loadNuvioProfiles()
        syncNuvio()
    }

    fun disconnectNuvio() = viewModelScope.launch {
        nuvio.signOut(settings.profileId)
        nuvioSyncStatus = null
        nuvioProfiles = emptyList()
        nuvioVersion++
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

    /** How sources have done on this box (sources that learn). */
    val sourceStats get() = zapp.sourceStats
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

    /** A short message on the starting screen while something is got ready (e.g. matching the TV). */
    fun showStarting(text: String?) {
        resolving = text
    }

    // ------------------------------------------------------------ getting ready before Play

    /** Sources for one film or episode, fetched ahead (from its details page) and shared with the sources screen. */
    class Prefetch(val type: String, val videoId: String) {
        val batches = androidx.compose.runtime.mutableStateListOf<StreamBatch>()
        val finished = mutableStateOf(false)
        val startedAt = System.currentTimeMillis()
        var job: kotlinx.coroutines.Job? = null
        val fresh: Boolean get() = System.currentTimeMillis() - startedAt < 10 * 60_000L
    }
    private val prefetches = LinkedHashMap<String, Prefetch>()

    /** Debrid links already fetched, by torrent and file, kept for 10 minutes (links can expire). */
    private val readyLinks = HashMap<String, Pair<String, Long>>()
    private fun linkKey(s: com.zapp.tv.addons.StreamItem) = "${s.infoHash?.lowercase()}|${s.fileIdx}|${s.filename}"

    /**
     * Starts (or reuses) asking the add-ons for a title's sources. Called by the details page, so
     * the list is usually there already when you press Play.
     */
    fun prefetchStreams(type: String, videoId: String): Prefetch {
        val key = "$type|$videoId"
        prefetches[key]?.takeIf { p ->
            p.fresh && p.job?.isCancelled != true && !(p.finished.value && p.batches.all { it.streams.isEmpty() })
        }?.let { return it }
        val p = Prefetch(type, videoId)
        prefetches[key] = p
        // Keep only the last few.
        while (prefetches.size > 3) {
            val oldest = prefetches.keys.first()
            prefetches.remove(oldest)?.job?.cancel()
        }
        prefetchSubtitles(type, videoId)
        val expected = addons.value.count { it.addon.enabled && it.manifest.supports("stream", type, videoId) }.coerceAtLeast(1)
        p.job = viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(30_000) {
                repo.streams(type, videoId).take(expected).collect { p.batches.add(it) }
            }
            p.finished.value = true
        }
        return p
    }

    /**
     * Level 2: once the sources are in, picks the one autoplay would choose and, for a torrent,
     * gets its debrid link ready, so Play goes straight to the picture.
     */
    fun prepareLink(type: String, videoId: String, metaId: String, title: String) {
        if (!settings.autoPlayFirst || !settings.prepareLinks || !debridConnected) return
        val p = prefetchStreams(type, videoId)
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(30_000) { snapshotFlow { p.finished.value }.first { it } }
            val order = addons.value.map { it.manifest.name }
            val cap = when (settings.maxQuality) { 1 -> 1080; 2 -> 720; else -> Int.MAX_VALUE }
            val all = p.batches.sortedBy { b -> order.indexOf(b.addonName).let { if (it < 0) Int.MAX_VALUE else it } }
                .flatMap { b ->
                    val kept = b.streams.filter { it.qualityRank <= cap }
                    if (settings.bestFirst) kept.sortedByDescending { (if (it.markedReady == true) 10_000 else 0) + it.qualityRank + sourceStats.bonus(it) } else kept
                }
            // Only sources the debrid service already has (cached): they cost nothing to add and use no
            // download slot. If none are cached, nothing is prepared and Play works as usual.
            val hashes = all.mapNotNull { it.infoHash?.lowercase() }.distinct()
            if (hashes.isEmpty()) return@launch
            val cached = runCatching { debrid.ready(hashes) }.getOrNull()
            fun ok(s: com.zapp.tv.addons.StreamItem): Boolean {
                val h = s.infoHash?.lowercase() ?: return false
                return if (cached != null) h in cached else s.markedReady == true
            }
            val last = if (settings.sameSource) lastSource(metaId) else null
            val choice = last?.takeIf { it.videoId == videoId && it.key != null }?.let { l -> all.firstOrNull { ok(it) && (it.url ?: it.infoHash) == l.key } }
                ?: last?.binge?.let { g -> all.firstOrNull { ok(it) && it.bingeGroup == g } }
                ?: last?.let { l -> all.firstOrNull { ok(it) && it.addonName == l.addon && it.qualityRank == l.quality } }
                ?: all.firstOrNull { ok(it) }
                ?: return@launch
            val k = linkKey(choice)
            if (readyLinks[k]?.let { System.currentTimeMillis() - it.second < 10 * 60_000L } == true) return@launch
            runCatching { debrid.resolve(com.zapp.tv.data.TorrentRef(choice.infoHash!!.lowercase(), choice.fileIdx, choice.filename, title)) }
                .getOrNull()?.let { (_, url) ->
                    readyLinks[k] = url to System.currentTimeMillis()
                    // While you read the page: the film's frame rate, so the TV can switch before it starts.
                    if (settings.frameRateMode != 0) com.zapp.tv.data.FrameRateProbe.probe(url, emptyMap(), timeoutMs = 25_000)
                }
        }
    }

    /** Turns a torrent source into a playable link through the connected debrid service(s). */
    fun resolveTorrent(stream: com.zapp.tv.addons.StreamItem, title: String, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val hash = stream.infoHash ?: return onError("This source has no torrent to fetch.")
        // Got ready earlier (from the details page): straight to the player.
        readyLinks[linkKey(stream)]?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000L }?.let { return onReady(it.first) }
        if (resolving != null) return
        resolving = "Getting it from ${debrid.connected.firstOrNull()?.name ?: "your debrid service"}…"
        viewModelScope.launch {
            try {
                val (_, url) = debrid.resolve(com.zapp.tv.data.TorrentRef(hash.lowercase(), stream.fileIdx, stream.filename, title))
                readyLinks[linkKey(stream)] = url to System.currentTimeMillis()
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

    fun cachedPage(ref: CatalogRef): List<MetaPreview>? =
        repo.cachedFirstPage(ref)?.let { list -> if (isKids) list.filter { com.zapp.tv.data.ProfileStore.kidsSafe(it.genres) } else list }

    private val warmed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Gets rows ready before you scroll to them: each catalogue's first page, and the pictures of
     * its first cards, so rows further down are already there when they come into view.
     */
    fun warmRows(refs: List<CatalogRef>) {
        val todo = refs.filter { warmed.add(it.key) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            val loader = coil.Coil.imageLoader(getApplication())
            todo.map { ref ->
                async {
                    gate.withPermit {
                        val items = runCatching { page(ref, 0) }.getOrDefault(emptyList())
                        items.take(8).forEach { m ->
                            val pic = m.poster ?: return@forEach
                            loader.enqueue(coil.request.ImageRequest.Builder(getApplication()).data(pic).size(320, 480).build())
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun page(ref: CatalogRef, skip: Int): List<MetaPreview> =
        repo.catalogPage(ref, skip).let { list -> if (isKids) list.filter { com.zapp.tv.data.ProfileStore.kidsSafe(it.genres) } else list }

    suspend fun meta(type: String, id: String): MetaDetail? {
        val m = repo.meta(type, id) ?: return null
        if (!tmdb.enabled || !id.startsWith("tt")) return m
        val t = runCatching { tmdb.details(id, type) }.getOrNull() ?: return m
        // TMDB's official trailer (often 4K) over the add-on's, and its art where the add-on has none.
        return m.copy(
            trailerYoutube = t.trailerKey ?: m.trailerYoutube,
            preview = m.preview.copy(
                logo = m.preview.logo ?: t.logo,
                background = m.preview.background ?: t.backdrop,
            ),
        )
    }

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

    /** Your last searches, newest first (kept per profile). */
    var recentSearches by mutableStateOf(settings.recentSearches)
        private set

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recentSearches = (listOf(t) + recentSearches.filterNot { it.equals(t, ignoreCase = true) }).take(10)
        settings.recentSearches = recentSearches
    }

    private val searchQuery = MutableStateFlow("")

    /**
     * The best results across all your add-ons, in one row: how well the title matches, then a
     * boost for what's in your library, what you're watching or have watched, and its rating.
     */
    fun bestMatches(results: List<Pair<CatalogRef, List<MetaPreview>>>, query: String): List<MetaPreview> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val lib = library.value.map { it.metaId }.toHashSet()
        val watching = continueWatching.value.map { it.metaId }.toHashSet()
        val seen = watchedTitles.value
        val all = LinkedHashMap<String, MetaPreview>()
        results.forEach { (_, list) -> list.forEach { m -> all.putIfAbsent(m.id, m) } }
        fun score(m: MetaPreview): Double {
            val n = m.name.lowercase()
            var s = when {
                n == q -> 60.0
                n.startsWith(q) -> 35.0
                n.split(' ', ':', '-').any { it.startsWith(q) } -> 22.0
                n.contains(q) -> 12.0
                else -> 0.0
            }
            if (m.id in lib) s += 30
            if (m.id in watching) s += 25
            if (m.id in seen) s += 15
            s += (m.imdbRating?.toDoubleOrNull() ?: 0.0) * 1.5
            return s
        }
        return all.values.sortedByDescending(::score).take(15)
    }

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
        if (duration <= 0 || info.videoId.startsWith("catchup:")) return
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
            nuvio.queueProgress(settings.profileId, info.metaId, info.type, info.videoId, position, duration, System.currentTimeMillis())
            flushNuvio(force = false)
        }
    }

    /**
     * After an episode is finished, its next episode goes to the front of Continue watching
     * ("Up next"). Marked with percent -1 until you start it.
     */
    fun markUpNext(info: MediaInfo, next: Video) = viewModelScope.launch {
        if (repo.progress(next.id) != null) return@launch
        repo.saveProgress(
            WatchProgress(
                videoId = next.id,
                metaId = info.metaId,
                type = info.type,
                name = info.title,
                episodeLabel = episodeLabel(next),
                poster = info.poster,
                background = next.thumbnail ?: info.background,
                position = 0,
                duration = 0,
                watched = false,
                hidden = false,
                updatedAt = System.currentTimeMillis() + 1,
                percent = -1.0,
            ),
        )
    }

    // ------------------------------------------------------------ smarter Home rows

    /** "Because you watched …": titles from your catalogues sharing a genre with what you watched last. */
    suspend fun becauseYouWatched(seed: MetaPreview?): Pair<String, List<MetaPreview>>? {
        val s = seed ?: return null
        val genres = (repo.meta(s.type, s.id)?.preview?.genres ?: s.genres).map { it.lowercase() }.toSet()
        if (genres.isEmpty()) return null
        val found = LinkedHashMap<String, MetaPreview>()
        for (ref in catalogs(s.type).take(6)) {
            val page = runCatching { page(ref, 0) }.getOrNull() ?: continue
            for (m in page) if (m.id != s.id && m.genres.any { it.lowercase() in genres }) found.putIfAbsent(m.id, m)
            if (found.size >= 20) break
        }
        return if (found.size < 3) null else s.name to found.values.take(20).toList()
    }

    /** Series in your library with an episode out in the last 10 days that you haven't watched. */
    suspend fun newEpisodes(library: List<LibraryItem>): List<Pair<MetaPreview, String>> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<MetaPreview, String>>()
        for (item in library.filter { it.type == "series" }.take(15)) {
            val d = runCatching { repo.meta("series", item.metaId) }.getOrNull() ?: continue
            val latest = d.videos.filter { (it.season ?: 0) > 0 }
                .mapNotNull { v -> releasedAt(v.released)?.let { v to it } }
                .filter { it.second <= now }
                .maxByOrNull { it.second } ?: continue
            if (now - latest.second > 10 * 86_400_000L) continue
            val p = repo.progress(latest.first.id)
            if (p?.watched == true) continue
            out.add(d.preview to "S${latest.first.season} E${latest.first.episode}")
        }
        return out
    }

    private fun releasedAt(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")) {
            try {
                return java.text.SimpleDateFormat(f, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(text)?.time
            } catch (e: Exception) {
                // next
            }
        }
        return null
    }

    /** Intro, recap and credits timings for a film or episode (null when IntroDB doesn't know it). */
    suspend fun segments(videoId: String, type: String): com.zapp.tv.data.Segments? = zapp.introDb.segments(videoId, type)

    /** The source last used for a title: its link or torrent, binge group, add-on and quality. */
    data class LastSource(val videoId: String, val key: String?, val binge: String?, val addon: String, val quality: Int)

    fun rememberSource(metaId: String, videoId: String, s: com.zapp.tv.addons.StreamItem) {
        val o = try { JSONObject(settings.lastSourcesJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        o.put(
            metaId,
            JSONObject().put("v", videoId).put("k", s.url ?: s.infoHash ?: "").put("b", s.bingeGroup ?: "")
                .put("a", s.addonName).put("q", s.qualityRank).put("t", System.currentTimeMillis()),
        )
        // Keep the 300 most recent.
        if (o.length() > 300) {
            val oldest = o.keys().asSequence().minByOrNull { o.optJSONObject(it)?.optLong("t") ?: 0L }
            oldest?.let { o.remove(it) }
        }
        settings.lastSourcesJson = o.toString()
    }

    fun lastSource(metaId: String): LastSource? = try {
        JSONObject(settings.lastSourcesJson.ifBlank { "{}" }).optJSONObject(metaId)?.let {
            LastSource(it.optString("v"), it.optString("k").ifBlank { null }, it.optString("b").ifBlank { null }, it.optString("a"), it.optInt("q"))
        }
    } catch (e: Exception) {
        null
    }

    // ---- collections (TMDB): franchises, studios, decades and themes

    /**
     * One collection as a card. [kind] is franchise, studio, decade or theme; [yours] how many of
     * its films you've saved or watched; [note] a short line under the name ("2 of 5 watched").
     */
    data class CollectionSummary(
        val id: Long,
        val name: String,
        val poster: String?,
        val backdrop: String?,
        val yours: Int = 0,
        val kind: String = "franchise",
        val shelf: String = "",
        val note: String? = null,
        /** Streaming services: a few of what's popular on it, for the card's poster wall. */
        val posters: List<String> = emptyList(),
        /** Streaming services: the service's own colour (ARGB). */
        val tint: Long = 0,
    )

    /** A row of collections in Library (and, if you add it, on Home). */
    data class CollectionShelf(val id: String, val title: String, val items: List<CollectionSummary>)

    /** A collection made from a TMDB search rather than a franchise. Ids are negative so they never meet TMDB's. */
    private data class Curated(
        val id: Long,
        val kind: String,
        val name: String,
        val blurb: String,
        val params: Map<String, String>,
        /** The same for series, where the collection has both (a text button switches). */
        val tvParams: Map<String, String>? = null,
        /** A TMDB keyword to add (looked up by name), e.g. "christmas". */
        val keyword: String? = null,
        /** Opens on series rather than films. */
        val seriesFirst: Boolean = false,
        /** In the order TMDB gives (most popular first) rather than by date. */
        val popularOrder: Boolean = false,
        /** A streaming service's TMDB provider id (for its logo). */
        val provider: Int = 0,
    )

    /** Each service's own colour, for the glow behind its logo. */
    private val SERVICE_COLOURS = mapOf(
        8 to 0xFFE50914, 9 to 0xFF00A8E1, 337 to 0xFF1A44D4, 350 to 0xFFB8BCC4, 531 to 0xFF0064FF,
        39 to 0xFF1BD4A6, 38 to 0xFFFF4C98, 41 to 0xFFD8F55A, 103 to 0xFF7BD34B,
    )

    /** Streaming services available in the UK, each a collection of what's on it. */
    private val networks: List<Curated> = listOf(
        8 to "Netflix", 9 to "Prime Video", 337 to "Disney+", 350 to "Apple TV+", 531 to "Paramount+",
        39 to "NOW", 38 to "BBC iPlayer", 41 to "ITVX", 103 to "Channel 4",
    ).map { (p, name) ->
        val base = mapOf("with_watch_providers" to p.toString(), "watch_region" to "GB", "with_watch_monetization_types" to "flatrate|free|ads", "sort_by" to "popularity.desc")
        Curated(
            -(200_000L + p), "network", name, "Popular on $name in the UK right now.",
            base + ("vote_count.gte" to "80"), base + ("vote_count.gte" to "40"),
            seriesFirst = true, popularOrder = true, provider = p,
        )
    }

    /** The collections for the time of year (none between seasons). Christmas runs from mid-November. */
    private fun seasonal(today: java.time.LocalDate = java.time.LocalDate.now()): List<Curated> {
        val m = today.monthValue
        val d = today.dayOfMonth
        fun before(y: Int) = mapOf("primary_release_date.lte" to "$y-12-31")
        fun from(y: Int) = mapOf("primary_release_date.gte" to "$y-01-01")
        val votes = mapOf("sort_by" to "vote_count.desc")
        fun c(id: Long, name: String, blurb: String, params: Map<String, String>, keyword: String? = null, tv: Map<String, String>? = null) =
            Curated(id, "seasonal", name, blurb, votes + params, tv?.let { mapOf("sort_by" to "popularity.desc") + it }, keyword)
        return when {
            (m == 11 && d >= 15) || m == 12 || (m == 1 && d <= 6) -> listOf(
                c(-301, "Christmas Classics", "The ones that make it Christmas.", before(1999), "christmas", emptyMap()),
                c(-302, "Christmas Favourites", "Newer festive favourites.", from(2000), "christmas", emptyMap()),
                c(-303, "Family Christmas", "For everyone on the sofa.", mapOf("with_genres" to "10751"), "christmas", mapOf("with_genres" to "10751")),
                c(-304, "Christmas Comedies", "Festive films to laugh at.", mapOf("with_genres" to "35"), "christmas", mapOf("with_genres" to "35")),
            )
            m == 10 -> listOf(
                c(-311, "Halloween Night", "Trick or treat.", emptyMap(), "halloween", emptyMap()),
                c(-312, "Horror Classics", "Before scares were digital.", before(1999) + ("with_genres" to "27")),
                c(-313, "Family Halloween", "Spooky, not scary.", mapOf("with_genres" to "10751"), "halloween"),
            )
            m == 2 && d <= 14 -> listOf(
                c(-321, "Romance Classics", "Love stories that last.", before(1999) + ("with_genres" to "10749")),
                c(-322, "Modern Romance", "Love stories since 2000.", from(2000) + ("with_genres" to "10749")),
                c(-323, "Romantic Comedies", "Love, with laughs.", mapOf("with_genres" to "10749,35")),
            )
            (m == 3 && d >= 20) || (m == 4 && d <= 25) -> listOf(
                c(-331, "Easter Holidays", "Films for the whole family.", mapOf("with_genres" to "10751")),
                c(-332, "Animated Adventures", "Big adventures, drawn and animated.", mapOf("with_genres" to "16,12")),
            )
            (m == 6 && d >= 15) || m == 7 || m == 8 -> listOf(
                c(-341, "Summer Blockbusters", "Big, loud and best on a big screen.", mapOf("with_genres" to "28,12", "vote_count.gte" to "3000")),
                c(-342, "Sunny Comedies", "Light, warm and funny.", mapOf("with_genres" to "35", "vote_average.gte" to "6.8", "vote_count.gte" to "1500")),
            )
            else -> emptyList()
        }
    }

    private fun curatedById(id: Long): Curated? = curated.firstOrNull { it.id == id } ?: networks.firstOrNull { it.id == id } ?: seasonal().firstOrNull { it.id == id }

    /** Whether a collection has a series version to switch to, and which it opens on. */
    fun collectionHasSeries(id: Long) = curatedById(id)?.tvParams != null
    fun collectionSeriesFirst(id: Long) = curatedById(id)?.seriesFirst == true

    /** In-season collections and streaming services, made live (not part of the weekly catalogue). */
    private var special by mutableStateOf<List<CollectionSummary>>(emptyList())
    private var specialJob: kotlinx.coroutines.Job? = null

    private fun loadSpecial() {
        if (specialJob?.isActive == true || (special.isNotEmpty())) return
        specialJob = viewModelScope.launch {
            // Each seasonal card gets a different film's picture (the best-known ones turn up in several).
            val found = seasonal().map { d -> async { d to collection(d.id) } }.awaitAll()
            val used = HashSet<String>()
            val season = found.mapNotNull { (d, c) ->
                c ?: return@mapNotNull null
                val pics = listOfNotNull(c.backdrop?.replace("/original/", "/w780/")) + c.parts.mapNotNull { it.item.backdrop?.replace("/w1280/", "/w780/") }
                val pick = pics.firstOrNull { it !in used } ?: pics.firstOrNull()
                pick?.let { used.add(it) }
                CollectionSummary(d.id, d.name, c.poster, pick, kind = "seasonal", shelf = "seasonal")
            }
            val logos = runCatching { tmdb.providerLogos() }.getOrDefault(emptyMap())
            val services = networks.map { d ->
                async {
                    val wall = runCatching { collection(d.id, series = true) }.getOrNull()?.parts?.mapNotNull { it.item.poster }?.take(4).orEmpty()
                    CollectionSummary(d.id, d.name, logos[d.provider], null, kind = "network", shelf = "networks", posters = wall, tint = SERVICE_COLOURS[d.provider] ?: 0xFF6E8BFF)
                }
            }.awaitAll()
            special = season + services
        }
    }

    /**
     * Pictures for a collection's card when it has none of its own (or they won't load): backdrops
     * of its films, best first, and up to four posters for a mosaic as the last resort.
     */
    suspend fun collectionArt(id: Long): Pair<List<String>, List<String>> {
        val c = collection(id) ?: return emptyList<String>() to emptyList()
        val backdrops = listOfNotNull(c.backdrop?.replace("/original/", "/w780/")) + c.parts.mapNotNull { it.item.backdrop?.replace("/w1280/", "/w780/") }
        return backdrops.distinct().take(6) to c.parts.mapNotNull { it.item.poster }.take(4)
    }

    private val curated: List<Curated> = run {
        fun studio(company: Int, name: String, blurb: String) =
            Curated(-(100_000L + company), "studio", name, blurb, mapOf("with_companies" to company.toString(), "sort_by" to "vote_count.desc"))
        fun span(from: Int?, to: Int?): Map<String, String> = buildMap {
            from?.let { put("primary_release_date.gte", "$it-01-01") }
            to?.let { put("primary_release_date.lte", "$it-12-31") }
        }
        fun decade(id: Long, name: String, from: Int, blurb: String) =
            Curated(id, "decade", name, blurb, span(from, from + 9) + mapOf("sort_by" to "vote_count.desc"))
        fun theme(id: Long, name: String, blurb: String, genres: String, from: Int? = null, to: Int? = null, extra: Map<String, String> = emptyMap()) =
            Curated(id, "theme", name, blurb, span(from, to) + mapOf("with_genres" to genres, "sort_by" to "vote_count.desc") + extra)
        listOf(
            studio(3, "Pixar", "Every Pixar film, from Toy Story on."),
            studio(10342, "Studio Ghibli", "Hand-drawn worlds from Miyazaki, Takahata and friends."),
            studio(41077, "A24", "The independent studio's best-loved films."),
            studio(420, "Marvel Studios", "The Marvel Cinematic Universe, film by film."),
            studio(1, "Lucasfilm", "Star Wars, Indiana Jones and more."),
            studio(521, "DreamWorks Animation", "Shrek, Kung Fu Panda, How to Train Your Dragon and more."),
            studio(3172, "Blumhouse", "Modern horror, made small and scary."),
            decade(-1, "'70s Classics", 1970, "The decade's best-known films."),
            decade(-2, "'80s Classics", 1980, "The decade's best-known films."),
            decade(-3, "'90s Classics", 1990, "The decade's best-known films."),
            decade(-4, "2000s Favourites", 2000, "The decade's best-known films."),
            decade(-5, "2010s Favourites", 2010, "The decade's best-known films."),
            theme(-11, "'80s Action", "Explosions, one-liners and synth.", "28", 1980, 1989),
            theme(-12, "'90s Action", "The blockbusters of the nineties.", "28", 1990, 1999),
            theme(-13, "Modern Horror", "The scariest films since 2010.", "27", from = 2010),
            theme(-14, "Animated Classics", "Animation from before 2000.", "16", to = 1999),
            theme(-15, "Mind-Bending Sci-Fi", "Science fiction that stays with you.", "878", extra = mapOf("vote_average.gte" to "7.4", "vote_count.gte" to "1500")),
            theme(-16, "Space Adventures", "Out among the stars.", "878,12"),
            theme(-17, "Crime Classics", "Gangsters, heists and detectives, before 2000.", "80", to = 1999),
            theme(-18, "Feel-Good Comedies", "Comedies people love.", "35", extra = mapOf("vote_average.gte" to "7.2", "vote_count.gte" to "1500")),
            theme(-19, "War Epics", "War on the big screen.", "10752"),
            theme(-20, "Westerns", "Out on the frontier.", "37"),
        )
    }

    /** Franchises you have at least one film of (saved or watched). */
    var collections by mutableStateOf<List<CollectionSummary>>(emptyList())
        private set
    /** Franchises you've started but not finished. */
    private var unfinished by mutableStateOf<List<CollectionSummary>>(emptyList())
    /** Every franchise, studio, decade and theme found on TMDB. */
    private var catalogue by mutableStateOf<List<CollectionSummary>>(emptyList())
    private var collectionsJob: kotlinx.coroutines.Job? = null
    private var catalogueJob: kotlinx.coroutines.Job? = null

    /** Changes when the Collections layout, pins or removals change (so the page redraws). */
    private var collectionLayoutTick by mutableStateOf(0)

    /** Shelves in your order: id, title, shown. New shelves join at the end, shown. */
    fun collectionShelfLayout(): List<Triple<String, String, Boolean>> {
        collectionLayoutTick
        val saved = try {
            val a = JSONArray(settings.collectionLayout.ifBlank { "[]" })
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("id") to it.optBoolean("on", true) }
        } catch (e: Exception) {
            emptyList()
        }
        val titles = COLLECTION_SHELVES.toMap()
        val out = saved.filter { it.first in titles }.map { Triple(it.first, titles[it.first]!!, it.second) }.toMutableList()
        // New shelves join where they belong by default (after the one before them), shown.
        COLLECTION_SHELVES.forEachIndexed { i, (id, t) ->
            if (out.none { it.first == id }) {
                val prev = COLLECTION_SHELVES.getOrNull(i - 1)?.first
                val at = out.indexOfFirst { it.first == prev }.let { if (it < 0) minOf(i, out.size) else it + 1 }
                out.add(at, Triple(id, t, true))
            }
        }
        return out
    }

    fun saveCollectionShelfLayout(list: List<Triple<String, String, Boolean>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.first).put("on", it.third)) }
        settings.collectionLayout = a.toString()
        collectionLayoutTick++
    }

    fun moveCollectionShelf(id: String, by: Int) {
        val l = collectionShelfLayout().toMutableList()
        val i = l.indexOfFirst { it.first == id }
        val j = (i + by).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        val x = l.removeAt(i)
        l.add(j, x)
        saveCollectionShelfLayout(l)
    }

    fun setCollectionShelfShown(id: String, on: Boolean) =
        saveCollectionShelfLayout(collectionShelfLayout().map { if (it.first == id) it.copy(third = on) else it })

    private fun summariesFrom(json: String): List<CollectionSummary> = try {
        val a = JSONArray(json.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            CollectionSummary(it.getLong("id"), it.optString("name"), it.optString("poster").ifBlank { null }, it.optString("backdrop").ifBlank { null }, kind = it.optString("kind", "franchise"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private val pinned: List<CollectionSummary> get() { collectionLayoutTick; return summariesFrom(settings.pinnedCollections) }
    private val removedFromYours: Set<Long> get() {
        collectionLayoutTick
        return settings.removedCollections.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    fun isPinned(id: Long) = pinned.any { it.id == id }
    fun isInYours(id: Long) = isPinned(id) || (collections.any { it.id == id } && id !in removedFromYours)

    /** Keep a collection at the front of Your collections (even before you've watched any of it). */
    fun pinCollection(c: CollectionSummary, pin: Boolean) {
        val list = pinned.filter { it.id != c.id }.toMutableList()
        if (pin) list.add(0, c)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("poster", it.poster ?: "").put("backdrop", it.backdrop ?: "").put("kind", it.kind)) }
        settings.pinnedCollections = a.toString()
        if (pin) settings.removedCollections = (removedFromYours - c.id).joinToString(",")
        collectionLayoutTick++
    }

    fun removeFromYours(id: Long) {
        pinCollection(CollectionSummary(id, "", null, null), pin = false)
        settings.removedCollections = (removedFromYours + id).joinToString(",")
        collectionLayoutTick++
    }

    /** Puts a collection shelf on Home (switched on, at the end if it wasn't there). */
    fun addShelfToHome(shelfId: String) {
        val rowId = if (shelfId == "yours") COLLECTIONS_ROW else "collections:$shelfId"
        val a = try { JSONArray(settings.homeRowsJson.ifBlank { "[]" }) } catch (e: Exception) { JSONArray() }
        var found = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == rowId) { o.put("on", true); found = true }
        }
        if (!found) a.put(JSONObject().put("id", rowId).put("on", true).put("size", 1).put("landscape", true))
        settings.homeRowsJson = a.toString()
    }

    /** The shelves in your order, the hidden and empty ones left out. */
    val collectionShelves: List<CollectionShelf>
        get() {
            val yours = pinned + collections.filter { c -> c.id !in removedFromYours && pinned.none { it.id == c.id } }
            val built = HashMap<String, CollectionShelf>()
            built["yours"] = CollectionShelf("yours", "Your collections", yours)
            built["finish"] = CollectionShelf("finish", "Finish what you started", unfinished)
            COLLECTION_SHELVES.filter { it.first !in setOf("yours", "finish") }.forEach { (id, title) ->
                built[id] = CollectionShelf(id, title, (catalogue + special).filter { it.shelf == id })
            }
            return collectionShelfLayout().filter { it.third }.mapNotNull { built[it.first] }.filter { it.items.isNotEmpty() }
        }

    fun collectionShelf(id: String): CollectionShelf? = collectionShelves.firstOrNull { it.id == id }

    /** Works out your collections from the films in your library and the ones you've watched. */
    fun refreshCollections() {
        if (!tmdb.enabled) return
        loadCatalogue()
        loadSpecial()
        collectionsJob?.cancel()
        collectionsJob = viewModelScope.launch {
            delay(1_500)
            val watched = watchedTitles.value
            val films = (library.value.filter { it.type == "movie" }.map { it.metaId } + watched)
                .filter { it.startsWith("tt") }.distinct().take(160)
            val found = LinkedHashMap<Long, CollectionSummary>()
            val watchedTmdb = HashSet<Long>()
            val watchedIn = HashMap<Long, Int>()
            for (id in films) {
                val d = runCatching { tmdb.details(id, "movie") }.getOrNull() ?: continue
                if (id in watched) watchedTmdb.add(d.tmdbId)
                val c = d.collection ?: continue
                val prev = found[c.id]
                found[c.id] = CollectionSummary(c.id, c.name, c.poster, c.backdrop, (prev?.yours ?: 0) + 1)
                if (id in watched) watchedIn[c.id] = (watchedIn[c.id] ?: 0) + 1
            }
            collections = found.values.sortedByDescending { it.yours }
            // Started but not finished: some watched, more already out.
            val today = java.time.LocalDate.now().toString()
            unfinished = found.values.filter { (watchedIn[it.id] ?: 0) > 0 }.mapNotNull { s ->
                val c = collection(s.id) ?: return@mapNotNull null
                val out = c.parts.filter { (it.released ?: "9999") <= today }
                val seen = out.count { it.item.id in watchedTmdb }
                if (seen in 1 until out.size) s.copy(note = "$seen of ${out.size} watched") else null
            }
        }
    }

    /** The catalogue, from the copy on the box; looked for again on TMDB once a week. */
    private fun loadCatalogue() {
        if (catalogue.isEmpty()) catalogue = parseCatalogue(settings.collectionCatalogue)
        val stale = System.currentTimeMillis() - settings.collectionCatalogueAt > 7 * 24 * 3_600_000L
        if ((catalogue.isEmpty() || stale) && catalogueJob?.isActive != true) {
            catalogueJob = viewModelScope.launch { buildCatalogue() }
        }
    }

    private fun parseCatalogue(json: String): List<CollectionSummary> = try {
        val a = JSONArray(json.ifBlank { "[]" })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CollectionSummary(
                o.getLong("id"), o.getString("name"), o.optString("poster").ifBlank { null }, o.optString("backdrop").ifBlank { null },
                kind = o.optString("kind", "franchise"), shelf = o.optString("shelf"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Which shelf a franchise goes on, from the genres of its best-known film. */
    private fun shelfFor(genres: List<Int>, superhero: Boolean): String = when {
        superhero -> "superheroes"
        16 in genres || 10751 in genres -> "family"
        27 in genres -> "horror"
        878 in genres -> "scifi"
        14 in genres -> "fantasy"
        28 in genres || 12 in genres -> "action"
        80 in genres || 53 in genres || 9648 in genres -> "crime"
        35 in genres -> "comedy"
        else -> "more"
    }

    private suspend fun buildCatalogue() = withContext(Dispatchers.IO) {
        // The most-watched films on TMDB (and today's popular ones), and the franchises they belong to.
        val heroes = tmdb.discoverFilms(mapOf("with_keywords" to "9715", "sort_by" to "vote_count.desc"), 3)
        val heroIds = heroes.map { it.first }.toSet()
        val films = (tmdb.discoverFilms(mapOf("sort_by" to "vote_count.desc"), 25) +
            tmdb.discoverFilms(mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "400"), 5) + heroes).distinctBy { it.first }
        if (films.isEmpty()) return@withContext
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val refs = films.map { f -> async { gate.withPermit { f to runCatching { tmdb.filmCollection(f.first) }.getOrNull() } } }.awaitAll()
        val franchises = LinkedHashMap<Long, CollectionSummary>()
        for ((f, ref) in refs) {
            ref ?: continue
            val prev = franchises[ref.id]
            if (prev == null) {
                franchises[ref.id] = CollectionSummary(ref.id, ref.name, ref.poster, ref.backdrop, shelf = shelfFor(f.second, f.first in heroIds))
            } else if (f.first in heroIds && prev.shelf != "superheroes") {
                franchises[ref.id] = prev.copy(shelf = "superheroes")
            }
        }
        // Studios, decades and themes: their best-known film's artwork.
        val made = curated.map { d ->
            async {
                gate.withPermit {
                    tmdb.discoverCollection(d.id, d.name, d.blurb, d.params)?.let { c ->
                        CollectionSummary(d.id, d.name, c.poster, c.backdrop?.replace("/original/", "/w780/"), kind = d.kind, shelf = d.kind + "s")
                    }
                }
            }
        }.awaitAll().filterNotNull()
        val all = franchises.values.toList() + made
        if (franchises.size < 10) return@withContext // TMDB was having a bad moment; try again next time
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("poster", it.poster ?: "").put("backdrop", it.backdrop ?: "").put("kind", it.kind).put("shelf", it.shelf)) }
        settings.collectionCatalogue = arr.toString()
        settings.collectionCatalogueAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) { catalogue = all }
    }

    suspend fun collection(id: Long, series: Boolean = false): com.zapp.tv.data.TmdbCollection? = runCatching {
        if (id < 0) {
            curatedById(id)?.let { d ->
                val kw = d.keyword?.let { tmdb.keywordId(it) }
                if (d.keyword != null && kw == null) return@let null
                fun withKw(p: Map<String, String>) = if (kw != null) p + ("with_keywords" to kw.toString()) else p
                val tv = d.tvParams
                if (series && tv != null) tmdb.discoverCollection(d.id - 10_000_000L, d.name, d.blurb, withKw(tv), tv = true, byDate = !d.popularOrder)
                else tmdb.discoverCollection(d.id, d.name, d.blurb, withKw(d.params), byDate = !d.popularOrder && d.kind != "seasonal")
            }
        } else {
            tmdb.collection(id)
        }
    }.getOrNull()

    /** "COLLECTION", "STUDIO", "DECADE" or "THEME", for the showcase and cards. */
    fun collectionLabel(id: Long): String = when (curatedById(id)?.kind) {
        "network" -> "STREAMING"
        "seasonal" -> "IN SEASON"
        "studio" -> "STUDIO"
        "decade" -> "DECADE"
        "theme" -> "THEME"
        else -> "COLLECTION"
    }

    /** IMDb ids for a collection's films (to open them, and to tick the watched ones). */
    suspend fun collectionImdbIds(c: com.zapp.tv.data.TmdbCollection): Map<Long, String> = kotlinx.coroutines.coroutineScope {
        c.parts.map { p -> async { p.item.id to runCatching { tmdb.imdbId(p.item) }.getOrNull() } }
            .awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /** While a collection's card is focused: find its theme tune now, so it plays the moment it opens. */
    fun prefetchTheme(id: Long, name: String = "") {
        if (!settings.themeMusic || id <= 0) return
        viewModelScope.launch {
            if (name.isNotBlank() && themeCandidates(id, name).isNotEmpty()) return@launch
            val yt = collectionTheme(id) ?: return@launch
            zapp.youtube.prefetch(yt, 0, audioOnly = true)
        }
    }

    // ---- theme tunes: 30-second previews from Apple Music or Deezer, or the one you chose

    suspend fun themeCandidates(id: Long, name: String): List<com.zapp.tv.data.ThemeTrack> =
        if (id > 0) runCatching { zapp.themePreviews.search(id, name) }.getOrDefault(emptyList()) else emptyList()

    /** The tune you picked for a collection, if you picked one. */
    fun chosenTheme(id: Long): com.zapp.tv.data.ThemeTrack? = try {
        JSONObject(settings.themeChoices.ifBlank { "{}" }).optJSONObject(id.toString())?.let {
            com.zapp.tv.data.ThemeTrack(it.optString("t"), it.optString("a"), it.optString("u"), it.optString("s"))
        }
    } catch (e: Exception) {
        null
    }

    /** Remember [track] for this collection (null: back to automatic). */
    fun chooseTheme(id: Long, track: com.zapp.tv.data.ThemeTrack?) {
        val o = try { JSONObject(settings.themeChoices.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        if (track == null) o.remove(id.toString())
        else o.put(id.toString(), JSONObject().put("t", track.title).put("a", track.artist).put("u", track.url).put("s", track.source))
        settings.themeChoices = o.toString()
    }

    /** Only franchises have theme tunes. */
    suspend fun collectionTheme(id: Long): String? = if (settings.themeMusic && id > 0) runCatching { zapp.themes.collectionTheme(id) }.getOrNull() else null

    /** Episode ratings for one season (TMDB), by episode number. */
    suspend fun episodeRatings(seriesId: String, season: Int): Map<Int, Double> {
        if (!tmdb.enabled || !seriesId.startsWith("tt")) return emptyMap()
        val id = runCatching { tmdb.details(seriesId, "series")?.tmdbId }.getOrNull() ?: return emptyMap()
        return runCatching { tmdb.seasonRatings(id, season) }.getOrDefault(emptyMap())
    }

    /** Something to watch next, with why it's suggested. */
    data class Suggestion(val meta: MetaPreview, val reason: String, val trailer: String?)

    /**
     * After a film (or the latest episode of a series): the next film in its collection if you
     * haven't seen it, then titles people also watched, skipping anything you've watched.
     */
    suspend fun suggestionsAfter(finished: MetaPreview): List<Suggestion> {
        if (!tmdb.enabled || !finished.id.startsWith("tt")) return emptyList()
        val d = runCatching { tmdb.details(finished.id, finished.type) }.getOrNull() ?: return emptyList()
        val seen = watchedTitles.value + finished.id + ratings.all().filter { it.rating == com.zapp.tv.data.Rating.Dislike }.map { it.metaId }
        val out = ArrayList<Triple<String, String, String>>() // imdb id, reason, type
        d.collection?.let { ref ->
            val c = collection(ref.id)
            if (c != null) {
                val ids = collectionImdbIds(c)
                val idx = c.parts.indexOfFirst { ids[it.item.id] == finished.id }
                c.parts.drop(idx + 1).firstOrNull { p -> ids[p.item.id]?.let { it !in seen } == true }?.let { p ->
                    out.add(Triple(ids[p.item.id]!!, "Next in the ${c.name} collection", "movie"))
                }
            }
        }
        for (r in d.recommendations.take(10)) {
            if (out.size >= 5) break
            val id = runCatching { tmdb.imdbId(r) }.getOrNull() ?: continue
            if (id in seen || out.any { it.first == id }) continue
            out.add(Triple(id, "Because you watched ${finished.name}", r.type))
        }
        return kotlinx.coroutines.coroutineScope {
            out.map { (id, reason, type) ->
                async {
                    runCatching { meta(type, id) }.getOrNull()?.let { m -> Suggestion(m.preview, reason, m.trailerYoutube) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ---- your ratings (Dislike / Like / Love) and the taste they add up to

    val ratings get() = zapp.ratings
    fun ratingOf(id: String): com.zapp.tv.data.Rating? = ratings.get(id)

    /** Bumped when your taste changes, so rows built from it are worked out again. */
    var tasteVersion by mutableStateOf(0)
        private set
    private var taste: Map<String, Double> = emptyMap()
    private var tasteJob: kotlinx.coroutines.Job? = null

    fun rate(meta: MetaPreview, r: com.zapp.tv.data.Rating?) {
        ratings.set(meta.id, meta.type, meta.name, meta.poster, r)
        if (simkl.client.signedIn) viewModelScope.launch { simkl.rate(meta.id, meta.type, r?.simkl) }
        recomputeTaste()
    }

    /** Genres, lead cast and directors of a title, as taste features. */
    private suspend fun features(id: String, type: String): List<String> {
        val d = runCatching { tmdb.details(id, type) }.getOrNull() ?: return emptyList()
        return d.genres.map { "g:${it.first}" } + d.cast.take(5).map { "p:${it.name}" } + d.directors.map { "d:$it" }
    }

    /** Loved counts most, liked a little, disliked against. */
    fun recomputeTaste() {
        if (!tmdb.enabled) return
        tasteJob?.cancel()
        tasteJob = viewModelScope.launch {
            delay(400)
            val t = HashMap<String, Double>()
            for (r in ratings.all().take(150)) {
                val w = when (r.rating) {
                    com.zapp.tv.data.Rating.Love -> 3.0
                    com.zapp.tv.data.Rating.Like -> 1.0
                    com.zapp.tv.data.Rating.Dislike -> -2.0
                }
                features(r.metaId, r.type).forEach { f -> t[f] = (t[f] ?: 0.0) + w * (if (f.startsWith("g:")) 0.6 else 1.0) }
            }
            taste = t
            tasteVersion++
        }
    }

    /** How well a title fits your taste, as a percentage (null until you've rated a few). */
    suspend fun matchPercent(id: String, type: String): Int? {
        if (ratings.all().size < 3 || taste.isEmpty()) return null
        val f = features(id, type)
        if (f.isEmpty()) return null
        val s = f.sumOf { taste[it] ?: 0.0 } / kotlin.math.sqrt(f.size.toDouble())
        return (50 + 49 * kotlin.math.tanh(s / 2.5)).toInt().coerceIn(1, 99)
    }

    private suspend fun previewsOf(items: List<com.zapp.tv.data.TmdbItem>, limit: Int): List<MetaPreview> {
        val seen = watchedTitles.value
        return kotlinx.coroutines.coroutineScope {
            items.take(limit * 2).map { async { tmdbToPreview(it) } }.awaitAll().filterNotNull()
                .filter { it.id !in seen && ratingOf(it.id) == null }
                .distinctBy { it.id }
                .take(limit)
        }
    }

    /** "For you": titles recommended by several things you liked or loved. */
    suspend fun forYou(): List<MetaPreview> {
        val liked = ratings.all().filter { it.rating != com.zapp.tv.data.Rating.Dislike }.take(15)
        if (liked.isEmpty() || !tmdb.enabled) return emptyList()
        val score = HashMap<Long, Pair<com.zapp.tv.data.TmdbItem, Double>>()
        for (r in liked) {
            val w = if (r.rating == com.zapp.tv.data.Rating.Love) 3.0 else 1.0
            val recs = runCatching { tmdb.details(r.metaId, r.type)?.recommendations }.getOrNull().orEmpty()
            recs.forEachIndexed { i, item -> score[item.id] = item to ((score[item.id]?.second ?: 0.0) + w * (1.0 - i / 40.0)) }
        }
        return previewsOf(score.values.sortedByDescending { it.second }.map { it.first }, 20)
    }

    /** "Because you loved …": the most recent love and what's like it. */
    suspend fun becauseYouLoved(): Pair<String, List<MetaPreview>>? {
        // Your latest love first; if it hasn't enough you haven't seen, the one before, and so on.
        for (loved in ratings.all().filter { it.rating == com.zapp.tv.data.Rating.Love }.take(5)) {
            val recs = runCatching { tmdb.details(loved.metaId, loved.type)?.recommendations }.getOrNull().orEmpty()
            val list = previewsOf(recs, 15)
            if (list.size >= 3) return loved.name to list
        }
        return null
    }

    /** A time of year with its own Home row: [key] picks its decorations. */
    data class Season(val key: String, val title: String, val params: Map<String, String>, val keyword: String? = null, val tv: Boolean = false)

    /** Christmas runs from mid-November into early January; Halloween all October; and so on. */
    fun currentSeason(today: java.time.LocalDate = java.time.LocalDate.now()): Season? {
        val m = today.monthValue
        val d = today.dayOfMonth
        return when {
            (m == 11 && d >= 15) || m == 12 || (m == 1 && d <= 6) -> Season("christmas", "Christmas picks", emptyMap(), "christmas", tv = true)
            m == 10 -> Season("halloween", "Halloween picks", emptyMap(), "halloween")
            m == 2 && d <= 14 -> Season("valentine", "Love stories", mapOf("with_genres" to "10749"))
            (m == 3 && d >= 20) || (m == 4 && d <= 25) -> Season("easter", "Easter holiday picks", mapOf("with_genres" to "10751"))
            (m == 6 && d >= 15) || m == 7 || m == 8 -> Season("summer", "Summer blockbusters", mapOf("with_genres" to "28|12", "vote_count.gte" to "2000"))
            else -> null
        }
    }

    private var seasonCache: Pair<Long, List<MetaPreview>>? = null

    /**
     * The season's row: a fresh set every day from a large pool (classics and newer ones), a few of
     * the first places going to ones in genres you like, nothing you've watched, and none of
     * yesterday's first picks first again.
     */
    suspend fun seasonalPicks(): List<MetaPreview> {
        val season = currentSeason() ?: return emptyList()
        if (!tmdb.enabled) return emptyList()
        val day = System.currentTimeMillis() / 86_400_000L
        seasonCache?.takeIf { it.first == day }?.let { return it.second }
        val kw = season.keyword?.let { tmdb.keywordId(it) }
        if (season.keyword != null && kw == null) return emptyList()
        val p = season.params + (kw?.let { mapOf("with_keywords" to it.toString()) } ?: emptyMap())
        val pool = runCatching {
            tmdb.discoverItems(p + ("sort_by" to "vote_count.desc"), 5) +
                tmdb.discoverItems(p + mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "50"), 2) +
                (if (season.tv) tmdb.discoverItems(mapOf("sort_by" to "popularity.desc") + (kw?.let { mapOf("with_keywords" to it.toString()) } ?: emptyMap()), 1, tv = true) else emptyList())
        }.getOrDefault(emptyList()).distinctBy { it.first.type + it.first.id }
        val liked = taste.filterKeys { it.startsWith("g:") }.filterValues { it > 0 }.keys.mapNotNull { it.removePrefix("g:").toIntOrNull() }.toSet()
        val rnd = java.util.Random(day * 7919L + season.key.hashCode())
        val mine = pool.filter { (_, g) -> g.any { it in liked } }.shuffled(rnd).take(4).map { it.first }
        val rest = pool.map { it.first }.filter { it !in mine }.shuffled(rnd)
        val list = previewsOf(mine + rest, 18)
        // Kept for the day only when it came out whole (a bad moment on TMDB is tried again).
        if (list.size >= 8) seasonCache = day to list
        return list
    }

    /** "Hidden gems for you": well-rated, less famous titles in your favourite genres. */
    suspend fun hiddenGems(): List<MetaPreview> {
        val genres = taste.filterKeys { it.startsWith("g:") }.filterValues { it > 0 }.entries.sortedByDescending { it.value }
            .mapNotNull { it.key.removePrefix("g:").toIntOrNull() }.take(2)
        if (genres.isEmpty()) return emptyList()
        val items = runCatching { tmdb.gems(genres) }.getOrDefault(emptyList())
        return previewsOf(items.shuffled(java.util.Random(System.currentTimeMillis() / 86_400_000L)), 15)
    }

    /** For "Rate a few": popular films right now. */
    suspend fun popularToRate(): List<MetaPreview> = previewsOf(runCatching { tmdb.popular() }.getOrDefault(emptyList()), 15)

    /**
     * Things you've definitely watched, to rate: films and series marked or finished as watched,
     * and series you've seen at least three episodes of. Newest first; ones you've rated stay in.
     */
    suspend fun watchedToRate(): List<MetaPreview> = withContext(Dispatchers.IO) {
        val pid = settings.profileId
        val dao = zapp.mediaDao
        val lib = dao.allLibrary(pid).associateBy { it.metaId }
        val rows = (dao.watchedTitleRows(pid) + dao.seriesWatchedRows(pid, 3)).distinctBy { it.metaId }.take(60)
        rows.map { r ->
            async {
                val l = lib[r.metaId]
                var name = l?.name?.takeIf { it.isNotBlank() && !it.startsWith("tt") } ?: r.name.takeIf { it.isNotBlank() && !it.startsWith("tt") }
                var poster = l?.poster ?: r.poster
                if (name == null || poster == null) {
                    val m = runCatching { meta(r.type, r.metaId) }.getOrNull()
                    name = name ?: m?.preview?.name
                    poster = poster ?: m?.preview?.poster
                }
                val n = name ?: return@async null
                MetaPreview(r.metaId, r.type, n, poster ?: fallbackPoster(r.metaId), "poster", r.background, null, null, null, null, emptyList())
            }
        }.awaitAll().filterNotNull()
    }

    // ---- MDBList ratings (optional, with your own key)
    val mdblist get() = zapp.mdblist
    suspend fun ratings(id: String, type: String): List<com.zapp.tv.data.RatingBadge> = runCatching { mdblist.ratings(id, type) }.getOrDefault(emptyList())

    // ---- TMDB (optional, with your own key)
    val tmdb get() = zapp.tmdb
    suspend fun tmdbDetails(id: String, type: String): com.zapp.tv.data.TmdbDetails? = if (tmdb.enabled) tmdb.details(id, type) else null
    suspend fun tmdbPersonWork(personId: Long, except: Long) = tmdb.personWork(personId, except)

    /** Opens a TMDB title in Zapp (finding its IMDb id first). */
    suspend fun tmdbToPreview(item: com.zapp.tv.data.TmdbItem): MetaPreview? {
        val imdb = tmdb.imdbId(item) ?: return null
        return MetaPreview(imdb, item.type, item.name, item.poster, "poster", item.backdrop, null, null, item.year, null, emptyList())
    }

    fun hideFromContinue(metaId: String) = viewModelScope.launch {
        val pid = settings.profileId
        if (nuvioReady()) nuvio.queueContinueRemoval(pid, metaId)
        repo.hideFromContinue(metaId)
        flushNuvio()
    }

    fun inLibrary(metaId: String): Flow<Boolean> = repo.inLibrary(metaId)

    /** Films and series you've marked (or finished) as watched, for the little tick on cards. */
    val watchedTitles: StateFlow<Set<String>> =
        repo.watchedTitles.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // ---- marks on posters: series you've seen every aired episode of (a tick), and what you're
    // part-way through (a ring, with the next episode for series).

    private val airedPrefs = zapp.getSharedPreferences("aired_episodes", android.content.Context.MODE_PRIVATE)
    private val aired = java.util.concurrent.ConcurrentHashMap<String, com.zapp.tv.data.AiredEpisodes>().apply {
        val fresh = System.currentTimeMillis() - 3L * 24 * 3600_000
        airedPrefs.all.forEach { (id, v) ->
            val parts = (v as? String)?.split('|') ?: return@forEach
            if (parts.size < 3 || (parts[2].toLongOrNull() ?: 0) < fresh) return@forEach
            val seasons = parts[1].split(',').filter { ':' in it }.associate { it.substringBefore(':').toInt() to it.substringAfter(':').toInt() }
            put(id, com.zapp.tv.data.AiredEpisodes(parts[0].toIntOrNull() ?: 0, seasons))
        }
    }
    private val airedAsked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val airedTick = MutableStateFlow(0)

    /** (Series finished, what's part-watched.) */
    val posterMarks: StateFlow<Pair<Set<String>, Map<String, ProgressMark>>> =
        kotlinx.coroutines.flow.combine(repo.allProgress, airedTick) { rows, _ -> computeMarks(rows) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet<String>() to emptyMap())

    private fun computeMarks(rows: List<com.zapp.tv.data.WatchProgress>): Pair<Set<String>, Map<String, ProgressMark>> {
        val done = HashSet<String>()
        val marks = HashMap<String, ProgressMark>()
        val need = ArrayList<String>()
        fun frac(r: com.zapp.tv.data.WatchProgress): Float? =
            r.percent?.let { (it / 100.0).toFloat() } ?: if (r.duration > 0) r.position.toFloat() / r.duration else null
        fun episode(videoId: String): Pair<Int, Int>? {
            val p = videoId.split(':')
            if (p.size < 3) return null
            val s = p[p.size - 2].toIntOrNull() ?: return null
            val e = p.last().toIntOrNull() ?: return null
            return s to e
        }
        rows.filter { !it.videoId.startsWith("catchup:") }.groupBy { it.metaId }.forEach { (id, list) ->
            // Marked watched as a whole: the tick comes from watchedTitles.
            if (list.any { it.videoId == id && it.watched }) return@forEach
            val eps = list.mapNotNull { r -> episode(r.videoId)?.let { it to r } }
            if (eps.isEmpty()) {
                val r = list.firstOrNull { it.videoId == id } ?: return@forEach
                if (r.hidden || r.watched) return@forEach
                val f = frac(r) ?: return@forEach
                if (f in 0.02f..0.95f) marks[id] = ProgressMark(f)
                return@forEach
            }
            val seen = eps.filter { it.second.watched && it.first.first > 0 }.map { it.first }.toSet()
            val info = aired[id]
            if (info == null && id.startsWith("tt")) need += id
            if (info != null && info.total > 0 && seen.size >= info.total) {
                done += id
                return@forEach
            }
            val latest = eps.maxByOrNull { it.second.updatedAt } ?: return@forEach
            val (s, e) = latest.first
            val next = when {
                !latest.second.watched -> s to e
                info?.seasons?.get(s)?.let { e >= it } == true -> (s + 1) to 1
                else -> s to (e + 1)
            }
            val fraction = if (info != null && info.total > 0) seen.size.toFloat() / info.total else 0.3f
            marks[id] = ProgressMark(fraction.coerceIn(0.06f, 0.97f), "S${next.first} E${next.second}")
        }
        if (need.isNotEmpty()) fetchAired(need)
        return done to marks
    }

    /** Asks TMDB how many episodes have aired, a few series at a time, then updates the marks. */
    private fun fetchAired(ids: List<String>) {
        if (!tmdb.enabled) return
        val todo = ids.filter { airedAsked.add(it) }
        if (todo.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var changed = false
            todo.forEach { id ->
                val a = runCatching { tmdb.airedEpisodes(id) }.getOrNull() ?: return@forEach
                aired[id] = a
                airedPrefs.edit().putString(id, "${a.total}|${a.seasons.entries.joinToString(",") { "${it.key}:${it.value}" }}|${System.currentTimeMillis()}").apply()
                changed = true
            }
            if (changed) airedTick.value++
        }
    }

    // (After watchedTitles, which it needs.) Collections follow your library and what you've watched.
    init {
        recomputeTaste()
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(library, watchedTitles) { l, w -> l.size to w.size }
                .distinctUntilChanged()
                .collect { refreshCollections() }
        }
    }


    private fun watchedRow(meta: MetaPreview, videoId: String, label: String?, existing: WatchProgress?, now: Long) = WatchProgress(
        id = existing?.id ?: 0,
        profileId = settings.profileId,
        videoId = videoId,
        metaId = meta.id,
        type = meta.type,
        name = meta.name,
        episodeLabel = label,
        poster = meta.poster,
        background = existing?.background ?: meta.background,
        position = existing?.duration?.takeIf { it > 0 } ?: 0,
        duration = existing?.duration ?: 0,
        watched = true,
        hidden = false,
        updatedAt = now,
        percent = 100.0,
    )

    /**
     * Marks a film or a whole series as watched (every episode out so far), or clears it again.
     * Goes to Nuvio and, for films, Simkl.
     */
    fun setWatched(meta: MetaPreview, watched: Boolean) = viewModelScope.launch {
        val pid = settings.profileId
        val now = System.currentTimeMillis()
        val dao = zapp.mediaDao
        if (!watched) {
            val rows = dao.progressOfMeta(pid, meta.id).filter { it.watched }
            rows.forEach { nuvio.queueWatched(pid, meta.id, meta.type, it.videoId, meta.name, false) }
            dao.deleteWatched(pid, meta.id)
            flushNuvio()
            return@launch
        }
        val rows = ArrayList<WatchProgress>()
        if (meta.type == "series") {
            val d = runCatching { repo.meta("series", meta.id) }.getOrNull()
            d?.videos?.filter { (it.season ?: 0) > 0 && (releasedAt(it.released) ?: 0) <= now }?.forEach { v ->
                rows.add(watchedRow(meta, v.id, episodeLabel(v), dao.progress(pid, v.id), now))
                nuvio.queueWatched(pid, meta.id, "series", v.id, meta.name, true)
            }
        } else {
            nuvio.queueWatched(pid, meta.id, meta.type, meta.id, meta.name, true)
            if (simkl.client.signedIn) launch { runCatching { simkl.scrobble("stop", meta.id, meta.type, meta.name, 100.0) } }
        }
        // The title itself (for series, a marker meaning "all of it").
        rows.add(watchedRow(meta, meta.id, null, dao.progress(pid, meta.id), now))
        dao.saveProgressList(rows)
        flushNuvio()
    }

    /** Marks some episodes watched or unwatched (from the details page or Continue watching). */
    fun setEpisodesWatched(meta: MetaPreview, episodes: List<Video>, watched: Boolean) = viewModelScope.launch {
        val pid = settings.profileId
        val now = System.currentTimeMillis()
        val dao = zapp.mediaDao
        if (watched) {
            dao.saveProgressList(episodes.map { v -> watchedRow(meta, v.id, episodeLabel(v), dao.progress(pid, v.id), now) })
            episodes.forEach { v ->
                nuvio.queueWatched(pid, meta.id, "series", v.id, meta.name, true)
                if (simkl.client.signedIn) launch { runCatching { simkl.scrobble("stop", v.id, "series", meta.name, 100.0) } }
            }
        } else {
            episodes.forEach { v ->
                val p = dao.progress(pid, v.id) ?: return@forEach
                if (!p.watched) return@forEach
                dao.deleteProgressRow(p.id)
                nuvio.queueWatched(pid, meta.id, "series", v.id, meta.name, false)
            }
            // Not all of it any more.
            dao.progress(pid, meta.id)?.takeIf { it.watched }?.let { dao.deleteProgressRow(it.id) }
        }
        flushNuvio()
    }

    /**
     * From Continue watching: marks that episode (or film) watched. For a series, the next episode
     * then takes its place as Up next.
     */
    fun markContinueWatched(p: WatchProgress) = viewModelScope.launch {
        val preview = p.asPreview()
        if (p.type != "series") {
            setWatched(preview, true)
            return@launch
        }
        val d = runCatching { repo.meta("series", p.metaId) }.getOrNull()
        val videos = d?.videos.orEmpty()
        val v = videos.firstOrNull { it.id == p.videoId }
        if (v == null) {
            setEpisodesWatched(preview, listOf(Video(p.videoId, "", null, null, null, null, null)), true)
            return@launch
        }
        setEpisodesWatched(d!!.preview, listOf(v), true).join()
        val i = videos.indexOf(v)
        videos.drop(i + 1).firstOrNull { (it.season ?: 0) > 0 }?.let { next ->
            if ((releasedAt(next.released) ?: 0) <= System.currentTimeMillis()) {
                markUpNext(MediaInfo(p.videoId, p.metaId, p.type, d.preview.name, null, d.preview.poster, d.preview.background, "", emptyMap()), next)
            }
        }
    }

    fun toggleLibrary(meta: MetaPreview, currentlyIn: Boolean) = viewModelScope.launch {
        repo.toggleLibrary(meta, currentlyIn)
        if (!currentlyIn && simkl.client.signedIn) simkl.addToWatchlist(meta.id, meta.type)
        nuvio.queueLibrary(settings.profileId, meta.id, meta.type, meta.name, meta.poster, !currentlyIn)
        flushNuvio()
        if (stremio.signedIn) runCatching { stremio.pushLibrary(meta.id, meta.type, meta.name, meta.poster, !currentlyIn) }
    }

    // ------------------------------------------------------------ Home layout

    /**
     * Every row Home can show, in the user's order. Rows the user hasn't arranged yet are added
     * after the arranged ones (new add-on catalogues show up switched on).
     */
    fun homeLayout(catalogs: List<CatalogRef>): List<HomeRowSetting> {
        val available = LinkedHashMap<String, String>()
        available[CONTINUE_ROW] = "Continue watching"
        available[BECAUSE_ROW] = "Because you watched…"
        available[NEW_EPISODES_ROW] = "New episodes"
        available[FOR_YOU_ROW] = "For you"
        available[LOVED_ROW] = "Because you loved…"
        available[GEMS_ROW] = "Hidden gems for you"
        available[SEASON_ROW] = currentSeason()?.title ?: "In season (Christmas and other times of year)"
        catalogs.forEach { available[it.key] = it.title }
        available[LIBRARY_ROW] = "Your library"
        available[COLLECTIONS_ROW] = "Your collections"
        COLLECTION_SHELVES.drop(1).forEach { (id, title) -> available["collections:$id"] = "Collections: $title" }
        available[SPORTS_ROW] = "Your teams"

        val sources = HashMap<String, String>()
        listOf(CONTINUE_ROW, BECAUSE_ROW, NEW_EPISODES_ROW, FOR_YOU_ROW, LOVED_ROW, GEMS_ROW, SEASON_ROW).forEach { sources[it] = "Smart rows" }
        catalogs.forEach { sources[it.key] = it.addon.manifest.name }
        sources[LIBRARY_ROW] = "Library"
        available.keys.filter { it == COLLECTIONS_ROW || it.startsWith("collections:") }.forEach { sources[it] = "Collections" }
        sources[SPORTS_ROW] = "Sports"

        val saved = parseLayout(settings.homeRowsJson)
        val result = ArrayList<HomeRowSetting>()
        for (r in saved) {
            val title = available[r.id] ?: continue
            result.add(r.copy(title = title, source = sources[r.id].orEmpty()))
        }
        for ((id, title0) in available) {
            val title = title0
            if (result.none { it.id == id }) {
                // Collections start switched off on Home (they live in Library); turn them on in Home layout.
                val shelfRow = id == COLLECTIONS_ROW || id.startsWith("collections:")
                val row = HomeRowSetting(id, title, !shelfRow, landscape = id == CONTINUE_ROW || shelfRow, source = sources[id].orEmpty())
                // New smart rows go just below Continue watching; everything else at the end.
                if (id == BECAUSE_ROW || id == NEW_EPISODES_ROW || id == SPORTS_ROW || id == FOR_YOU_ROW || id == LOVED_ROW || id == GEMS_ROW || id == SEASON_ROW) {
                    result.add(result.indexOfFirst { it.id == CONTINUE_ROW } + 1, row)
                } else {
                    result.add(row)
                }
            }
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
            arr.put(JSONObject().put("id", it.id).put("on", it.on).put("size", it.size).put("landscape", it.landscape).apply {
                it.top10?.let { t -> put("top10", t) }
                it.name?.let { n -> put("name", n) }
            })
        }
        settings.homeRowsJson = arr.toString()
    }

    /** Change one row and save. */
    fun updateRow(all: List<HomeRowSetting>, id: String, change: (HomeRowSetting) -> HomeRowSetting) =
        saveLayout(all.map { if (it.id == id) change(it) else it })

    /** Moves a row among the ones on Home (past any hidden ones in between). */
    fun moveShownRow(all: List<HomeRowSetting>, id: String, by: Int) {
        val shown = all.filter { it.on }
        val i = shown.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 1 || j < 1 || j >= shown.size) return
        val other = shown[j].id
        val m = all.toMutableList()
        val a = m.indexOfFirst { it.id == id }
        val b = m.indexOfFirst { it.id == other }
        val x = m[a]; m[a] = m[b]; m[b] = x
        saveLayout(m)
    }

    /** Puts a hidden row on Home: just after Continue watching, or after the last row shown. */
    fun showRow(all: List<HomeRowSetting>, id: String, atTop: Boolean) {
        val m = all.toMutableList()
        val i = m.indexOfFirst { it.id == id }
        if (i < 0) return
        val r = m.removeAt(i).copy(on = true)
        val at = if (atTop) 1 else (m.indexOfLast { it.on } + 1).coerceAtLeast(1)
        m.add(at.coerceAtMost(m.size), r)
        saveLayout(m)
    }

    fun resetLayout() {
        settings.homeRowsJson = ""
    }

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
                        name = it.optString("name").takeIf { n -> n.isNotBlank() },
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
