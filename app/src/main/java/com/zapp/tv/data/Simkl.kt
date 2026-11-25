package com.zapp.tv.data

import com.zapp.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Simkl, using its AUTH V2 device sign-in: the TV shows a code (and a QR link with the code filled in),
 * you approve it on your phone, and Zapp receives a token. No password ever reaches Zapp.
 */
class SimklClient(private val http: OkHttpClient, private val settings: Settings) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data object Expired : Poll
        data class Done(val access: String, val refresh: String) : Poll
        data class Failed(val message: String) : Poll
    }

    class NotSignedIn : Exception("Simkl isn't connected.")

    private val refreshLock = Mutex()
    private val json = "application/json".toMediaType()

    val clientId: String get() = settings.simklClientId

    private fun url(path: String, params: Map<String, String> = emptyMap()): HttpUrl =
        "https://api.simkl.com$path".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("app-name", "zapp-tv")
            .addQueryParameter("app-version", BuildConfig.VERSION_NAME)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private fun builder(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", "zapp-tv/${BuildConfig.VERSION_NAME}")

    // ------------------------------------------------------------------ sign-in

    suspend fun deviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) error("Add your Simkl app ID first.")
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "media:read media:write")
            .build()
        http.newCall(builder(url("/oauth2/device")).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) error("Simkl didn't recognise that app ID. Check it's the Client ID of a device/TV app.")
            if (!r.isSuccessful) error("Simkl answered with HTTP ${r.code}.")
            val o = JSONObject(text)
            DeviceCode(
                deviceCode = o.getString("device_code"),
                userCode = o.getString("user_code"),
                verificationUri = o.optString("verification_uri", "https://simkl.com/pin"),
                verificationUriComplete = o.optString("verification_uri_complete").ifBlank { o.optString("verification_uri", "https://simkl.com/pin") },
                expiresIn = o.optInt("expires_in", 900),
                interval = o.optInt("interval", 5).coerceAtLeast(5),
            )
        }
    }

    suspend fun poll(deviceCode: String): Poll = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .build()
        try {
            http.newCall(builder(url("/oauth2/token")).post(body).build()).execute().use { r ->
                val o = try {
                    JSONObject(r.body?.string().orEmpty())
                } catch (e: Exception) {
                    JSONObject()
                }
                if (r.isSuccessful && o.has("access_token")) {
                    return@withContext Poll.Done(o.getString("access_token"), o.optString("refresh_token"))
                }
                when (o.optString("error")) {
                    "authorization_pending" -> Poll.Pending
                    "slow_down" -> Poll.SlowDown
                    "expired_token" -> Poll.Expired
                    "invalid_client" -> Poll.Failed("Simkl didn't recognise that app ID.")
                    else -> Poll.Failed("Simkl answered with HTTP ${r.code}.")
                }
            }
        } catch (e: java.io.IOException) {
            Poll.Pending // a network blip; keep waiting
        }
    }

    fun saveTokens(access: String, refresh: String) {
        settings.simklAccess = access
        settings.simklRefresh = refresh
    }

    fun signOut() {
        settings.simklAccess = ""
        settings.simklRefresh = ""
        settings.simklUser = ""
        settings.simklActivities = ""
        settings.simklLastSync = 0
    }

    val signedIn: Boolean get() = settings.simklAccess.isNotBlank()

    /** Gets a new access token; refreshes are serialised because each one replaces the previous token. */
    private suspend fun refresh(): Boolean = refreshLock.withLock {
        val refresh = settings.simklRefresh
        if (refresh.isBlank()) return false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refresh)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(builder(url("/oauth2/token")).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                val o = JSONObject(r.body?.string().orEmpty())
                val access = o.optString("access_token")
                if (access.isBlank()) return@withContext false
                settings.simklAccess = access
                o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { settings.simklRefresh = it }
                true
            }
        }
    }

    // ------------------------------------------------------------------ calls

    private suspend fun call(build: (String) -> Request): String {
        if (!signedIn) throw NotSignedIn()
        for (attempt in 0..1) {
            val result = withContext(Dispatchers.IO) {
                http.newCall(build(settings.simklAccess)).execute().use { r -> r.code to r.body?.string().orEmpty() }
            }
            if (result.first == 401 && attempt == 0) {
                if (!refresh()) {
                    signOut()
                    throw NotSignedIn()
                }
                continue
            }
            if (result.first !in 200..299) error("Simkl answered with HTTP ${result.first}.")
            return result.second
        }
        throw NotSignedIn()
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): String =
        call { token -> builder(url(path, params)).header("Authorization", "Bearer $token").get().build() }

    suspend fun post(path: String, body: JSONObject, params: Map<String, String> = emptyMap()): String =
        call { token ->
            builder(url(path, params)).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(json)).build()
        }

    suspend fun userName(): String = try {
        val o = JSONObject(post("/users/settings", JSONObject()))
        o.optJSONObject("user")?.optString("name").orEmpty()
    } catch (e: NotSignedIn) {
        throw e
    } catch (e: Exception) {
        ""
    }
}

