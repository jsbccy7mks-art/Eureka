package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A theme tune to play: a short official preview from a music service, or any audio link you chose. */
data class ThemeTrack(val title: String, val artist: String, val url: String, val source: String)

/**
 * Theme tunes from the free 30-second previews Apple Music and Deezer offer for every track
 * (meant for apps to play; no account or key). Zapp looks for the collection's best-known theme,
 * by name and composer where it knows them, and plays the preview on a gentle loop.
 */
class ThemePreviews(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, List<ThemeTrack>>()

    /** Famous themes, so the search finds the right track (TMDB collection id → search words). */
    private val known = mapOf(
        1241L to "Hedwig's Theme John Williams",
        119L to "Concerning Hobbits Howard Shore",
        121938L to "Misty Mountains Howard Shore",
        10L to "Star Wars Main Title John Williams",
        328L to "Theme from Jurassic Park John Williams",
        264L to "Back to the Future Alan Silvestri",
        295L to "He's a Pirate Klaus Badelt",
        645L to "James Bond Theme Monty Norman",
        84L to "Raiders March John Williams",
        87359L to "Mission Impossible Theme Lalo Schifrin",
        10194L to "You've Got a Friend in Me Randy Newman",
        131292L to "Iron Man Ramin Djawadi",
        87096L to "Avatar James Horner",
        722971L to "Knives Out Nathan Johnson",
        86311L to "The Avengers Alan Silvestri",
        2344L to "The Matrix Main Title Don Davis",
        263L to "The Dark Knight Hans Zimmer",
        528L to "The Terminator Brad Fiedel",
        8091L to "Alien Jerry Goldsmith",
        748L to "X-Men Theme",
        9485L to "Fast and Furious theme",
        131635L to "The Hunger Games Hanging Tree",
        2150L to "Shrek Harry Gregson-Williams",
        86066L to "Despicable Me Pharrell",
        556L to "Spider-Man Danny Elfman",
        531241L to "Spider-Man Michael Giacchino",
        8650L to "Transformers Arrival to Earth Steve Jablonsky",
        2806L to "American Pie",
        404609L to "John Wick Tyler Bates",
    )

    /** Candidates for a collection, best first (several, so you can choose another). */
    suspend fun search(collectionId: Long, name: String): List<ThemeTrack> = withContext(Dispatchers.IO) {
        val key = "$collectionId"
        cache[key]?.let { return@withContext it }
        val franchise = name.removeSuffix(" Collection").trim()
        val query = known[collectionId] ?: "$franchise main theme"
        val found = (apple(query) + deezer(query) + if (known[collectionId] == null) apple("$franchise theme") else emptyList())
        // Best first: soundtracks named after the franchise, themes and main titles, then the rest.
        val words = franchise.lowercase().split(' ', ':', '-').filter { it.length > 2 && it !in setOf("the", "and", "of") }
        fun score(t: ThemeTrack): Int {
            val text = (t.title + " " + t.artist).lowercase()
            var s = 0
            if (words.any { it in text }) s += 3
            if ("theme" in text || "main title" in text || "suite" in text) s += 2
            if (known[collectionId]?.lowercase()?.split(' ')?.count { it.length > 3 && it in text } ?: 0 >= 2) s += 4
            if ("karaoke" in text || "cover" in text || "remix" in text || "piano version" in text) s -= 4
            return s
        }
        val list = found.distinctBy { it.title.lowercase() + "|" + it.artist.lowercase() }.sortedByDescending { score(it) }.take(8)
        if (list.isNotEmpty()) cache[key] = list
        list
    }

    private fun apple(q: String): List<ThemeTrack> = runCatching {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", q).addQueryParameter("media", "music").addQueryParameter("entity", "song")
            .addQueryParameter("limit", "8").addQueryParameter("country", "GB").build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList()
            val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return@use emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val p = o.optString("previewUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                ThemeTrack(o.optString("trackName"), o.optString("artistName"), p, "Apple Music")
            }
        }
    }.getOrDefault(emptyList())

    private fun deezer(q: String): List<ThemeTrack> = runCatching {
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "8").build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@use emptyList()
            val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val p = o.optString("preview").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                ThemeTrack(o.optString("title"), o.optJSONObject("artist")?.optString("name").orEmpty(), p, "Deezer")
            }
        }
    }.getOrDefault(emptyList())
}
