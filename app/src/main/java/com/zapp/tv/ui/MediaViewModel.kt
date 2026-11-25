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
                ),
            )
        }
    }

    fun hideFromContinue(metaId: String) = viewModelScope.launch { repo.hideFromContinue(metaId) }

    fun inLibrary(metaId: String): Flow<Boolean> = repo.inLibrary(metaId)

    fun toggleLibrary(meta: MetaPreview, currentlyIn: Boolean) = viewModelScope.launch { repo.toggleLibrary(meta, currentlyIn) }

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