/**
 * Keeps Zapp's watch progress, watched marks and library in step with Simkl, which Nuvio can use too.
 * Pulls changes (using Simkl's activity timestamps so it only fetches what moved) and reports what you
 * watch in Zapp with Simkl's play / pause / stop events.
 */
class SimklSync(private val db: AppDatabase, val client: SimklClient, private val settings: Settings) {

    private val dao = db.mediaDao()
    private val lock = Mutex()

    data class Summary(val watched: Int, val inProgress: Int, val library: Int)

    /** Pulls everything that changed since the last sync. Returns null if nothing changed. */
    suspend fun pull(force: Boolean = false): Summary? = lock.withLock {
        val activitiesText = client.get("/sync/activities")
        val activities = JSONObject(activitiesText)
        val all = activities.optString("all")
        val previous = settings.simklActivities
        val previousAll = try {
            if (previous.isBlank()) "" else JSONObject(previous).optString("all")
        } catch (e: Exception) {
            ""
        }
        if (!force && all.isNotBlank() && all == previousAll) return@withLock null

        val dateFrom = previousAll.takeIf { it.isNotBlank() && !force }
        val local = dao.allProgress(1).associateBy { it.videoId }.toMutableMap()
        val libraryIds = dao.libraryIds(1).toHashSet()
        val progressOut = ArrayList<WatchProgress>()
        val libraryOut = ArrayList<LibraryItem>()
        var watched = 0

        for (type in listOf("movies", "shows")) {
            val params = HashMap<String, String>()
            params["extended"] = "full"
            if (type == "shows") params["episode_watched_at"] = "yes"
            if (dateFrom != null) params["date_from"] = dateFrom
            val text = client.get("/sync/all-items/$type", params)
            if (text.isBlank() || text == "null") continue
            val root = JSONObject(text)
            val arr = root.optJSONArray(type) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val media = item.optJSONObject(if (type == "movies") "movie" else "show") ?: continue
                val imdb = media.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") } ?: continue
                val title = media.optString("title")
                val poster = media.optString("poster").takeIf { it.isNotBlank() }?.let { "https://simkl.in/posters/${it}_m.jpg" }
                val fanart = media.optString("fanart").takeIf { it.isNotBlank() }?.let { "https://simkl.in/fanart/${it}_medium.jpg" }
                val status = item.optString("status")
                val metaType = if (type == "movies") "movie" else "series"

                if ((status == "plantowatch" || status == "watching") && imdb !in libraryIds) {
                    libraryOut.add(LibraryItem(metaId = imdb, type = metaType, name = title, poster = poster, addedAt = parseTime(item.optString("added_to_watchlist_at"))))
                    libraryIds.add(imdb)
                }

                if (type == "movies" && status == "completed") {
                    val existing = local[imdb]
                    if (existing == null || !existing.watched) {
                        val p = (existing ?: blank(imdb, imdb, metaType, title, null, poster, fanart))
                            .copy(watched = true, updatedAt = parseTime(item.optString("last_watched_at")))
                        progressOut.add(p)
                        local[imdb] = p
                        watched++
                    }
                }
                if (type == "shows") {
                    val seasons = item.optJSONArray("seasons") ?: continue
                    for (s in 0 until seasons.length()) {
                        val season = seasons.optJSONObject(s) ?: continue
                        val sn = season.optInt("number")
                        val eps = season.optJSONArray("episodes") ?: continue
                        for (e in 0 until eps.length()) {
                            val ep = eps.optJSONObject(e) ?: continue
                            val en = ep.optInt("number")
                            val vid = "$imdb:$sn:$en"
                            val existing = local[vid]
                            if (existing == null || !existing.watched) {
                                val p = (existing ?: blank(vid, imdb, metaType, title, "S$sn E$en", poster, fanart))
                                    .copy(watched = true, updatedAt = parseTime(ep.optString("watched_at")))
                                progressOut.add(p)
                                local[vid] = p
                                watched++
                            }
                        }
                    }
                }
            }
        }

