package com.zapp.tv.addons

import kotlinx.coroutines.flow.flatMapLatest
import com.zapp.tv.data.Addon
import com.zapp.tv.data.AppDatabase
import com.zapp.tv.data.LibraryItem
import com.zapp.tv.data.Secrets
import com.zapp.tv.data.WatchProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** An installed add-on with its parsed manifest and plain base URL. */
data class InstalledAddon(val addon: Addon, val manifest: Manifest, val base: String)

/** A catalogue row: which add-on, which catalogue. */
data class CatalogRef(val addon: InstalledAddon, val def: CatalogDef, val genre: String? = null) {
    /** Stable id used by the Home layout settings (a genre filter adds to it). */
    val key: String get() = "catalog:${addon.addon.addonId}:${def.type}:${def.id}" + (genre?.let { ":genre=$it" } ?: "")

    /** Genres this catalogue can be filtered by. */
    val genres: List<String> get() = def.extras.firstOrNull { it.name == "genre" }?.options.orEmpty()
    val title: String
        get() = when (def.type) {
            "movie" -> "${def.name} (films)"
            "series" -> "${def.name} (series)"
            else -> def.name
        }
}

/** Streams from one add-on, delivered as soon as that add-on answers. */
data class StreamBatch(val addonName: String, val streams: List<StreamItem>, val error: String?)

class MediaRepository(private val db: AppDatabase, http: OkHttpClient, private val settings: com.zapp.tv.data.Settings) {

    val client = AddonClient(http)
    val nuvio = NuvioImport(http)
    private val dao = db.mediaDao()
    private val catalogCache = ConcurrentHashMap<String, Pair<Long, List<MetaPreview>>>()
    private val metaCache = ConcurrentHashMap<String, MetaDetail>()

    val addons: Flow<List<InstalledAddon>> = dao.observeAddons().map { list -> list.mapNotNull { installed(it) } }

    private fun installed(a: Addon): InstalledAddon? = try {
        val url = Secrets.decrypt(a.manifestUrl).orEmpty()
        InstalledAddon(a, client.parseManifest(JSONObject(a.manifestJson)), client.baseUrl(url))
    } catch (e: Exception) {
        null
    }

    suspend fun installedAddons(): List<InstalledAddon> = dao.addons().mapNotNull { installed(it) }

    suspend fun enabledAddons(): List<InstalledAddon> = installedAddons().filter { it.addon.enabled }

    // ------------------------------------------------------------------ managing add-ons

    suspend fun addAddon(input: String): InstalledAddon {
        val url = client.normalizeManifestUrl(input)
        val existing = installedAddons()
        if (existing.any { Secrets.decrypt(it.addon.manifestUrl) == url }) error("That add-on is already installed.")
        val (manifest, raw) = client.manifest(url)
        if (manifest.configurationRequired) error("${manifest.name} needs to be set up on its website first. Configure it there, then copy the install link it gives you.")
        val addon = Addon(
            manifestUrl = Secrets.encrypt(url) ?: url,
            addonId = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            logo = manifest.logo,
            manifestJson = raw,
            enabled = true,
            sortOrder = (existing.maxOfOrNull { it.addon.sortOrder } ?: 0) + 1,
            addedAt = System.currentTimeMillis(),
        )
        val id = dao.insertAddon(addon)
        catalogCache.clear()
        return InstalledAddon(addon.copy(id = id), manifest, client.baseUrl(url))
    }

    suspend fun removeAddon(id: Long) {
        dao.deleteAddon(id)
        catalogCache.clear()
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val a = dao.addons().firstOrNull { it.id == id } ?: return
        dao.updateAddon(a.copy(enabled = enabled))
        catalogCache.clear()
    }

    /** Moves an add-on one place up or down; order decides which add-on's details and streams come first. */
    suspend fun move(id: Long, up: Boolean) {
        val list = dao.addons().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        dao.updateAddons(list.mapIndexed { index, a -> a.copy(sortOrder = index) })
    }

    /** Re-reads every manifest, so new catalogues and settings from add-on authors show up. */
    suspend fun refreshManifests() {
        for (a in dao.addons()) {
            try {
                val url = Secrets.decrypt(a.manifestUrl).orEmpty()
                val (m, raw) = client.manifest(url)
                dao.updateAddon(a.copy(name = m.name, version = m.version, description = m.description, logo = m.logo, manifestJson = raw))
            } catch (e: Exception) {
                // Keep the old manifest if the add-on is unreachable right now.
            }
        }
        catalogCache.clear()
    }

    // ------------------------------------------------------------------ browsing

    suspend fun catalogs(type: String? = null): List<CatalogRef> =
        enabledAddons().flatMap { a ->
            a.manifest.catalogs
                .filter { it.browsable && (type == null || it.type == type) && (it.type == "movie" || it.type == "series" || type != null) }
                .map { CatalogRef(a, it) }
        }

    /** One page of a catalogue; pages are cached for 30 minutes. */
    suspend fun catalogPage(ref: CatalogRef, skip: Int): List<MetaPreview> {
        val key = "${ref.key}|$skip"
        catalogCache[key]?.let { (time, items) -> if (System.currentTimeMillis() - time < 30 * 60_000L) return items }
        val extra = buildMap {
            ref.genre?.let { put("genre", it) }
            if (skip > 0) put("skip", skip.toString())
        }
        val items = ref.addon.let { client.catalog(it.base, ref.def, extra) }
        catalogCache[key] = System.currentTimeMillis() to items
        return items
    }

