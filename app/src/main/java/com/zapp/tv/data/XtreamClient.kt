package com.zapp.tv.data

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

const val USER_AGENT = "Zapp/0.4 (Android TV)"

class XtreamClient(private val http: OkHttpClient) {

    data class XtreamCategory(val id: String, val name: String)

    data class XtreamStream(
        val id: String,
        val name: String,
        val icon: String?,
        val epgId: String?,
        val categoryId: String?,
    )

    /** Accepts things like "host:8080", "http://host:8080/", "http://host:8080/c/". */
    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        s = s.substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
        if (s.endsWith("/c")) s = s.removeSuffix("/c")
        return s
    }

    fun authenticate(server: String, user: String, pass: String) {
        val body = getString(apiUrl(server, user, pass, null))
        val info = try {
            JSONObject(body).optJSONObject("user_info")
        } catch (e: Exception) {
            null
        } ?: error("The server did not answer like an Xtream panel. Check the server address.")

        val auth = info.opt("auth")?.toString()
        if (auth != "1" && auth != "true") {
            error("Login rejected. Check the username and password.")
        }
    }

    fun liveCategories(server: String, user: String, pass: String): List<XtreamCategory> =
        readArray<XtreamCategory>(apiUrl(server, user, pass, "get_live_categories")) { r ->
            var id: String? = null
            var name = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "category_id" -> id = r.readAsString()
                    "category_name" -> name = r.readAsString().orEmpty()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id == null) null else XtreamCategory(id, name)
        }

    fun liveStreams(server: String, user: String, pass: String): List<XtreamStream> =
        readArray<XtreamStream>(apiUrl(server, user, pass, "get_live_streams")) { r ->
            var id: String? = null
            var name = ""
            var icon: String? = null
            var epg: String? = null
            var cat: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = r.readAsString()
                    "name" -> name = r.readAsString().orEmpty()
                    "stream_icon" -> icon = r.readAsString()
                    "epg_channel_id" -> epg = r.readAsString()
                    "category_id" -> cat = r.readAsString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id == null) null else XtreamStream(id, name, icon, epg, cat)
        }

    fun streamUrl(server: String, user: String, pass: String, streamId: String, format: String): String =
        livePrefix(server, user, pass) + "$streamId.$format"

    /** The part of every live link that carries the login. */
    fun livePrefix(server: String, user: String, pass: String): String =
        "$server/live/${Uri.encode(user)}/${Uri.encode(pass)}/"

    fun epgUrl(server: String, user: String, pass: String): String =
        "$server/xmltv.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build()
            .toString()

    private fun apiUrl(server: String, user: String, pass: String, action: String?): HttpUrl {
        val base = "$server/player_api.php".toHttpUrl()
        return base.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()
    }

    private fun getString(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The server answered with HTTP ${response.code}.")
            return response.body?.string() ?: error("The server sent an empty answer.")
        }
    }

    /** Streams a JSON array item by item so very large channel lists stay light on memory. */
    private fun <T> readArray(url: HttpUrl, parseItem: (JsonReader) -> T?): List<T> {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The server answered with HTTP ${response.code}.")
            val body = response.body ?: error("The server sent an empty answer.")
            val reader = JsonReader(BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)))
            reader.use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                    error("The server returned no channel list. Check that the account is active.")
                }
                val items = ArrayList<T>()
                r.beginArray()
                while (r.hasNext()) {
                    val item = parseItem(r)
                    if (item != null) items.add(item)
                }
                r.endArray()
                return items
            }
        }
    }

    private fun JsonReader.readAsString(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> {
            skipValue()
            null
        }
    }
}