        // Paused films and episodes (Continue watching).
        var inProgress = 0
        val playbackText = client.get("/sync/playback")
        val playback = if (playbackText.trim().startsWith("[")) JSONArray(playbackText) else JSONArray()
        for (i in 0 until playback.length()) {
            val p = playback.optJSONObject(i) ?: continue
            val percent = p.optDouble("progress", 0.0)
            val paused = parseTime(p.optString("paused_at"))
            val isEpisode = p.optString("type") == "episode"
            val media = p.optJSONObject(if (isEpisode) "show" else "movie") ?: p.optJSONObject("anime") ?: continue
            val imdb = media.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") } ?: continue
            val ep = p.optJSONObject("episode")
            val vid = if (isEpisode && ep != null) "$imdb:${ep.optInt("season")}:${ep.optInt("number")}" else imdb
            val existing = local[vid]
            if (existing != null && (existing.updatedAt >= paused || existing.watched)) continue
            val base = existing ?: blank(
                vid,
                imdb,
                if (isEpisode) "series" else "movie",
                media.optString("title"),
                ep?.let { "S${it.optInt("season")} E${it.optInt("number")}" + (it.optString("title").takeIf { t -> t.isNotBlank() }?.let { t -> ", $t" } ?: "") },
                null,
                null,
            )
            val dur = base.duration
            progressOut.add(
                base.copy(
                    percent = percent,
                    position = if (dur > 1) (dur * percent / 100).toLong() else 0,
                    hidden = false,
                    updatedAt = paused,
                ),
            )
            inProgress++
        }

        if (progressOut.isNotEmpty()) dao.saveProgressList(progressOut.distinctBy { it.videoId }.map { it.copy(id = local[it.videoId]?.id?.takeIf { id -> id > 0 } ?: 0) })
        if (libraryOut.isNotEmpty()) dao.addToLibraryList(libraryOut)
        settings.simklActivities = activitiesText
        settings.simklLastSync = System.currentTimeMillis()
        Summary(watched, inProgress, libraryOut.size)
    }

    private fun blank(videoId: String, metaId: String, type: String, name: String, label: String?, poster: String?, fanart: String?) =
        WatchProgress(
            videoId = videoId,
            metaId = metaId,
            type = type,
            name = name,
            episodeLabel = label,
            poster = poster,
            background = fanart,
            position = 0,
            duration = 0,
            watched = false,
            hidden = false,
            updatedAt = System.currentTimeMillis(),
        )

    // ------------------------------------------------------------------ pushing

    /** Reports playback to Simkl. [event] is "start", "pause" or "stop"; stop at 80 % or more marks it watched. */
    suspend fun scrobble(event: String, videoId: String, type: String, title: String, percent: Double) {
        val body = itemBody(videoId, type, title) ?: return
        body.put("progress", String.format(Locale.US, "%.2f", percent.coerceIn(0.0, 100.0)).toDouble())
        try {
            client.post("/scrobble/$event", body)
        } catch (e: Exception) {
            // Scrobbles are best-effort; the next sync catches up.
        }
    }

    /** Adds a title to Simkl's Plan to Watch list (Nuvio shows this as its library). */
    suspend fun addToWatchlist(metaId: String, type: String) {
        if (!metaId.startsWith("tt")) return
        val key = if (type == "movie") "movies" else "shows"
        val body = JSONObject().put(key, JSONArray().put(JSONObject().put("ids", JSONObject().put("imdb", metaId)).put("status", "plantowatch")))
        try {
            client.post("/sync/history", body)
        } catch (e: Exception) {
            // Best-effort.
        }
    }

    private fun itemBody(videoId: String, type: String, title: String): JSONObject? {
        val parts = videoId.split(':')
        val imdb = parts.firstOrNull()?.takeIf { it.startsWith("tt") } ?: return null
        return if (type == "movie" || parts.size < 3) {
            JSONObject().put("movie", JSONObject().put("title", title).put("ids", JSONObject().put("imdb", imdb)))
        } else {
            JSONObject()
                .put("show", JSONObject().put("title", title).put("ids", JSONObject().put("imdb", imdb)))
                .put("episode", JSONObject().put("season", parts[1].toIntOrNull() ?: return null).put("number", parts[2].toIntOrNull() ?: return null))
        }
    }

    companion object {
        private val formats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")

        fun parseTime(s: String?): Long {
            if (s.isNullOrBlank()) return System.currentTimeMillis()
            for (f in formats) {
                try {
                    val fmt = SimpleDateFormat(f, Locale.US)
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    return fmt.parse(s)?.time ?: continue
                } catch (e: Exception) {
                    // try the next format
                }
            }
            return System.currentTimeMillis()
        }
    }
}
