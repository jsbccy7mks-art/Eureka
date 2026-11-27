package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.seekr.previews.android.Seekr
import tv.seekr.previews.android.SeekrTrack
import tv.seekr.previews.android.SeekrTrackResult
import tv.seekr.previews.core.SeekrContent
import java.util.concurrent.ConcurrentHashMap

/**
 * Seek previews from Seekr (seekr.tv): ready-made pictures every few seconds of a film or
 * episode, fetched as small sprite sheets. Nothing is taken from the stream or decoded on the TV.
 *
 * Needs the user's own Seekr key (added from the phone setup page). The key is stored encrypted
 * with the other keys and is only ever sent to Seekr, never logged.
 */
class SeekrPreviews(private val http: OkHttpClient, private val settings: Settings) {
    /** Turned on, with a key. */
    val enabled: Boolean get() = settings.seekrOn && hasKey
    val hasKey: Boolean get() = settings.secret(KEY).isNotBlank()

    @Volatile private var client: Pair<String, Seekr>? = null

    private fun client(): Seekr? {
        val key = settings.secret(KEY).ifBlank { return null }
        client?.takeIf { it.first == key }?.let { return it.second }
        return Seekr.create(apiKey = key, httpClient = http).also { client = key to it }
    }

    /** Checks a key with Seekr before it's saved. */
    suspend fun check(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { Seekr.create(apiKey = key, httpClient = http).validateKey() }.getOrDefault(false)
    }

    // Titles Seekr has no pictures for, and when to ask again (it rarely changes, so not for hours).
    private val missing = ConcurrentHashMap<String, Long>()
    // A refused key isn't sent again until it changes.
    @Volatile private var refusedKey: String? = null
    @Volatile private var quietUntil = 0L

    /**
     * The previews for what's playing, or null (no key, switched off, not covered, or Seekr
     * unreachable). [videoId] is the Stremio-style id: "tt…" for a film, "tt…:season:episode"
     * for an episode (TMDB "tmdb:…" and Kitsu "kitsu:…" ids work too).
     */
    suspend fun track(videoId: String, type: String, durationMs: Long, positionMs: Long): SeekrTrack? {
        if (!enabled || durationMs <= 0) return null
        val key = settings.secret(KEY)
        if (key == refusedKey) return null
        val now = System.currentTimeMillis()
        if (now < quietUntil) return null
        val content = contentFor(videoId, type) ?: return null
        val memo = "$videoId|${durationMs / 60_000}"
        missing[memo]?.let { if (now < it) return null }
        val c = client() ?: return null
        return when (val r = runCatching { c.lookupTrack(content, durationMs, positionMs) }.getOrNull()) {
            is SeekrTrackResult.Found -> r.track
            SeekrTrackResult.NoPreviews -> {
                missing[memo] = now + 6 * 3_600_000L
                null
            }
            is SeekrTrackResult.Rejected -> {
                refusedKey = key
                null
            }
            is SeekrTrackResult.RateLimited -> {
                quietUntil = now + ((r.retryAfterSec ?: 60).coerceIn(5, 3_600)) * 1000L
                null
            }
            else -> null
        }
    }

    /** Clears "refused" once the key is changed or checked again. */
    fun keyChanged() {
        refusedKey = null
        quietUntil = 0L
    }

    private fun contentFor(videoId: String, type: String): SeekrContent? {
        val parts = videoId.split(':')
        return runCatching {
            when {
                parts[0].startsWith("tt") && parts.size >= 3 ->
                    SeekrContent.Episode(showImdbId = parts[0], season = parts[1].toInt(), episode = parts[2].toInt())
                parts[0].startsWith("tt") && type != "series" -> SeekrContent.Movie(imdbId = parts[0])
                parts[0] == "tmdb" && parts.size >= 4 ->
                    SeekrContent.Episode(showTmdbId = parts[1].toInt(), season = parts[2].toInt(), episode = parts[3].toInt())
                parts[0] == "tmdb" && parts.size >= 2 && type != "series" -> SeekrContent.Movie(tmdbId = parts[1].toInt())
                parts[0] == "kitsu" && parts.size >= 3 -> SeekrContent.Anime(parts[1].toInt(), parts[2].toInt())
                parts[0] == "kitsu" && parts.size == 2 -> SeekrContent.Anime(parts[1].toInt())
                else -> null
            }
        }.getOrNull()
    }

    companion object {
        const val KEY = "seekrKey"
    }
}
