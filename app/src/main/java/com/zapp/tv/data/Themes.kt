package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Theme tunes from ThemerrDB, a free community database (used by Plex's Themerr) that links a TMDB
 * id to a YouTube video of the theme. Only collections use it for now.
 */
class Themes(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<Long, String>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    /** The YouTube id of a film collection's theme, or null if the database doesn't have one. */
    suspend fun collectionTheme(tmdbCollectionId: Long): String? = withContext(Dispatchers.IO) {
        cache[tmdbCollectionId]?.let { return@withContext it }
        if (tmdbCollectionId in missing) return@withContext null
        val url = "https://app.lizardbyte.dev/ThemerrDB/movie_collections/themoviedb/$tmdbCollectionId.json"
        val yt = try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optString("youtube_theme_url")
            }
        } catch (e: Exception) {
            null
        }
        val id = yt?.let { Regex("(?:v=|youtu\\.be/|embed/)([A-Za-z0-9_-]{11})").find(it)?.groupValues?.get(1) }
        if (id == null) missing.add(tmdbCollectionId) else cache[tmdbCollectionId] = id
        id
    }
}
