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
data class TmdbItem(
    val id: Long,
    val type: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val year: String?,
    val overview: String? = null,
    val rating: Double? = null,
    val genreIds: List<Int> = emptyList(),
)

/** TMDB's genre names, films and series together (their ids don't clash). */
val TMDB_GENRES: Map<Int, String> = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
    18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
    9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller",
    10752 to "War", 37 to "Western", 10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News",
    10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
)

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
    val genres: List<Pair<Int, String>> = emptyList(),
    val directors: List<String> = emptyList(),
)

/** Episodes aired so far ([total]) and each season's episode count. */
data class AiredEpisodes(val total: Int, val seasons: Map<Int, Int>)

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
        // A few requests at a time (Home asks for well over a hundred at once), and a short wait and
        // another go when TMDB says "too many" or has a hiccup, instead of giving up on the title.
        for (attempt in 0..2) {
            var waitMs = 0L
            gate.acquire()
            try {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return JSONObject(r.body?.string().orEmpty())
                    if (r.code != 429 && r.code < 500) return null // a real "no" (not found, bad key)
                    waitMs = (r.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 5) ?: (attempt + 1L)) * 1000L
                }
            } catch (e: Exception) {
                waitMs = (attempt + 1L) * 700L
            } finally {
                gate.release()
            }
            if (attempt < 2) Thread.sleep(waitMs)
        }
        return null
    }

    private val gate = java.util.concurrent.Semaphore(8, true)

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
            genres = o.optJSONArray("genres").objects().map { it.optInt("id") to it.optString("name") },
            directors = crewJob("Director"),
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
        val g = it.optJSONArray("genre_ids")
        return TmdbItem(
            id = it.optLong("id"),
            type = if (kind == "tv") "series" else "movie",
            name = it.optString("title").ifBlank { it.optString("name") },
            poster = img(it.optString("poster_path"), "w342"),
            backdrop = img(it.optString("backdrop_path"), "w780"),
            year = date.take(4).takeIf { y -> y.length == 4 },
            overview = it.optString("overview").takeIf { o -> o.isNotBlank() },
            rating = it.optDouble("vote_average").takeIf { r -> !r.isNaN() && r > 0 },
            genreIds = if (g == null) emptyList() else (0 until g.length()).map { i -> g.optInt(i) },
        )
    }

    /** Popular films right now (for "Rate a few"). */
    suspend fun popular(): List<TmdbItem> = withContext(Dispatchers.IO) {
        get("trending/movie/week")?.optJSONArray("results").objects().filter { it.optString("poster_path").isNotBlank() }.map { item(it, "movie") }
    }

    /**
     * Titles from any TMDB list (trending, now playing, discover…): [kind] "movie" or "tv" (lists
     * that mix them say which each one is).
     */
    suspend fun titles(path: String, params: Map<String, String>, kind: String, page: Int = 1): List<TmdbItem> = withContext(Dispatchers.IO) {
        get(path, params + ("page" to page.toString()))?.optJSONArray("results").objects()
            .filter { it.optString("poster_path").isNotBlank() }
            .map { item(it, kind) }
    }

    /** Well rated but less famous titles in these genres ("Hidden gems for you"). */
    suspend fun gems(genreIds: List<Int>, tv: Boolean = false): List<TmdbItem> = withContext(Dispatchers.IO) {
        if (genreIds.isEmpty()) return@withContext emptyList()
        val kind = if (tv) "tv" else "movie"
        get(
            "discover/$kind",
            mapOf(
                "with_genres" to genreIds.take(2).joinToString("|"),
                "vote_average.gte" to "7.2",
                "vote_count.gte" to "250",
                "vote_count.lte" to "4000",
                "sort_by" to "vote_average.desc",
            ),
        )?.optJSONArray("results").objects().filter { it.optString("poster_path").isNotBlank() }.map { item(it, kind) }
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


    // ------------------------------------------------------------ the collections catalogue

    /** Well-known films (TMDB ids, with their genre ids), by [sort], [pages] pages of 20. */
    suspend fun discoverFilms(params: Map<String, String>, pages: Int): List<Pair<Long, List<Int>>> = withContext(Dispatchers.IO) {
        (1..pages).flatMap { p ->
            get("discover/movie", params + ("page" to p.toString()))?.optJSONArray("results").objects().map { o ->
                val g = o.optJSONArray("genre_ids")
                o.optLong("id") to (if (g == null) emptyList() else (0 until g.length()).map { g.optInt(it) })
            }
        }.distinctBy { it.first }
    }

    /** The franchise a film (by TMDB id) belongs to, if any. */
    suspend fun filmCollection(tmdbId: Long): TmdbCollectionRef? = withContext(Dispatchers.IO) {
        val c = get("movie/$tmdbId")?.optJSONObject("belongs_to_collection") ?: return@withContext null
        TmdbCollectionRef(
            c.optLong("id"),
            c.optString("name").removeSuffix(" Collection").removeSuffix(" collection"),
            img(c.optString("poster_path"), "w500"),
            img(c.optString("backdrop_path"), "w780"),
        )
    }

    /**
     * A collection made from a search of TMDB (a studio, a decade, a theme) rather than a franchise:
     * its best-known films, shown in release order.
     */
    suspend fun discoverCollection(id: Long, name: String, overview: String?, params: Map<String, String>, tv: Boolean = false, byDate: Boolean = true): TmdbCollection? = withContext(Dispatchers.IO) {
        collections[id]?.let { return@withContext it }
        val dateField = if (tv) "first_air_date" else "release_date"
        val found = (1..2).flatMap { p -> get(if (tv) "discover/tv" else "discover/movie", params + ("page" to p.toString()))?.optJSONArray("results").objects() }
            .filter { it.optString("poster_path").isNotBlank() && it.optString(dateField).isNotBlank() }
            .distinctBy { it.optLong("id") }
            .take(30)
        if (found.isEmpty()) return@withContext null
        // Franchise-like collections run in date order; services and seasons keep TMDB's order (most popular first).
        val ordered = if (byDate) found.sortedBy { it.optString(dateField) } else found
        val parts = ordered.map { p ->
            TmdbCollectionPart(
                item(p, if (tv) "tv" else "movie").copy(poster = img(p.optString("poster_path"), "w500"), backdrop = img(p.optString("backdrop_path"), "w1280")),
                p.optString("overview").takeIf { it.isNotBlank() },
                p.optString(dateField).takeIf { it.isNotBlank() },
            )
        }
        val top = found.first()
        val c = TmdbCollection(
            id = id,
            name = name,
            overview = overview,
            poster = img(top.optString("poster_path"), "w500"),
            backdrop = img(top.optString("backdrop_path"), "original"),
            parts = parts,
        )
        collections[id] = c
        c
    }

    /**
     * How many episodes of a series have aired (specials left out), and how many each season has,
     * so a series can count as finished once you've seen them all. Null if TMDB doesn't know it.
     */
    suspend fun airedEpisodes(imdbId: String): AiredEpisodes? = withContext(Dispatchers.IO) {
        val found = get("find/${imdbId.substringBefore(':')}", mapOf("external_source" to "imdb_id")) ?: return@withContext null
        val id = found.optJSONArray("tv_results")?.optJSONObject(0)?.optLong("id") ?: return@withContext null
        val o = get("tv/$id") ?: return@withContext null
        val seasons = o.optJSONArray("seasons").objects()
            .filter { it.optInt("season_number") > 0 }
            .associate { it.optInt("season_number") to it.optInt("episode_count") }
        val last = o.optJSONObject("last_episode_to_air") ?: return@withContext AiredEpisodes(0, seasons)
        val ls = last.optInt("season_number")
        val le = last.optInt("episode_number")
        val total = seasons.filterKeys { it in 1 until ls }.values.sum() + if (ls > 0) le else 0
        AiredEpisodes(total, seasons)
    }

    /** Titles from a TMDB search, with their genre ids. */
    suspend fun discoverItems(params: Map<String, String>, pages: Int, tv: Boolean = false): List<Pair<TmdbItem, List<Int>>> = withContext(Dispatchers.IO) {
        (1..pages).flatMap { p ->
            get(if (tv) "discover/tv" else "discover/movie", params + ("page" to p.toString()))?.optJSONArray("results").objects()
                .filter { it.optString("poster_path").isNotBlank() }
                .map { o ->
                    val g = o.optJSONArray("genre_ids")
                    item(o, if (tv) "tv" else "movie") to (if (g == null) emptyList() else (0 until g.length()).map { g.optInt(it) })
                }
        }.distinctBy { it.first.id }
    }

    private val keywordIds = ConcurrentHashMap<String, Long>()

    /** TMDB's id for a keyword ("christmas"), so seasonal collections can ask for it. */
    suspend fun keywordId(word: String): Long? = withContext(Dispatchers.IO) {
        keywordIds[word]?.let { return@withContext it.takeIf { id -> id > 0 } }
        // A failed request isn't remembered, so the next try asks again.
        val res = get("search/keyword", mapOf("query" to word)) ?: return@withContext null
        val r = res.optJSONArray("results").objects()
        val id = (r.firstOrNull { it.optString("name").equals(word, true) } ?: r.firstOrNull())?.optLong("id") ?: 0L
        keywordIds[word] = id
        id.takeIf { it > 0 }
    }

    private var providerLogos: Map<Int, String>? = null

    /** Streaming services' logos (by TMDB provider id), as offered in the UK. */
    suspend fun providerLogos(): Map<Int, String> = withContext(Dispatchers.IO) {
        providerLogos?.let { return@withContext it }
        val out = HashMap<Int, String>()
        listOf("movie", "tv").forEach { kind ->
            get("watch/providers/$kind", mapOf("watch_region" to "GB"))?.optJSONArray("results").objects().forEach { p ->
                img(p.optString("logo_path"), "w300")?.let { out.putIfAbsent(p.optInt("provider_id"), it) }
            }
        }
        if (out.isNotEmpty()) providerLogos = out
        out
    }

    /** The IMDb id for a TMDB title, so it can open in Zapp. */
    suspend fun imdbId(item: TmdbItem): String? = withContext(Dispatchers.IO) {
        val key = "${item.type}|${item.id}"
        if (imdbIds.containsKey(key)) return@withContext imdbIds[key]
        // Only a real answer is remembered: a failed request is asked again next time.
        val o = get("${if (item.type == "series") "tv" else "movie"}/${item.id}/external_ids") ?: return@withContext null
        val id = o.optString("imdb_id").takeIf { it.startsWith("tt") }
        imdbIds[key] = id
        id
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