    /** Searches every searchable film and series catalogue at once. */
    suspend fun search(query: String): List<Pair<CatalogRef, List<MetaPreview>>> = coroutineScope {
        val refs = enabledAddons().flatMap { a ->
            a.manifest.catalogs
                .filter { it.searchable && (it.type == "movie" || it.type == "series") && it.extras.none { e -> e.isRequired && e.name != "search" } }
                .map { CatalogRef(a, it) }
        }
        refs.map { ref ->
            async {
                val items = withTimeoutOrNull(15_000) {
                    try {
                        client.catalog(ref.addon.base, ref.def, mapOf("search" to query))
                    } catch (e: Exception) {
                        null
                    }
                }
                if (items.isNullOrEmpty()) null else ref to items
            }
        }.awaitAll().filterNotNull()
    }

    /** Full details from the first add-on (in your order) that knows this title. */
    suspend fun meta(type: String, id: String): MetaDetail? {
        metaCache["$type|$id"]?.let { return it }
        for (a in enabledAddons()) {
            if (!a.manifest.supports("meta", type, id)) continue
            val m = withTimeoutOrNull(15_000) {
                try {
                    client.meta(a.base, type, id)
                } catch (e: Exception) {
                    null
                }
            }
            if (m != null) {
                metaCache["$type|$id"] = m
                return m
            }
        }
        return null
    }

    /** Asks every add-on that offers streams for this title; each answer arrives as its own batch. */
    /**
     * Subtitles from every subtitles add-on, limited to the languages you want (your subtitle
     * language and English), a few of each.
     */
    suspend fun subtitles(type: String, videoId: String, languages: Set<String>): List<ExternalSubtitle> = coroutineScope {
        val sources = enabledAddons().filter { it.manifest.supports("subtitles", type, videoId) }
        sources.map { a ->
            async {
                withTimeoutOrNull(8_000) {
                    try {
                        client.subtitles(a.base, a.manifest.name, type, videoId)
                    } catch (e: Exception) {
                        null
                    }
                }.orEmpty()
            }
        }.awaitAll().flatten()
            .filter { s -> normalizeLang(s.lang) in languages }
            .groupBy { normalizeLang(it.lang) }
            .flatMap { (_, list) -> list.distinctBy { it.url }.take(4) }
    }

    fun streams(type: String, videoId: String): Flow<StreamBatch> = channelFlow {
        val sources = enabledAddons().filter { it.manifest.supports("stream", type, videoId) }
        if (sources.isEmpty()) {
            send(StreamBatch("", emptyList(), "None of your add-ons provide sources for this title."))
        }
        for (a in sources) {
            launch {
                val result = withTimeoutOrNull(25_000) {
                    try {
                        StreamBatch(a.manifest.name, client.streams(a.base, a.manifest.name, type, videoId), null)
                    } catch (e: Exception) {
                        StreamBatch(a.manifest.name, emptyList(), e.message ?: "Didn't answer")
                    }
                } ?: StreamBatch(a.manifest.name, emptyList(), "Took too long to answer")
                send(result)
            }
        }
        awaitClose()
    }

    // ------------------------------------------------------------------ progress and library

    private val profile: Long get() = settings.profileId

    /** Re-runs [query] whenever the profile changes. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> perProfile(query: (Long) -> Flow<T>): Flow<T> =
        androidx.compose.runtime.snapshotFlow { settings.profileId }.flatMapLatest { query(it) }

    suspend fun progress(videoId: String): WatchProgress? = dao.progress(profile, videoId)

    fun progressFor(metaId: String): Flow<List<WatchProgress>> = perProfile { dao.observeProgressFor(it, metaId) }

    fun continueWatching(limit: Int = 30): Flow<List<WatchProgress>> = perProfile { dao.observeContinueWatching(it, limit) }

    suspend fun saveProgress(p: WatchProgress) {
        val pid = profile
        val existing = dao.progress(pid, p.videoId)
        dao.saveProgress(p.copy(id = existing?.id ?: 0, profileId = pid))
    }

    suspend fun hideFromContinue(metaId: String) = dao.hideFromContinue(profile, metaId)

    val watchedTitles: Flow<Set<String>> = perProfile { dao.observeWatchedTitles(it) }.map { it.toSet() }

    val library: Flow<List<LibraryItem>> = perProfile { dao.observeLibrary(it) }

    fun inLibrary(metaId: String): Flow<Boolean> = perProfile { dao.observeInLibrary(it, metaId) }

    suspend fun toggleLibrary(meta: MetaPreview, currentlyIn: Boolean) {
        if (currentlyIn) {
            dao.removeFromLibrary(profile, meta.id)
        } else {
            dao.addToLibrary(LibraryItem(profileId = profile, metaId = meta.id, type = meta.type, name = meta.name, poster = meta.poster, addedAt = System.currentTimeMillis()))
        }
    }
}

/** "eng", "en", "en-GB" and "English" all become "en" (and so on for other languages). */
fun normalizeLang(code: String): String {
    val c = code.trim().lowercase()
    if (c.isEmpty()) return ""
    val two = c.substringBefore('-').substringBefore('_')
    if (two.length == 2) return two
    return try {
        java.util.Locale.getAvailableLocales().firstOrNull { l ->
            try { l.isO3Language == two } catch (e: Exception) { false }
        }?.language ?: when (two) {
            "fre" -> "fr"; "ger" -> "de"; "dut" -> "nl"; "gre" -> "el"; "chi" -> "zh"; "per" -> "fa"; "rum" -> "ro"; "cze" -> "cs"
            "english" -> "en"; "spanish" -> "es"; "french" -> "fr"; "german" -> "de"; "italian" -> "it"; "portuguese" -> "pt"; "arabic" -> "ar"; "turkish" -> "tr"
            else -> two
        }
    } catch (e: Exception) {
        two
    }
}
