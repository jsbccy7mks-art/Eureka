package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One score: where it's from ("IMDb", "RT", …) and how it reads ("7.8", "92%"). */
data class RatingBadge(val source: String, val value: String, val good: Boolean? = null)

/**
 * Ratings from MDBList (your own free key, added on the phone page): IMDb, Rotten Tomatoes
 * critics and audience, Metacritic and Letterboxd, whichever exist. The key is stored encrypted and
 * only ever put on the request itself, never logged.
 */
class MdbList(private val http: OkHttpClient, private val settings: Settings, private val tmdb: Tmdb) {
    private val cache = ConcurrentHashMap<String, List<RatingBadge>>()

    val enabled: Boolean get() = settings.secret("mdblistKey").isNotBlank()

    private fun fetch(type: String, tmdbId: Long): JSONObject? {
        val key = settings.secret("mdblistKey").ifBlank { return null }
        val url = "https://api.mdblist.com/tmdb/$type/$tmdbId".toHttpUrl().newBuilder().addQueryParameter("apikey", key).build()
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Checks a key with a well-known film. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) { fetch("movie", 550) != null }

    suspend fun ratings(imdbId: String, type: String): List<RatingBadge> = withContext(Dispatchers.IO) {
        if (!enabled || !imdbId.startsWith("tt")) return@withContext emptyList()
        val cacheKey = "$type|$imdbId"
        cache[cacheKey]?.let { return@withContext it }
        val tmdbId = runCatching { tmdb.details(imdbId, type)?.tmdbId }.getOrNull() ?: return@withContext emptyList()
        val o = fetch(if (type == "series") "show" else "movie", tmdbId) ?: return@withContext emptyList()
        val arr = o.optJSONArray("ratings")
        val by = HashMap<String, Double>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val v = r.optDouble("value").takeIf { !it.isNaN() && it > 0 } ?: continue
                by[r.optString("source").lowercase()] = v
            }
        }
        val out = ArrayList<RatingBadge>()
        by["imdb"]?.let { out.add(RatingBadge("IMDb", "%.1f".format(java.util.Locale.UK, it))) }
        by["tomatoes"]?.let { out.add(RatingBadge("RT", "${it.toInt()}%", it >= 60)) }
        by["popcorn"]?.let { out.add(RatingBadge("Audience", "${it.toInt()}%", it >= 60)) }
        by["metacritic"]?.let { out.add(RatingBadge("Metacritic", it.toInt().toString(), it >= 61)) }
        by["letterboxd"]?.let { out.add(RatingBadge("Letterboxd", "%.1f".format(java.util.Locale.UK, it))) }
        cache[cacheKey] = out
        out
    }
}
