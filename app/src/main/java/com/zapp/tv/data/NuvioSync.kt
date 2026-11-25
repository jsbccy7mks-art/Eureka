package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class NuvioException(message: String) : Exception(message)

/**
 * Two-way sync with a Nuvio account, through Nuvio's own server API (the one its apps use and that
 * the Nuvio self-host project documents).
 *
 * - The server is found through its public discovery document (`/.well-known/nuvio`), which also
 *   gives the publishable client key. Nothing is built into Zapp; self-hosted servers work too.
 * - Sign-in is the account's email and password, used once. Only the session (access and refresh
 *   tokens) is kept, encrypted, per Zapp profile, and never logged.
 * - Each Zapp profile links to one Nuvio profile.
 * - First connection: snapshot of Nuvio's progress, watched list and library merged into Zapp
 *   (newest wins, nothing removed), then Zapp's own items sent up. After that only changes move:
 *   Nuvio's delta feeds (including removals) come down, and Zapp's changes go up through a queue
 *   that survives being offline.
 * - Only films and episodes with IMDb ids are synced as progress. Live channels and TV replays never are.
 */
class NuvioSync(db: AppDatabase, private val http: OkHttpClient, private val settings: Settings) {
    companion object {
        const val DEFAULT_SERVER = "https://api.nuvio.tv"
        private const val PAGE = 1000
        private const val PUSH_CHUNK = 100
    }

    private val dao = db.mediaDao()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val lock = Mutex()
    private val queueLock = Any()

    data class NuvioProfile(val index: Int, val name: String)
    data class Summary(val progress: Int, val watched: Int, val library: Int, val removed: Int, val sent: Int)

    // ------------------------------------------------------------------ stored state (per Zapp profile)

    fun signedIn(pid: Long) = settings.acctSecretFor(pid, "nuvioRefresh").isNotBlank()
    fun user(pid: Long) = settings.acctStrFor(pid, "nuvioUser")
    fun server(pid: Long) = settings.acctStrFor(pid, "nuvioServer").ifBlank { DEFAULT_SERVER }
    private fun key(pid: Long) = settings.acctStrFor(pid, "nuvioKey")
    fun linkedProfile(pid: Long): Int = settings.acctLongFor(pid, "nuvioProfile").toInt()
    fun linkedProfileName(pid: Long) = settings.acctStrFor(pid, "nuvioProfileName")
    fun lastSync(pid: Long) = settings.acctLongFor(pid, "nuvioLastSync")
    /** Continue watching shows exactly what Nuvio's does (titles only in Zapp are hidden, not deleted). */
    fun mirror(pid: Long) = settings.acctLongFor(pid, "nuvioMirror") == 1L
    fun setMirror(pid: Long, on: Boolean) = settings.setAcctLongFor(pid, "nuvioMirror", if (on) 1 else 0)

    fun pending(pid: Long): Int = synchronized(queueLock) { readQueue(pid).length() }

    private fun resetSyncState(pid: Long) {
        listOf("nuvioCurProgress", "nuvioCurWatched", "nuvioCurLibrary", "nuvioSeeded", "nuvioLastSync").forEach {
            settings.setAcctLongFor(pid, it, 0)
        }
        synchronized(queueLock) { settings.setAcctStrFor(pid, "nuvioQueue", "") }
    }

    // ------------------------------------------------------------------ server and session

