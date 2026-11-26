package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Someone in the cast, from TMDB. */
data class TmdbPerson(val id: Long, val name: String, val role: String?, val photo: String?)

/** A film or series from TMDB (for "More with …" and "People also watched"). */
data class TmdbItem(val id: Long, val type: String, val name: String, val poster: String?, val backdrop: String?, val year: String?)

data class TmdbReview(val author: String, val rating: Double?, val text: String)

data class TmdbDetails(
    val tmdbId: Long,
    val type: String,
    val tagline: String?,
    val certification: String?,
    val network: String?,
    val createdBy: List<String>,
    val writers: List<String>,
    val music: List<String>,
    val cast: List<TmdbPerson>,
    val reviews: List<TmdbReview>,
    val recommendations: List<TmdbItem>,
    /** The best official YouTube trailer (highest resolution, official, newest). */
    val trailerKey: String? = null,
    /** English title logo. */
    val logo: String? = null,
    /** A backdrop without text, in high resolution. */
    val backdrop: String? = null,
    /** The franchise this film belongs to (Harry Potter, Bond…), if any. */
    val collection: TmdbCollectionRef? = null,
)

data class TmdbCollectionRef(val id: Long, val name: String, val poster: String?, val backdrop: String?)

/** One film in a collection. */
data class TmdbCollectionPart(val item: TmdbItem, val overview: String?, val released: String?)

data class TmdbCollection(
    val id: Long,
    val name: String,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val parts: List<TmdbCollectionPart>,
)

/**
 * TMDB, with your own API key (added on the phone page): cast photos, credits, age ratings,
 * reviews, recommendations and other work by the cast. Everything else works without it.
 */
class Tmdb(private val http: OkHttpClient, private val settings: Settings) {
    private val details = ConcurrentHashMap<String, TmdbDetails?>()
    private val credits = ConcurrentHashMap<Long, List<TmdbItem>>()
    private val imdbIds = ConcurrentHashMap<String, String?>()

