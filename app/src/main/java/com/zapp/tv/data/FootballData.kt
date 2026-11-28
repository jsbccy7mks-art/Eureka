package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A league or cup football-data.org covers on its free plan. */
data class Competition(val code: String, val name: String, val country: String)

/**
 * Football fixtures, live scores and results for whole leagues, from football-data.org (free key).
 * The free plan allows 10 calls a minute, so every league you follow is fetched in one call and the
 * answer is kept for a minute (longer when nothing is live). The key is stored encrypted and is never
 * written to the log.
 */
class FootballData(private val http: OkHttpClient, private val settings: Settings) {
    companion object {
        const val KEY = "footballDataKey"
        val COMPETITIONS = listOf(
            Competition("PL", "Premier League", "England"),
            Competition("ELC", "Championship", "England"),
            Competition("CL", "Champions League", "Europe"),
            Competition("PD", "La Liga", "Spain"),
            Competition("BL1", "Bundesliga", "Germany"),
            Competition("SA", "Serie A", "Italy"),
            Competition("FL1", "Ligue 1", "France"),
            Competition("DED", "Eredivisie", "Netherlands"),
            Competition("PPL", "Primeira Liga", "Portugal"),
            Competition("BSA", "Brasileirão", "Brazil"),
            Competition("EC", "European Championship", "Europe"),
            Competition("WC", "World Cup", "World"),
        )
        private val LIVE = setOf("IN_PLAY", "PAUSED", "LIVE")
        private const val DAY = 86_400_000L
    }

    val hasKey: Boolean get() = settings.secret(KEY).isNotBlank()

    // ---- leagues this profile follows
    fun followed(): List<String> = settings.sportsLeagues.split(',').map { it.trim() }.filter { c -> COMPETITIONS.any { it.code == c } }

    fun setFollowed(codes: List<String>) {
        settings.sportsLeagues = COMPETITIONS.map { it.code }.filter { it in codes }.joinToString(",")
    }

    private val lock = Mutex()
    private var cacheKey = ""
    private var cacheAt = 0L
    private var cache: List<SportEvent> = emptyList()

    /** Checks a key by asking for one competition. True when football-data.org accepts it. */
    suspend fun check(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("https://api.football-data.org/v4/competitions/PL").header("X-Auth-Token", key).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Matches in the leagues you follow from three days ago to a week ahead, soonest first. */
    suspend fun matches(codes: List<String>, force: Boolean = false): List<SportEvent> = withContext(Dispatchers.IO) {
        val key = settings.secret(KEY)
        if (key.isBlank() || codes.isEmpty()) return@withContext emptyList()
        lock.withLock {
            val now = System.currentTimeMillis()
            val id = codes.sorted().joinToString(",")
            val anyLive = cache.any { it.status in LIVE || it.liveAt(now) }
            val maxAge = if (anyLive) 55_000L else if (force) 60_000L else 10 * 60_000L
            if (id == cacheKey && now - cacheAt < maxAge) return@withLock cache

            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val url = "https://api.football-data.org/v4/matches".toHttpUrl().newBuilder()
                .addQueryParameter("competitions", id)
                .addQueryParameter("dateFrom", day.format(now - 3 * DAY))
                .addQueryParameter("dateTo", day.format(now + 6 * DAY)) // the free plan allows ten days per call
                .build()
            val body = runCatching {
                http.newCall(Request.Builder().url(url).header("X-Auth-Token", key).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull() ?: return@withLock cache.takeIf { id == cacheKey }.orEmpty()

            val list = runCatching {
                val a = JSONObject(body).optJSONArray("matches")
                (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.let(::event) }.sortedBy { it.start }
            }.getOrDefault(emptyList())
            cache = list
            cacheKey = id
            cacheAt = now
            list
        }
    }

    private fun JSONObject.s(n: String): String? = if (isNull(n)) null else optString(n).takeIf { it.isNotBlank() && it != "null" }

    private fun event(o: JSONObject): SportEvent? {
        val start = o.s("utcDate")?.let { ts ->
            runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(ts)?.time }.getOrNull()
        } ?: return null
        val home = o.optJSONObject("homeTeam")
        val away = o.optJSONObject("awayTeam")
        val comp = o.optJSONObject("competition")
        val full = o.optJSONObject("score")?.optJSONObject("fullTime")
        fun name(t: JSONObject?) = t?.s("shortName") ?: t?.s("name")
        val homeName = name(home) ?: "TBC"
        val awayName = name(away) ?: "TBC"
        return SportEvent(
            id = "fd:" + o.optString("id"),
            title = "$homeName v $awayName",
            sport = "Soccer",
            league = comp?.s("name"),
            home = homeName,
            away = awayName,
            homeBadge = home?.s("crest"),
            awayBadge = away?.s("crest"),
            start = start,
            homeScore = full?.s("home"),
            awayScore = full?.s("away"),
            status = o.s("status"),
            thumb = comp?.s("emblem"),
        )
    }
}
