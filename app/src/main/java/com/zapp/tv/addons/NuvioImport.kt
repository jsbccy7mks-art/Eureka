package com.zapp.tv.addons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads the add-on list from Nuvio TV's own "Manage from phone" page, which Nuvio serves on the local
 * network (ports 8080 to 8089) while that screen is open, even with Nuvio in the background.
 * Watch history, progress and library come across through Simkl instead.
 */
class NuvioImport(http: OkHttpClient) {

    data class Found(val address: String, val addons: List<Pair<String, String>>)

    private val quick = http.newBuilder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** Looks on this TV first, then at [extraHost] (another device's address) if given. */
    suspend fun find(extraHost: String?): Found? = withContext(Dispatchers.IO) {
        val candidates = ArrayList<String>()
        val manual = extraHost?.trim()?.removePrefix("http://")?.removePrefix("https://")?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (manual != null) {
            if (manual.contains(':')) candidates.add(manual) else (8080..8089).forEach { candidates.add("$manual:$it") }
        }
        (8080..8089).forEach { candidates.add("127.0.0.1:$it") }
        for (address in candidates) {
            val list = try {
                read("http://$address/api/addons")
            } catch (e: Exception) {
                null
            } ?: continue
            return@withContext Found(address, list)
        }
        null
    }

    private fun read(url: String): List<Pair<String, String>>? {
        quick.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val text = r.body?.string()?.trim().orEmpty()
            val arr = when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> JSONObject(text).optJSONArray("addons") ?: return null
                else -> return null
            }
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").ifBlank { o.optString("transportUrl") }
                if (u.isNotBlank()) out.add(u to o.optString("name").ifBlank { u })
            }
            return out
        }
    }
}