    val enabled: Boolean get() = settings.secret("tmdbKey").isNotBlank()

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val key = settings.secret("tmdbKey").ifBlank { return null }
        val b = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        // A long key is a "read access token"; a short one is the classic API key.
        val bearer = key.length > 40
        if (!bearer) b.addQueryParameter("api_key", key)
        val req = Request.Builder().url(b.build()).apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        return try {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun img(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** Checks a key by asking TMDB for its configuration. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) { get("configuration") != null }

    suspend fun details(imdbId: String, type: String): TmdbDetails? = withContext(Dispatchers.IO) {
        val cacheKey = "$type|$imdbId"
        if (details.containsKey(cacheKey)) return@withContext details[cacheKey]
        val imdb = imdbId.substringBefore(':')
        val found = get("find/$imdb", mapOf("external_source" to "imdb_id")) ?: return@withContext null
        val tv = type == "series"
        val id = (if (tv) found.optJSONArray("tv_results") else found.optJSONArray("movie_results"))?.optJSONObject(0)?.optLong("id")
            ?: run {
                details[cacheKey] = null
                return@withContext null
            }
        val kind = if (tv) "tv" else "movie"
        val o = get("$kind/$id", mapOf(
                "append_to_response" to "credits,reviews,recommendations,videos,images," + if (tv) "content_ratings" else "release_dates",
                "include_image_language" to "en,null",
                "include_video_language" to "en,null",
            )) ?: return@withContext null

        val cast = o.optJSONObject("credits")?.optJSONArray("cast").objects().take(16).map {
            TmdbPerson(it.optLong("id"), it.optString("name"), it.optString("character").takeIf { c -> c.isNotBlank() }, img(it.optString("profile_path"), "w185"))
        }
        val crew = o.optJSONObject("credits")?.optJSONArray("crew").objects()
        fun crewJob(vararg jobs: String) = crew.filter { it.optString("job") in jobs }.map { it.optString("name") }.distinct().take(3)
        val certification = if (tv) {
            o.optJSONObject("content_ratings")?.optJSONArray("results").objects().let { r ->
                (r.firstOrNull { it.optString("iso_3166_1") == "GB" } ?: r.firstOrNull { it.optString("iso_3166_1") == "US" })?.optString("rating")
            }
        } else {
            o.optJSONObject("release_dates")?.optJSONArray("results").objects().let { r ->
                (r.firstOrNull { it.optString("iso_3166_1") == "GB" } ?: r.firstOrNull { it.optString("iso_3166_1") == "US" })
                    ?.optJSONArray("release_dates").objects().map { it.optString("certification") }.firstOrNull { it.isNotBlank() }
            }
        }?.takeIf { it.isNotBlank() }
        val reviews = o.optJSONObject("reviews")?.optJSONArray("results").objects().take(8).map {
            TmdbReview(
                it.optString("author").ifBlank { "Someone" },
                it.optJSONObject("author_details")?.optDouble("rating")?.takeIf { r -> !r.isNaN() },
                it.optString("content").replace(Regex("\\s+"), " ").trim(),
            )
        }.filter { it.text.isNotBlank() }
        val recs = o.optJSONObject("recommendations")?.optJSONArray("results").objects().take(20).map { item(it, kind) }
        // Trailers: official YouTube trailers first, then the sharpest (TMDB records 2160 for 4K), then newest.
        val trailer = o.optJSONObject("videos")?.optJSONArray("results").objects()
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() && it.optString("type") in setOf("Trailer", "Teaser") }
            .sortedWith(
                compareByDescending<JSONObject> { it.optString("type") == "Trailer" }
                    .thenByDescending { it.optBoolean("official") }
                    .thenByDescending { it.optInt("size") }
                    .thenByDescending { it.optString("published_at") },
            ).firstOrNull()?.optString("key")
        val images = o.optJSONObject("images")
        val logo = images?.optJSONArray("logos").objects()
            .filter { it.optString("iso_639_1") == "en" }
            .maxByOrNull { it.optDouble("vote_average") }?.optString("file_path")
        val backdrop = images?.optJSONArray("backdrops").objects()
            .filter { it.isNull("iso_639_1") || it.optString("iso_639_1").isBlank() }
            .maxByOrNull { it.optDouble("vote_average") }?.optString("file_path")
            ?: o.optString("backdrop_path").takeIf { it.isNotBlank() }
        val d = TmdbDetails(
            tmdbId = id,
            type = kind,
            tagline = o.optString("tagline").takeIf { it.isNotBlank() },
            certification = certification,
            network = o.optJSONArray("networks").objects().firstOrNull()?.optString("name")
                ?: o.optJSONArray("production_companies").objects().firstOrNull()?.optString("name"),
            createdBy = o.optJSONArray("created_by").objects().map { it.optString("name") }.filter { it.isNotBlank() },
            writers = crewJob("Screenplay", "Writer", "Story"),
            music = crewJob("Original Music Composer", "Music"),
            cast = cast,
            reviews = reviews,
            recommendations = recs,
            trailerKey = trailer,
            collection = o.optJSONObject("belongs_to_collection")?.let { c ->
                TmdbCollectionRef(
                    c.optLong("id"),
                    c.optString("name").removeSuffix(" Collection").removeSuffix(" collection"),
                    img(c.optString("poster_path"), "w342"),
                    img(c.optString("backdrop_path"), "w1280"),
                )
            },
            logo = img(logo, "w500"),
            backdrop = img(backdrop, "w1280"),
        )
        details[cacheKey] = d
        d
    }

    private fun item(it: JSONObject, fallbackKind: String): TmdbItem {
        val kind = it.optString("media_type").ifBlank { fallbackKind }
        val date = it.optString("release_date").ifBlank { it.optString("first_air_date") }
        return TmdbItem(
            id = it.optLong("id"),
            type = if (kind == "tv") "series" else "movie",
            name = it.optString("title").ifBlank { it.optString("name") },
            poster = img(it.optString("poster_path"), "w342"),
            backdrop = img(it.optString("backdrop_path"), "w780"),
            year = date.take(4).takeIf { y -> y.length == 4 },
        )
    }

