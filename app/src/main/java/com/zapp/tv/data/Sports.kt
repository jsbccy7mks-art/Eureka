package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A team you follow. */
data class SportTeam(val id: String, val name: String, val sport: String, val league: String?, val badge: String?)

/** A match or race. [start] in ms; scores when it's been played. */
data class SportEvent(
    val id: String,
    val title: String,
    val sport: String,
    val league: String?,
    val home: String?,
    val away: String?,
    val homeBadge: String?,
    val awayBadge: String?,
    val start: Long,
    val homeScore: String?,
    val awayScore: String?,
    val status: String?,
    val thumb: String?,
) {
    val finished: Boolean get() = status == "FINISHED" || status == "AWARDED" ||
        (homeScore != null && awayScore != null && status !in INPLAY && System.currentTimeMillis() > start + 2 * 3_600_000L)

    /** Off: postponed, cancelled or suspended. */
    val off: Boolean get() = status == "POSTPONED" || status == "CANCELLED" || status == "SUSPENDED"

    fun liveAt(now: Long): Boolean {
        if (status in INPLAY) return true
        if (finished || off) return false
        return now >= start - 10 * 60_000L && now <= start + 2 * 3_600_000L + 30 * 60_000L && !(status?.contains("FT", true) == true || status?.contains("Finished", true) == true)
    }

    private companion object {
        val INPLAY = setOf("IN_PLAY", "PAUSED", "LIVE")
    }
}

/**
 * Sports fixtures from TheSportsDB. Works with its free shared key; your own key (Patreon) can be
 * added on the phone page for fuller data. Teams you follow are kept per profile.
 */
class Sports(private val http: OkHttpClient, private val settings: Settings) {
    private fun key() = settings.secret("sportsDbKey").ifBlank { "123" }

    private fun get(path: String, params: Map<String, String>): JSONObject? {
        val b = "https://www.thesportsdb.com/api/v1/json/${key()}/$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return try {
            http.newCall(Request.Builder().url(b.build()).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.s(n: String): String? = if (isNull(n)) null else optString(n).takeIf { it.isNotBlank() && it != "null" }

    suspend fun searchTeams(query: String): List<SportTeam> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        get("searchteams.php", mapOf("t" to query.trim()))?.optJSONArray("teams").objects().map {
            SportTeam(it.optString("idTeam"), it.optString("strTeam"), it.optString("strSport"), it.s("strLeague"), it.s("strBadge") ?: it.s("strTeamBadge"))
        }
    }

    // ---- teams you follow (this profile)
    fun followed(): List<SportTeam> = runCatching {
        val a = JSONArray(settings.sportsTeamsJson.ifBlank { "[]" })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SportTeam(o.getString("id"), o.getString("name"), o.optString("sport"), o.s("league"), o.s("badge"))
        }
    }.getOrDefault(emptyList())

    fun setFollowed(list: List<SportTeam>) {
        val a = JSONArray()
        list.forEach { t -> a.put(JSONObject().put("id", t.id).put("name", t.name).put("sport", t.sport).put("league", t.league).put("badge", t.badge)) }
        settings.sportsTeamsJson = a.toString()
    }

    private fun time(o: JSONObject): Long? {
        o.s("strTimestamp")?.let { ts ->
            for (p in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss")) {
                runCatching { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(ts)?.time }
            }
        }
        val d = o.s("dateEvent") ?: return null
        val t = o.s("strTime")?.take(8) ?: "00:00:00"
        return runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("$d $t")?.time }.getOrNull()
    }

    private fun event(o: JSONObject): SportEvent? {
        val start = time(o) ?: return null
        return SportEvent(
            id = o.optString("idEvent"),
            title = o.s("strEvent") ?: listOfNotNull(o.s("strHomeTeam"), o.s("strAwayTeam")).joinToString(" v "),
            sport = o.optString("strSport"),
            league = o.s("strLeague"),
            home = o.s("strHomeTeam"),
            away = o.s("strAwayTeam"),
            homeBadge = o.s("strHomeTeamBadge"),
            awayBadge = o.s("strAwayTeamBadge"),
            start = start,
            homeScore = o.s("intHomeScore"),
            awayScore = o.s("intAwayScore"),
            status = o.s("strStatus"),
            thumb = o.s("strThumb") ?: o.s("strPoster"),
        )
    }

    /** Upcoming and recent matches for the teams you follow, soonest first (recent results last). */
    suspend fun fixtures(teams: List<SportTeam>): List<SportEvent> = withContext(Dispatchers.IO) {
        coroutineScope {
            teams.map { t ->
                async {
                    val next = get("eventsnext.php", mapOf("id" to t.id))?.optJSONArray("events").objects().mapNotNull { event(it) }
                    val last = get("eventslast.php", mapOf("id" to t.id))?.optJSONArray("results").objects().mapNotNull { event(it) }
                    next + last
                }
            }.awaitAll().flatten().distinctBy { it.id }.sortedBy { it.start }
        }
    }
}