    private fun normalise(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isBlank()) return DEFAULT_SERVER
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return s
    }

    private data class Discovery(val server: String, val key: String)

    private fun discover(base: String): Discovery {
        val req = Request.Builder().url("$base/.well-known/nuvio").get().build()
        val o = try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw NuvioException("That isn't a Nuvio server (it answered ${r.code}).")
                JSONObject(r.body?.string().orEmpty())
            }
        } catch (e: NuvioException) {
            throw e
        } catch (e: Exception) {
            throw NuvioException("Couldn't reach the Nuvio server. Check the address and your connection.")
        }
        if (o.optString("service") != "nuvio") throw NuvioException("That isn't a Nuvio server.")
        val key = o.optString("publishable_key")
        if (key.isBlank()) throw NuvioException("The Nuvio server didn't give a client key.")
        val caps = o.optJSONObject("capabilities")
        if (caps != null && caps.has("email_password_auth") && !caps.optBoolean("email_password_auth")) {
            throw NuvioException("This Nuvio server doesn't allow email sign-in.")
        }
        val server = o.optString("backend_url").takeIf { it.startsWith("http") }?.trimEnd('/') ?: base
        return Discovery(server, key)
    }

    private fun authCall(pid: Long, server: String, key: String, grant: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$server/auth/v1/token?grant_type=$grant")
            .header("apikey", key)
            .post(body.toString().toRequestBody(json))
            .build()
        val (code, text) = try {
            http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw NuvioException("Couldn't reach Nuvio. Check your connection.")
        }
        val o = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
        if (code !in 200..299) {
            if (grant == "refresh_token" && code in 400..499) {
                clearSession(pid)
                throw NuvioException("Nuvio signed Zapp out. Connect Nuvio again.")
            }
            throw NuvioException(
                when {
                    code == 400 || code == 401 -> "Nuvio didn't accept that email and password."
                    code == 429 -> "Nuvio is busy. Try again in a minute."
                    else -> "Nuvio couldn't sign in ($code)."
                },
            )
        }
        val access = o.optString("access_token")
        val refresh = o.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw NuvioException("Nuvio didn't sign in.")
        settings.setAcctSecretFor(pid, "nuvioAccess", access)
        settings.setAcctSecretFor(pid, "nuvioRefresh", refresh)
        settings.setAcctLongFor(pid, "nuvioExpires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
        return o
    }

    private fun clearSession(pid: Long) {
        settings.setAcctSecretFor(pid, "nuvioAccess", "")
        settings.setAcctSecretFor(pid, "nuvioRefresh", "")
        settings.setAcctLongFor(pid, "nuvioExpires", 0)
    }

    private fun accessToken(pid: Long, forceRefresh: Boolean = false): String {
        val access = settings.acctSecretFor(pid, "nuvioAccess")
        if (!forceRefresh && access.isNotBlank() && System.currentTimeMillis() < settings.acctLongFor(pid, "nuvioExpires") - 60_000) {
            return access
        }
        val refresh = settings.acctSecretFor(pid, "nuvioRefresh")
        if (refresh.isBlank()) throw NuvioException("Connect Nuvio first.")
        authCall(pid, server(pid), key(pid), "refresh_token", JSONObject().put("refresh_token", refresh))
        return settings.acctSecretFor(pid, "nuvioAccess")
    }

    /** Calls one of Nuvio's sync functions. Returns the raw reply (JSON array, number or empty). */
    private fun rpc(pid: Long, fn: String, params: JSONObject, retried: Boolean = false): String {
        val token = accessToken(pid, forceRefresh = retried)
        val req = Request.Builder()
            .url("${server(pid)}/rest/v1/rpc/$fn")
            .header("apikey", key(pid))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(params.toString().toRequestBody(json))
            .build()
        val (code, text) = try {
            http.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw NuvioException("Couldn't reach Nuvio. Changes will be sent when it's back.")
        }
        if (code == 401 && !retried) return rpc(pid, fn, params, retried = true)
        if (code !in 200..299) {
            val err = runCatching { JSONObject(text) }.getOrNull()
            val sqlCode = err?.optString("code").orEmpty()
            throw NuvioException(
                when {
                    code == 401 || sqlCode == "28000" -> {
                        clearSession(pid)
                        "Nuvio signed Zapp out. Connect Nuvio again."
                    }
                    code == 429 -> "Nuvio is busy. Zapp will try again shortly."
                    code >= 500 -> "Nuvio's server had a problem ($code). Zapp will try again."
                    else -> "Nuvio refused a sync request ($code)."
                },
            )
        }
        return text
    }

    private fun rpcArray(pid: Long, fn: String, params: JSONObject): JSONArray {
        val t = rpc(pid, fn, params).trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONArray()
    }

    private fun rpcLong(pid: Long, fn: String, params: JSONObject): Long =
        rpc(pid, fn, params).trim().trim('"').toLongOrNull() ?: 0

    // ------------------------------------------------------------------ connect, profiles, disconnect

    /** Signs in with email and password (used once, never stored). */
    suspend fun signIn(pid: Long, serverInput: String, email: String, password: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val d = discover(normalise(serverInput))
            authCall(pid, d.server, d.key, "password", JSONObject().put("email", email.trim()).put("password", password))
            settings.setAcctStrFor(pid, "nuvioServer", d.server)
            settings.setAcctStrFor(pid, "nuvioKey", d.key)
            settings.setAcctStrFor(pid, "nuvioUser", email.trim())
            settings.setAcctLongFor(pid, "nuvioProfile", 0)
            settings.setAcctStrFor(pid, "nuvioProfileName", "")
            resetSyncState(pid)
        }
        // One Nuvio profile: link it straight away. More: the TV asks which.
        val list = profiles(pid)
        if (list.size == 1) linkProfile(pid, list[0])
    }

    suspend fun profiles(pid: Long): List<NuvioProfile> = withContext(Dispatchers.IO) {
        val a = rpcArray(pid, "sync_pull_profiles", JSONObject())
        val out = ArrayList<NuvioProfile>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val idx = o.optInt("profile_index")
            if (idx in 1..6) out.add(NuvioProfile(idx, o.optString("name").ifBlank { "Profile $idx" }))
        }
        if (out.isEmpty()) out.add(NuvioProfile(1, "Main profile"))
        out.sortedBy { it.index }
    }

    /** Links this Zapp profile to a Nuvio profile; the next sync merges the two. */
    suspend fun linkProfile(pid: Long, p: NuvioProfile) = lock.withLock {
        if (linkedProfile(pid) == p.index) return@withLock
        settings.setAcctLongFor(pid, "nuvioProfile", p.index.toLong())
        settings.setAcctStrFor(pid, "nuvioProfileName", p.name)
        resetSyncState(pid)
    }

    suspend fun signOut(pid: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val token = settings.acctSecretFor(pid, "nuvioAccess")
            if (token.isNotBlank()) {
                runCatching {
                    val req = Request.Builder()
                        .url("${server(pid)}/auth/v1/logout?scope=local")
                        .header("apikey", key(pid))
                        .header("Authorization", "Bearer $token")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                    http.newCall(req).execute().close()
                }
            }
            clearSession(pid)
            listOf("nuvioUser", "nuvioServer", "nuvioKey", "nuvioProfileName").forEach { settings.setAcctStrFor(pid, it, "") }
            settings.setAcctLongFor(pid, "nuvioProfile", 0)
            resetSyncState(pid)
        }
    }

    // ------------------------------------------------------------------ ids

    private data class Ids(val contentId: String, val type: String, val videoId: String, val season: Int?, val episode: Int?) {
        val progressKey get() = if (season != null && episode != null) "${contentId}_s${season}e$episode" else contentId
    }

    /** Zapp's ids to Nuvio's; null for anything that isn't an IMDb film or episode. */
    private fun idsOf(metaId: String, type: String, videoId: String): Ids? {
        if (!metaId.startsWith("tt") || videoId.startsWith("catchup:")) return null
        if (type == "movie") return Ids(metaId, "movie", metaId, null, null)
        if (type != "series") return null
        val parts = videoId.split(':')
        if (parts.size < 3 || parts[0] != metaId) return null
        val s = parts[parts.size - 2].toIntOrNull() ?: return null
        val e = parts.last().toIntOrNull() ?: return null
        return Ids(metaId, "series", "$metaId:$s:$e", s, e)
    }

    /** Nuvio's ids to Zapp's; null when Zapp can't match the title. */
    private fun idsFrom(contentId: String, type: String, season: Int?, episode: Int?): Ids? {
        if (!contentId.startsWith("tt")) return null
        if (season != null && episode != null) return Ids(contentId, "series", "$contentId:$season:$episode", season, episode)
        if (type == "series") return null // a show without an episode isn't progress Zapp can resume
        return Ids(contentId, "movie", contentId, null, null)
    }

    private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name) || !has(name)) null else optInt(name)

    // ------------------------------------------------------------------ outgoing queue

    private fun readQueue(pid: Long): JSONObject =
        runCatching { JSONObject(settings.acctStrFor(pid, "nuvioQueue").ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun enqueue(pid: Long, id: String, op: JSONObject) {
        if (!signedIn(pid) || linkedProfile(pid) == 0) return
        synchronized(queueLock) {
            val q = readQueue(pid)
            q.put(id, op.put("t", System.nanoTime()))
            settings.setAcctStrFor(pid, "nuvioQueue", q.toString())
        }
    }

    private fun progressEntry(ids: Ids, position: Long, duration: Long, lastWatched: Long) = JSONObject()
        .put("content_id", ids.contentId)
        .put("content_type", ids.type)
        .put("video_id", ids.videoId)
        .put("season", ids.season ?: JSONObject.NULL)
        .put("episode", ids.episode ?: JSONObject.NULL)
        .put("position", position)
        .put("duration", duration)
        .put("last_watched", lastWatched)
        .put("progress_key", ids.progressKey)

    /** Where you got to in a film or episode. */
    fun queueProgress(pid: Long, metaId: String, type: String, videoId: String, position: Long, duration: Long, at: Long) {
        val ids = idsOf(metaId, type, videoId) ?: return
        if (duration <= 0) return
        enqueue(pid, "p:${ids.progressKey}", JSONObject().put("op", "progress").put("entry", progressEntry(ids, position, duration, at)))
    }

    /** Removed from Continue watching: the title's unfinished progress goes from Nuvio too. */
    suspend fun queueContinueRemoval(pid: Long, metaId: String) {
        dao.progressOfMeta(pid, metaId).filter { !it.watched && it.percent?.let { p -> p >= 0 } != false }.forEach { w ->
            val ids = idsOf(w.metaId, w.type, w.videoId) ?: return@forEach
            enqueue(pid, "p:${ids.progressKey}", JSONObject().put("op", "progressDelete").put("key", ids.progressKey))
        }
    }

    /** Marked (or unmarked) as watched by hand. [videoId] is the film id or "show:season:episode". */
    fun queueWatched(pid: Long, metaId: String, type: String, videoId: String, title: String, watched: Boolean) {
        val ids = idsOf(metaId, type, videoId) ?: return
        val key = JSONObject().put("content_id", ids.contentId)
            .put("season", ids.season ?: JSONObject.NULL).put("episode", ids.episode ?: JSONObject.NULL)
        val op = if (watched) {
            JSONObject().put("op", "watched").put(
                "item",
                JSONObject().put("content_id", ids.contentId).put("content_type", ids.type).put("title", title)
                    .put("season", ids.season ?: JSONObject.NULL).put("episode", ids.episode ?: JSONObject.NULL)
                    .put("watched_at", System.currentTimeMillis()),
            )
        } else {
            JSONObject().put("op", "watchedDelete").put("key", key)
        }
        enqueue(pid, "w:${ids.progressKey}", op)
    }

    fun queueLibrary(pid: Long, metaId: String, type: String, name: String, poster: String?, inLibrary: Boolean) {
        if (metaId.isBlank() || (type != "movie" && type != "series")) return
        val op = if (inLibrary) {
            JSONObject().put("op", "libraryAdd").put(
                "item",
                JSONObject()
                    .put("content_id", metaId).put("content_type", type).put("name", name)
                    .put("poster", poster ?: JSONObject.NULL).put("poster_shape", "POSTER")
                    .put("added_at", System.currentTimeMillis()),
            )
        } else {
            JSONObject().put("op", "libraryDelete").put("key", JSONObject().put("content_id", metaId).put("content_type", type))
        }
        enqueue(pid, "l:$type:$metaId", op)
    }

    /** Sends queued changes. Anything that fails stays queued for next time. Returns how many went. */
    suspend fun flush(pid: Long): Int = withContext(Dispatchers.IO) { lock.withLock { flushLocked(pid) } }

    private fun flushLocked(pid: Long): Int {
        val profile = linkedProfile(pid)
        if (!signedIn(pid) || profile == 0) return 0
        val snapshot = synchronized(queueLock) { readQueue(pid) }
        if (snapshot.length() == 0) return 0
        val progress = JSONArray()
        val progressDeletes = JSONArray()
        val libraryAdds = JSONArray()
        val libraryDeletes = JSONArray()
        val watchedAdds = JSONArray()
        val watchedDeletes = JSONArray()
        snapshot.keys().forEach { k ->
            val o = snapshot.optJSONObject(k) ?: return@forEach
            when (o.optString("op")) {
                "progress" -> o.optJSONObject("entry")?.let { progress.put(it) }
                "progressDelete" -> progressDeletes.put(o.optString("key"))
                "libraryAdd" -> o.optJSONObject("item")?.let { libraryAdds.put(it) }
                "libraryDelete" -> o.optJSONObject("key")?.let { libraryDeletes.put(it) }
                "watched" -> o.optJSONObject("item")?.let { watchedAdds.put(it) }
                "watchedDelete" -> o.optJSONObject("key")?.let { watchedDeletes.put(it) }
            }
        }
        val origin = settings.installId
        val base = { JSONObject().put("p_profile_id", profile) }
        if (progress.length() > 0) rpc(pid, "sync_push_watch_progress", base().put("p_entries", progress).put("p_origin_client_id", origin))
        if (progressDeletes.length() > 0) rpc(pid, "sync_delete_watch_progress", base().put("p_keys", progressDeletes).put("p_origin_client_id", origin))
        if (libraryAdds.length() > 0) rpc(pid, "sync_push_library_items", base().put("p_items", libraryAdds).put("p_origin_client_id", origin))
        if (watchedAdds.length() > 0) rpc(pid, "sync_push_watched_items", base().put("p_items", watchedAdds).put("p_origin_client_id", origin))
        if (watchedDeletes.length() > 0) rpc(pid, "sync_delete_watched_items", base().put("p_keys", watchedDeletes).put("p_origin_client_id", origin))
        if (libraryDeletes.length() > 0) rpc(pid, "sync_delete_library_items", base().put("p_keys", libraryDeletes).put("p_origin_client_id", origin))
        // Drop only what was sent and hasn't changed since.
        synchronized(queueLock) {
            val q = readQueue(pid)
            snapshot.keys().forEach { k ->
                val sent = snapshot.optJSONObject(k)?.optLong("t")
                if (q.optJSONObject(k)?.optLong("t") == sent) q.remove(k)
            }
            settings.setAcctStrFor(pid, "nuvioQueue", q.toString())
        }
        return snapshot.length()
    }

    // ------------------------------------------------------------------ sync

    /** Sends queued changes, then brings Nuvio's changes in (a full merge the first time). */
    suspend fun sync(pid: Long): Summary = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!signedIn(pid)) throw NuvioException("Connect Nuvio first.")
            val profile = linkedProfile(pid)
            if (profile == 0) throw NuvioException("Choose which Nuvio profile to sync with.")
            val counter = Counter()
            val sent = if (settings.acctLongFor(pid, "nuvioSeeded") == 0L) {
                firstMerge(pid, profile, counter)
            } else {
                val s = flushLocked(pid)
                pullDeltas(pid, profile, counter)
                s
            }
            if (mirror(pid)) mirrorContinue(pid, profile, counter)
            settings.setAcctLongFor(pid, "nuvioLastSync", System.currentTimeMillis())
            Summary(counter.progress, counter.watched, counter.library, counter.removed, sent)
        }
    }

    private class Counter {
        var progress = 0
        var watched = 0
        var library = 0
        var removed = 0
    }

    private suspend fun firstMerge(pid: Long, profile: Int, c: Counter): Int {
        val p = { JSONObject().put("p_profile_id", profile) }
        // Cursors first, so nothing that changes during the snapshot is missed.
        val curProgress = rpcLong(pid, "sync_get_watch_progress_delta_cursor", p())
        val curWatched = rpcLong(pid, "sync_get_watched_items_delta_cursor", p())
        val curLibrary = rpcLong(pid, "sync_get_library_delta_cursor", p())

        val progress = rpcArray(pid, "sync_pull_watch_progress", p())
        for (i in 0 until progress.length()) progress.optJSONObject(i)?.let { if (applyProgress(pid, it)) c.progress++ }

        var page = 1
        while (true) {
            val a = rpcArray(pid, "sync_pull_watched_items", p().put("p_page", page).put("p_page_size", PAGE))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (applyWatched(pid, it)) c.watched++ }
            if (a.length() < PAGE) break
            page++
        }

        val remoteLibrary = HashSet<String>()
        var offset = 0
        while (true) {
            val a = rpcArray(pid, "sync_pull_library", p().put("p_limit", 500).put("p_offset", offset))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                remoteLibrary.add(o.optString("content_type") + ":" + o.optString("content_id"))
                if (applyLibrary(pid, o)) c.library++
            }
            if (a.length() < 500) break
            offset += 500
        }

        // Then what Zapp has that Nuvio doesn't. Nuvio keeps whichever is newer.
        var sent = 0
        val origin = settings.installId
        val local = dao.allProgress(pid)
        val entries = ArrayList<JSONObject>()
        val watched = ArrayList<JSONObject>()
        local.forEach { w ->
            val ids = idsOf(w.metaId, w.type, w.videoId) ?: return@forEach
            if (w.watched) {
                watched.add(
                    JSONObject().put("content_id", ids.contentId).put("content_type", ids.type).put("title", w.name)
                        .put("season", ids.season ?: JSONObject.NULL).put("episode", ids.episode ?: JSONObject.NULL)
                        .put("watched_at", w.updatedAt),
                )
            } else if (w.duration > 0 && w.position > 30_000 && !w.hidden) {
                entries.add(progressEntry(ids, w.position, w.duration, w.updatedAt))
            }
        }
        entries.chunked(PUSH_CHUNK).forEach {
            rpc(pid, "sync_push_watch_progress", p().put("p_entries", JSONArray(it)).put("p_origin_client_id", origin))
            sent += it.size
        }
        watched.chunked(PUSH_CHUNK).forEach {
            rpc(pid, "sync_push_watched_items", p().put("p_items", JSONArray(it)).put("p_origin_client_id", origin))
            sent += it.size
        }
        val libraryOut = dao.allLibrary(pid)
            .filter { (it.type == "movie" || it.type == "series") && (it.type + ":" + it.metaId) !in remoteLibrary }
            .map {
                JSONObject().put("content_id", it.metaId).put("content_type", it.type).put("name", it.name)
                    .put("poster", it.poster ?: JSONObject.NULL).put("poster_shape", "POSTER").put("added_at", it.addedAt)
            }
        libraryOut.chunked(PUSH_CHUNK).forEach {
            rpc(pid, "sync_push_library_items", p().put("p_items", JSONArray(it)).put("p_origin_client_id", origin))
            sent += it.size
        }
        // Anything queued before the merge is covered by it.
        synchronized(queueLock) { settings.setAcctStrFor(pid, "nuvioQueue", "") }

        settings.setAcctLongFor(pid, "nuvioCurProgress", curProgress)
        settings.setAcctLongFor(pid, "nuvioCurWatched", curWatched)
        settings.setAcctLongFor(pid, "nuvioCurLibrary", curLibrary)
        settings.setAcctLongFor(pid, "nuvioSeeded", 1)
        return sent
    }

    /**
     * Nuvio as the source: everything in Nuvio's progress shows in Continue watching, in Nuvio's
     * order (by when it was last watched); anything else unfinished is hidden from the row.
     * Up next entries Zapp adds after an episode stay.
     */
    private suspend fun mirrorContinue(pid: Long, profile: Int, c: Counter) {
        val remote = rpcArray(pid, "sync_pull_watch_progress", JSONObject().put("p_profile_id", profile))
        val shown = HashMap<String, Long>()
        for (i in 0 until remote.length()) {
            val o = remote.optJSONObject(i) ?: continue
            if (applyProgress(pid, o)) c.progress++
            val ids = idsFrom(o.optString("content_id"), o.optString("content_type"), o.intOrNull("season"), o.intOrNull("episode")) ?: continue
            shown[ids.videoId] = o.optLong("last_watched")
        }
        for (w in dao.allProgress(pid)) {
            if (w.watched || (w.percent ?: 0.0) < 0) continue
            val inNuvio = w.videoId in shown
            if (!inNuvio && !w.hidden) {
                dao.saveProgress(w.copy(hidden = true))
                c.removed++
            } else if (inNuvio && w.hidden) {
                dao.saveProgress(w.copy(hidden = false))
            }
        }
    }

    private suspend fun pullDeltas(pid: Long, profile: Int, c: Counter) {
        pullFeed(pid, profile, "sync_pull_watch_progress_delta", "nuvioCurProgress") { o, delete ->
            if (delete) { if (removeProgress(pid, o)) c.removed++ } else if (applyProgress(pid, o)) c.progress++
        }
        pullFeed(pid, profile, "sync_pull_watched_items_delta", "nuvioCurWatched") { o, delete ->
            if (delete) { if (removeWatched(pid, o)) c.removed++ } else if (applyWatched(pid, o)) c.watched++
        }
        pullFeed(pid, profile, "sync_pull_library_delta", "nuvioCurLibrary") { o, delete ->
            if (delete) { if (removeLibrary(pid, o)) c.removed++ } else if (applyLibrary(pid, o)) c.library++
        }
    }

    private suspend fun pullFeed(pid: Long, profile: Int, fn: String, cursorKey: String, apply: suspend (JSONObject, Boolean) -> Unit) {
        var cursor = settings.acctLongFor(pid, cursorKey)
        while (true) {
            val a = rpcArray(pid, fn, JSONObject().put("p_profile_id", profile).put("p_since_event_id", cursor).put("p_limit", PAGE))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                apply(o, o.optString("operation").equals("delete", true))
                cursor = maxOf(cursor, o.optLong("event_id"))
            }
            settings.setAcctLongFor(pid, cursorKey, cursor)
            if (a.length() < PAGE) break
        }
    }

    // ------------------------------------------------------------------ applying Nuvio's data to Zapp

    private fun episodeLabel(ids: Ids) = if (ids.season != null && ids.episode != null) "S${ids.season} E${ids.episode}" else null

    /** Newest wins: Nuvio's progress replaces Zapp's only if it's more recent. */
    private suspend fun applyProgress(pid: Long, o: JSONObject): Boolean {
        val ids = idsFrom(o.optString("content_id"), o.optString("content_type"), o.intOrNull("season"), o.intOrNull("episode")) ?: return false
        val at = o.optLong("last_watched")
        val position = o.optLong("position")
        val duration = o.optLong("duration")
        val local = dao.progress(pid, ids.videoId)
        if (local != null && local.updatedAt >= at) return false
        val sibling = local ?: dao.progressOfMeta(pid, ids.contentId).maxByOrNull { it.updatedAt }
        dao.saveProgress(
            WatchProgress(
                id = local?.id ?: 0,
                profileId = pid,
                videoId = ids.videoId,
                metaId = ids.contentId,
                type = ids.type,
                name = sibling?.name ?: ids.contentId,
                episodeLabel = episodeLabel(ids),
                poster = sibling?.poster,
                background = local?.background ?: sibling?.background,
                position = position,
                duration = duration,
                watched = duration > 0 && position >= duration * 0.9,
                hidden = false,
                updatedAt = at,
                percent = if (duration > 0) position * 100.0 / duration else null,
            ),
        )
        return true
    }

    /** Removed in Nuvio: removed here too, unless Zapp has newer progress or it's marked watched. */
    private suspend fun removeProgress(pid: Long, o: JSONObject): Boolean {
        val ids = idsFrom(o.optString("content_id"), o.optString("content_type"), o.intOrNull("season"), o.intOrNull("episode")) ?: return false
        val local = dao.progress(pid, ids.videoId) ?: return false
        if (local.watched || local.updatedAt > o.optLong("last_watched") + 1_000) return false
        dao.deleteProgressRow(local.id)
        return true
    }

    private suspend fun applyWatched(pid: Long, o: JSONObject): Boolean {
        val ids = idsFrom(o.optString("content_id"), o.optString("content_type"), o.intOrNull("season"), o.intOrNull("episode")) ?: return false
        val at = o.optLong("watched_at").takeIf { it > 0 } ?: System.currentTimeMillis()
        val local = dao.progress(pid, ids.videoId)
        if (local != null) {
            if (local.watched || local.updatedAt > at) return false
            dao.saveProgress(local.copy(watched = true, updatedAt = at, percent = 100.0))
            return true
        }
        val sibling = dao.progressOfMeta(pid, ids.contentId).maxByOrNull { it.updatedAt }
        dao.saveProgress(
            WatchProgress(
                profileId = pid,
                videoId = ids.videoId,
                metaId = ids.contentId,
                type = ids.type,
                name = o.optString("title").ifBlank { sibling?.name ?: ids.contentId },
                episodeLabel = episodeLabel(ids),
                poster = sibling?.poster,
                background = sibling?.background,
                position = 0,
                duration = 0,
                watched = true,
                hidden = false,
                updatedAt = at,
                percent = 100.0,
            ),
        )
        return true
    }

    /** Unmarked as watched in Nuvio. */
    private suspend fun removeWatched(pid: Long, o: JSONObject): Boolean {
        val ids = idsFrom(o.optString("content_id"), o.optString("content_type"), o.intOrNull("season"), o.intOrNull("episode")) ?: return false
        val local = dao.progress(pid, ids.videoId) ?: return false
        if (!local.watched || local.updatedAt > o.optLong("watched_at") + 1_000) return false
        dao.deleteProgressRow(local.id)
        return true
    }

    private suspend fun applyLibrary(pid: Long, o: JSONObject): Boolean {
        val id = o.optString("content_id")
        val type = o.optString("content_type")
        if (id.isBlank() || (type != "movie" && type != "series")) return false
        if (dao.libraryIds(pid).contains(id)) return false
        dao.addToLibraryList(
            listOf(
                LibraryItem(
                    profileId = pid,
                    metaId = id,
                    type = type,
                    name = o.optString("name").ifBlank { id },
                    poster = o.optString("poster").takeIf { it.startsWith("http") },
                    addedAt = o.optLong("added_at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                ),
            ),
        )
        return true
    }

    private suspend fun removeLibrary(pid: Long, o: JSONObject): Boolean {
        val id = o.optString("content_id")
        if (id.isBlank() || !dao.libraryIds(pid).contains(id)) return false
        dao.removeFromLibrary(pid, id)
        return true
    }
}