    private val seasonRatingsCache = ConcurrentHashMap<String, Map<Int, Double>>()

    /** Each episode's TMDB rating (out of 10) in one season, by episode number. */
    suspend fun seasonRatings(tvId: Long, season: Int): Map<Int, Double> = withContext(Dispatchers.IO) {
        val k = "$tvId|$season"
        seasonRatingsCache[k]?.let { return@withContext it }
        val o = get("tv/$tvId/season/$season") ?: return@withContext emptyMap()
        val m = o.optJSONArray("episodes").objects()
            .filter { it.optInt("vote_count") >= 3 && it.optDouble("vote_average") > 0 }
            .associate { it.optInt("episode_number") to it.optDouble("vote_average") }
        seasonRatingsCache[k] = m
        m
    }

    /** People whose name matches (actors and directors), most popular first. */
    suspend fun searchPeople(query: String): List<TmdbPerson> = withContext(Dispatchers.IO) {
        if (query.length < 3) return@withContext emptyList()
        val o = get("search/person", mapOf("query" to query)) ?: return@withContext emptyList()
        o.optJSONArray("results").objects()
            .filter { it.optString("profile_path").isNotBlank() && it.optDouble("popularity") > 1.0 }
            .sortedByDescending { it.optDouble("popularity") }
            .take(8)
            .map { TmdbPerson(it.optLong("id"), it.optString("name"), it.optString("known_for_department").takeIf { d -> d.isNotBlank() }, img(it.optString("profile_path"), "w185")) }
    }

    /** Films and series someone was in, most popular first. */
    suspend fun personWork(personId: Long, exceptTmdbId: Long): List<TmdbItem> = withContext(Dispatchers.IO) {
        credits[personId]?.let { return@withContext it }
        val o = get("person/$personId/combined_credits") ?: return@withContext emptyList()
        val list = o.optJSONArray("cast").objects()
            .filter { (it.optString("media_type") == "movie" || it.optString("media_type") == "tv") && it.optLong("id") != exceptTmdbId && it.optString("poster_path").isNotBlank() }
            .sortedByDescending { it.optDouble("popularity") }
            .distinctBy { it.optLong("id") }
            .take(15)
            .map { item(it, "movie") }
        credits[personId] = list
        list
    }

    private val collections = ConcurrentHashMap<Long, TmdbCollection>()

    /** A franchise's films, in release order. */
    suspend fun collection(id: Long): TmdbCollection? = withContext(Dispatchers.IO) {
        collections[id]?.let { return@withContext it }
        val o = get("collection/$id") ?: return@withContext null
        val parts = o.optJSONArray("parts").objects()
            .filter { it.optString("release_date").isNotBlank() }
            .sortedBy { it.optString("release_date") }
            .map { p ->
                TmdbCollectionPart(
                    item(p, "movie").copy(poster = img(p.optString("poster_path"), "w500"), backdrop = img(p.optString("backdrop_path"), "w1280")),
                    p.optString("overview").takeIf { it.isNotBlank() },
                    p.optString("release_date").takeIf { it.isNotBlank() },
                )
            }
        val c = TmdbCollection(
            id = id,
            name = o.optString("name").removeSuffix(" Collection").removeSuffix(" collection"),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            poster = img(o.optString("poster_path"), "w500"),
            backdrop = img(o.optString("backdrop_path"), "original"),
            parts = parts,
        )
        collections[id] = c
        c
    }

    /** The IMDb id for a TMDB title, so it can open in Zapp. */
    suspend fun imdbId(item: TmdbItem): String? = withContext(Dispatchers.IO) {
        val key = "${item.type}|${item.id}"
        if (imdbIds.containsKey(key)) return@withContext imdbIds[key]
        val o = get("${if (item.type == "series") "tv" else "movie"}/${item.id}/external_ids")
        val id = o?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        imdbIds[key] = id
        id
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
