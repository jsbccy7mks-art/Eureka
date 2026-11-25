package com.zapp.tv.addons

import android.net.Uri
import com.zapp.tv.data.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks the Stremio add-on protocol: every request is a GET of a JSON file under the add-on's base URL.
 * Add-ons differ a lot, so every field is treated as optional.
 */
class AddonClient(private val http: OkHttpClient) {

    /** Accepts a manifest link in any of the forms people paste. */
    fun normalizeManifestUrl(input: String): String {
        var s = input.trim()
        if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        s = s.substringBefore('#')
        if (!s.substringBefore('?').endsWith("manifest.json")) s = s.trimEnd('/') + "/manifest.json"
        return s
    }

    fun baseUrl(manifestUrl: String): String = manifestUrl.substringBefore('?').removeSuffix("/manifest.json")

    suspend fun manifest(manifestUrl: String): Pair<Manifest, String> = withContext(Dispatchers.IO) {
        val text = get(manifestUrl)
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            error("That link didn't return an add-on manifest.")
        }
        parseManifest(json) to text
    }

    fun parseManifest(json: JSONObject): Manifest {
        val resources = ArrayList<ResourceDef>()
        json.optJSONArray("resources")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val r = arr.opt(i)) {
                    is String -> resources.add(ResourceDef(r, null, null))
                    is JSONObject -> resources.add(
                        ResourceDef(r.optString("name"), r.optJSONArray("types")?.strings(), r.optJSONArray("idPrefixes")?.strings()),
                    )
                }
            }
        }
        val catalogs = ArrayList<CatalogDef>()
        json.optJSONArray("catalogs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val extras = ArrayList<ExtraDef>()
                c.optJSONArray("extra")?.let { ex ->
                    for (j in 0 until ex.length()) {
                        val e = ex.optJSONObject(j) ?: continue
                        extras.add(ExtraDef(e.optString("name"), e.optBoolean("isRequired"), e.optJSONArray("options")?.strings().orEmpty()))
                    }
                }
                // Older manifests list extras by name only.
                val required = c.optJSONArray("extraRequired")?.strings().orEmpty()
                c.optJSONArray("extraSupported")?.strings()?.forEach { name ->
                    if (extras.none { it.name == name }) extras.add(ExtraDef(name, name in required, emptyList()))
                }
                catalogs.add(CatalogDef(c.optString("type"), c.optString("id"), c.optString("name").ifBlank { c.optString("id") }, extras))
            }
        }
        if (resources.isEmpty() && catalogs.isNotEmpty()) resources.add(ResourceDef("catalog", null, null))
        val id = json.optString("id")
        if (id.isBlank()) error("That link didn't return an add-on manifest.")
        return Manifest(
            id = id,
            name = json.optString("name").ifBlank { id },
            version = json.optString("version"),
            description = json.optString("description").takeIf { it.isNotBlank() },
            logo = json.optString("logo").takeIf { it.startsWith("http") },
            types = json.optJSONArray("types")?.strings().orEmpty(),
            idPrefixes = json.optJSONArray("idPrefixes")?.strings(),
            resources = resources,
            catalogs = catalogs,
            configurationRequired = json.optJSONObject("behaviorHints")?.optBoolean("configurationRequired") == true,
        )
    }

    suspend fun catalog(base: String, def: CatalogDef, extra: Map<String, String>): List<MetaPreview> = withContext(Dispatchers.IO) {
        val extraPath = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
        val text = get("$base/catalog/${seg(def.type)}/${seg(def.id)}$extraPath.json")
        val arr = JSONObject(text).optJSONArray("metas") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parsePreview(o, def.type) } }
    }

    suspend fun meta(base: String, type: String, id: String): MetaDetail? = withContext(Dispatchers.IO) {
        val text = get("$base/meta/${seg(type)}/${seg(id)}.json")
        val o = JSONObject(text).optJSONObject("meta") ?: return@withContext null
        val preview = parsePreview(o, type) ?: return@withContext null
        val cast = o.optJSONArray("cast")?.strings()?.toMutableList() ?: ArrayList()
        val directors = o.optJSONArray("director")?.strings()?.toMutableList() ?: ArrayList()
        // Newer add-ons describe people as links.
        o.optJSONArray("links")?.let { links ->
            for (i in 0 until links.length()) {
                val l = links.optJSONObject(i) ?: continue
                when (l.optString("category").lowercase()) {
                    "cast" -> if (cast.size < 20) cast.add(l.optString("name"))
                    "directors", "director" -> directors.add(l.optString("name"))
                }
            }
        }
        val videos = ArrayList<Video>()
        o.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val vid = v.optString("id")
                if (vid.isBlank()) continue
                videos.add(
                    Video(
                        id = vid,
                        title = v.optString("title").ifBlank { v.optString("name") },
                        season = v.optIntOrNull("season"),
                        episode = v.optIntOrNull("episode") ?: v.optIntOrNull("number"),
                        released = v.optString("released").takeIf { it.isNotBlank() },
                        thumbnail = v.optString("thumbnail").takeIf { it.startsWith("http") },
                        overview = v.optString("overview").ifBlank { v.optString("description") }.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        videos.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        val trailer = o.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId")
            ?: o.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")
        MetaDetail(
            preview = preview,
            runtime = o.optString("runtime").takeIf { it.isNotBlank() },
            cast = cast.filter { it.isNotBlank() }.distinct(),
            directors = directors.filter { it.isNotBlank() }.distinct(),
            videos = videos,
            trailerYoutube = trailer?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun subtitles(base: String, addonName: String, type: String, id: String): List<ExternalSubtitle> = withContext(Dispatchers.IO) {
        val text = get("$base/subtitles/${seg(type)}/${seg(id)}.json")
        val arr = JSONObject(text).optJSONArray("subtitles") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            ExternalSubtitle(url, o.optString("lang"), addonName)
        }
    }

    suspend fun streams(base: String, addonName: String, type: String, id: String): List<StreamItem> = withContext(Dispatchers.IO) {
        val text = get("$base/stream/${seg(type)}/${seg(id)}.json")
        val arr = JSONObject(text).optJSONArray("streams") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val hints = s.optJSONObject("behaviorHints")
            val headers = HashMap<String, String>()
            hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                h.keys().forEach { k -> headers[k] = h.optString(k) }
            }
            StreamItem(
                addonName = addonName,
                url = s.optString("url").takeIf { it.startsWith("http") },
                infoHash = s.optString("infoHash").takeIf { it.isNotBlank() },
                fileIdx = s.optIntOrNull("fileIdx"),
                externalUrl = s.optString("externalUrl").takeIf { it.isNotBlank() },
                ytId = s.optString("ytId").takeIf { it.isNotBlank() },
                name = s.optString("name").ifBlank { addonName },
                description = s.optString("description").ifBlank { s.optString("title") },
                headers = headers,
                filename = hints?.optString("filename")?.takeIf { it.isNotBlank() },
                bingeGroup = hints?.optString("bingeGroup")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun parsePreview(o: JSONObject, fallbackType: String): MetaPreview? {
        val id = o.optString("id").ifBlank { o.optString("imdb_id") }
        val name = o.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return MetaPreview(
            id = id,
            type = o.optString("type").ifBlank { fallbackType },
            name = name,
            poster = o.optString("poster").takeIf { it.startsWith("http") },
            posterShape = o.optString("posterShape").ifBlank { "poster" },
            background = o.optString("background").takeIf { it.startsWith("http") },
            logo = o.optString("logo").takeIf { it.startsWith("http") },
            description = o.optString("description").takeIf { it.isNotBlank() },
            releaseInfo = o.optString("releaseInfo").ifBlank { o.optString("year") }.takeIf { it.isNotBlank() },
            imdbRating = o.optString("imdbRating").takeIf { it.isNotBlank() && it != "null" },
            genres = o.optJSONArray("genres")?.strings() ?: o.optJSONArray("genre")?.strings().orEmpty(),
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("The add-on answered with HTTP ${r.code}.")
            return r.body?.string() ?: error("The add-on sent an empty answer.")
        }
    }

    /** Keeps the ":" in ids like tt123:1:2 readable, as add-ons expect. */
    private fun seg(s: String): String = Uri.encode(s, ":")

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
}
