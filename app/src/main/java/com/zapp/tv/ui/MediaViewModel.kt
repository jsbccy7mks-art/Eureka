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
import kotlinx.coroutines.awaitAll
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

    fun syncNuvio(quiet: Boolean = false) {
        val pid = settings.profileId
        if (nuvioSyncing || !nuvio.signedIn(pid) || nuvio.linkedProfile(pid) == 0) return
        nuvioSyncing = true
        if (!quiet) nuvioSyncStatus = "Syncing with Nuvio…"
        viewModelScope.launch {
            try {
                val s = nuvio.sync(pid)
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
                .getOrNull()?.let { (_, url) -> readyLinks[k] = url to System.currentTimeMillis() }
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

    // ---- franchise collections (TMDB)

    /** A franchise you have at least one film of (saved or watched). */
    data class CollectionSummary(val id: Long, val name: String, val poster: String?, val backdrop: String?, val yours: Int)

    var collections by mutableStateOf<List<CollectionSummary>>(emptyList())
        private set
    private var collectionsJob: kotlinx.coroutines.Job? = null

    /** Works out your collections from the films in your library and the ones you've watched. */
    fun refreshCollections() {
        if (!tmdb.enabled) return
        collectionsJob?.cancel()
        collectionsJob = viewModelScope.launch {
            delay(1_500)
            val films = (library.value.filter { it.type == "movie" }.map { it.metaId } + watchedTitles.value)
                .filter { it.startsWith("tt") }.distinct().take(120)
            val found = LinkedHashMap<Long, CollectionSummary>()
            for (id in films) {
                val c = runCatching { tmdb.details(id, "movie")?.collection }.getOrNull() ?: continue
                val prev = found[c.id]
                found[c.id] = CollectionSummary(c.id, c.name, c.poster, c.backdrop, (prev?.yours ?: 0) + 1)
            }
            collections = found.values.sortedByDescending { it.yours }
        }
    }

    suspend fun collection(id: Long): com.zapp.tv.data.TmdbCollection? = runCatching { tmdb.collection(id) }.getOrNull()

    /** IMDb ids for a collection's films (to open them, and to tick the watched ones). */
    suspend fun collectionImdbIds(c: com.zapp.tv.data.TmdbCollection): Map<Long, String> = kotlinx.coroutines.coroutineScope {
        c.parts.map { p -> async { p.item.id to runCatching { tmdb.imdbId(p.item) }.getOrNull() } }
            .awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    suspend fun collectionTheme(id: Long): String? = if (settings.themeMusic) runCatching { zapp.themes.collectionTheme(id) }.getOrNull() else null

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

    // (After watchedTitles, which it needs.) Collections follow your library and what you've watched.
    init {
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
        catalogs.forEach { available[it.key] = it.title }
        available[LIBRARY_ROW] = "Your library"
        available[COLLECTIONS_ROW] = "Your collections"

        val saved = parseLayout(settings.homeRowsJson)
        val result = ArrayList<HomeRowSetting>()
        for (r in saved) {
            val title = available[r.id] ?: continue
            result.add(r.copy(title = title))
        }
        for ((id, title) in available) {
            if (result.none { it.id == id }) {
                // Collections start switched off on Home (they live in Library); turn them on in Home layout.
                val row = HomeRowSetting(id, title, id != COLLECTIONS_ROW, landscape = id == CONTINUE_ROW || id == COLLECTIONS_ROW)
                // New smart rows go just below Continue watching; everything else at the end.
                if (id == BECAUSE_ROW || id == NEW_EPISODES_ROW) result.add(result.indexOfFirst { it.id == CONTINUE_ROW } + 1, row) else result.add(row)
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
