package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class StremioException(message: String) : Exception(message)

/**
 * Stremio account: signs in with email and password once (only the sign-in key is kept, encrypted)
 * and pulls your Stremio library and continue-watching progress into the first profile.
 */
class StremioSync(private val db: AppDatabase, private val http: OkHttpClient, private val settings: Settings) {
    private val api = "https://api.strem.io/api"
    private val dao = db.mediaDao()
    private val json = "application/json; charset=utf-8".toMediaType()

    val signedIn: Boolean get() = settings.secret("stremioAuth").isNotBlank()

    private fun call(method: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url("$api/$method").post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw StremioException("Stremio sent something unexpected (${r.code}).")
            }
            o.optJSONObject("error")?.let { e ->
                val msg = e.optString("message")
                throw StremioException(
                    when {
                        msg.contains("password", true) || msg.contains("user not found", true) -> "Stremio didn't accept that email and password."
                        msg.contains("session", true) || msg.contains("auth", true) -> "Stremio signed Zapp out. Sign in again from your phone."
                        msg.isNotBlank() -> "Stremio: $msg"
                        else -> "Stremio couldn't do that."
                    },
                )
            }
            return o
        }
    }

    /** Signs in; the password is used once and not kept. */
    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val o = call("login", JSONObject().put("type", "Login").put("email", email.trim()).put("password", password).put("facebook", false))
        val key = o.optJSONObject("result")?.optString("authKey").orEmpty()
        if (key.isBlank()) throw StremioException("Stremio didn't sign in. Check the email and password.")
        settings.setSecret("stremioAuth", key)
        settings.stremioUser = email.trim()
    }

    fun signOut() {
        settings.setSecret("stremioAuth", "")
        settings.stremioUser = ""
    }

    data class Summary(val library: Int, val inProgress: Int)

    /** Library (not removed) into Library; anything part-watched into Continue watching. */
    suspend fun pull(): Summary = withContext(Dispatchers.IO) {
        val key = settings.secret("stremioAuth").ifBlank { throw StremioException("Sign in to Stremio first.") }
        val o = call("datastoreGet", JSONObject().put("authKey", key).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
        val items = o.optJSONArray("result") ?: JSONArray()
        val libraryIds = dao.libraryIds(1).toHashSet()
        val libraryOut = ArrayList<LibraryItem>()
        val progressOut = ArrayList<WatchProgress>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val id = it.optString("_id").ifBlank { continue }
            val type = it.optString("type")
            if (type != "movie" && type != "series") continue
            if (it.optBoolean("removed") && !it.optBoolean("temp")) continue
            val name = it.optString("name")
            val poster = it.optString("poster").takeIf { p -> p.startsWith("http") }
            if (!it.optBoolean("removed") && !it.optBoolean("temp") && id !in libraryIds) {
                libraryOut.add(LibraryItem(profileId = 1, metaId = id, type = type, name = name, poster = poster, addedAt = time(it.optString("_ctime")) ?: System.currentTimeMillis()))
                libraryIds.add(id)
            }
            val state = it.optJSONObject("state") ?: continue
            val offset = state.optLong("timeOffset")
            val duration = state.optLong("duration")
            if (offset < 30_000 || duration <= 0 || offset >= duration * 0.9) continue
            val videoId = state.optString("video_id").ifBlank { id }
            val watchedAt = time(state.optString("lastWatched")) ?: continue
            val existing = dao.progress(1, videoId)
            if (existing != null && existing.updatedAt >= watchedAt) continue
            progressOut.add(
                WatchProgress(
                    id = existing?.id ?: 0,
                    profileId = 1,
                    videoId = videoId,
                    metaId = id,
                    type = type,
                    name = name,
                    episodeLabel = episodeLabel(videoId),
                    poster = poster,
                    background = existing?.background,
                    position = offset,
                    duration = duration,
                    watched = false,
                    hidden = false,
                    updatedAt = watchedAt,
                    percent = offset * 100.0 / duration,
                ),
            )
        }
        if (libraryOut.isNotEmpty()) dao.addToLibraryList(libraryOut)
        if (progressOut.isNotEmpty()) dao.saveProgressList(progressOut)
        settings.stremioLastSync = System.currentTimeMillis()
        Summary(libraryOut.size, progressOut.size)
    }

    private fun episodeLabel(videoId: String): String? {
        val parts = videoId.split(':')
        if (parts.size < 3) return null
        val s = parts[parts.size - 2].toIntOrNull() ?: return null
        val e = parts.last().toIntOrNull() ?: return null
        return "S$s E$e"
    }

    private fun time(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)?.time
            } catch (e: Exception) {
                // Try the next pattern.
            }
        }
        return null
    }
}
