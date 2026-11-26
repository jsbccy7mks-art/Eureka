package com.zapp.tv.data

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** Dislike, Like or Love. */
enum class Rating(val value: Int, val simkl: Int) { Dislike(-1, 3), Like(1, 8), Love(2, 10) }

data class RatedTitle(val metaId: String, val type: String, val name: String, val poster: String?, val rating: Rating, val at: Long)

/** Your ratings, per profile, kept on the TV (and sent to Simkl when connected). */
class Ratings(private val settings: Settings) {
    /** The profile's ratings, by title id; the UI reads this directly. */
    val byId = mutableStateMapOf<String, RatedTitle>()
    private var loadedFor = -1L

    fun load() {
        val pid = settings.profileId
        if (pid == loadedFor) return
        loadedFor = pid
        byId.clear()
        runCatching {
            val o = JSONObject(settings.ratingsJson.ifBlank { "{}" })
            o.keys().forEach { k ->
                val r = o.getJSONObject(k)
                val rating = Rating.entries.firstOrNull { it.value == r.optInt("r") } ?: return@forEach
                byId[k] = RatedTitle(k, r.optString("t"), r.optString("n"), r.optString("p").takeIf { it.isNotBlank() }, rating, r.optLong("at"))
            }
        }
    }

    fun get(id: String): Rating? {
        load()
        return byId[id]?.rating
    }

    fun set(id: String, type: String, name: String, poster: String?, rating: Rating?) {
        load()
        if (rating == null) byId.remove(id) else byId[id] = RatedTitle(id, type, name, poster, rating, System.currentTimeMillis())
        val o = JSONObject()
        byId.values.forEach { t -> o.put(t.metaId, JSONObject().put("r", t.rating.value).put("t", t.type).put("n", t.name).put("p", t.poster ?: "").put("at", t.at)) }
        settings.ratingsJson = o.toString()
    }

    /** Titles you love (reads the live map, so screens update). */
    fun loved(): Set<String> {
        load()
        return byId.filterValues { it.rating == Rating.Love }.keys.toSet()
    }

    fun all(): List<RatedTitle> {
        load()
        return byId.values.sortedByDescending { it.at }
    }
}
