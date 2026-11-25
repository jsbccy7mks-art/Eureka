package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A part of an episode or film, in milliseconds. */
data class Segment(val startMs: Long, val endMs: Long) {
    operator fun contains(ms: Long) = ms in startMs until endMs
}

data class Segments(val intro: Segment?, val recap: Segment?, val outro: Segment?, val postCredits: Segment?)

/**
 * IntroDB (introdb.app): community timings of intros, recaps and end credits, read without a key.
 * Works for titles with IMDb ids ("tt…", or "tt…:season:episode" for episodes).
 */
class IntroDb(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, Segments?>()

    suspend fun segments(videoId: String, type: String): Segments? = withContext(Dispatchers.IO) {
        if (cache.containsKey(videoId)) return@withContext cache[videoId]
        val parts = videoId.split(':')
        val imdb = parts.firstOrNull()?.takeIf { Regex("^tt\\d{7,8}$").matches(it) } ?: return@withContext null
        val url = "https://api.introdb.app/segments".toHttpUrl().newBuilder().addQueryParameter("imdb_id", imdb).apply {
            if (type == "movie" || parts.size < 3) {
                addQueryParameter("is_movie", "true")
            } else {
                addQueryParameter("season", parts[parts.size - 2])
                addQueryParameter("episode", parts.last())
            }
        }.build()
        val result = try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "ZappTV").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty())
                fun seg(name: String): Segment? = o.optJSONObject(name)?.let { s ->
                    val a = s.optLong("start_ms", -1)
                    val b = s.optLong("end_ms", -1)
                    if (a >= 0 && b > a) Segment(a, b) else null
                }
                Segments(seg("intro"), seg("recap"), seg("outro"), seg("post_credits"))
            }
        } catch (e: Exception) {
            null
        }
        cache[videoId] = result
        result
    }
}
