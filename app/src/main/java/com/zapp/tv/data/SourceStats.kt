package com.zapp.tv.data

import android.content.Context
import com.zapp.tv.addons.StreamItem
import org.json.JSONObject

/**
 * How sources have done on this box, kept privately here: for each add-on and each release group,
 * how often they started, how long that took, how often they failed, and how often you switched
 * away soon after. Used to nudge reliable ones up and unreliable ones down.
 */
class SourceStats(context: Context) {
    private val prefs = context.getSharedPreferences("zapp_sources", Context.MODE_PRIVATE)
    private var data: JSONObject = runCatching { JSONObject(prefs.getString("stats", "{}") ?: "{}") }.getOrDefault(JSONObject())

    data class Stat(val ok: Int, val fail: Int, val switched: Int, val avgStartMs: Long) {
        val attempts get() = ok + fail + switched
        /** 0..1, starting from an even guess so one bad night doesn't bury a source. */
        val reliability get() = (ok + 1.0) / (attempts + 2.0)
    }

    private fun stat(key: String): Stat {
        val o = data.optJSONObject(key) ?: return Stat(0, 0, 0, 0)
        return Stat(o.optInt("ok"), o.optInt("fail"), o.optInt("sw"), o.optLong("ms"))
    }

    private fun update(key: String, f: (JSONObject) -> Unit) {
        val o = data.optJSONObject(key) ?: JSONObject().also { data.put(key, it) }
        f(o)
        o.put("t", System.currentTimeMillis())
        // Keep it small: the 400 most recently used entries.
        if (data.length() > 400) {
            val oldest = data.keys().asSequence().minByOrNull { data.optJSONObject(it)?.optLong("t") ?: 0 }
            oldest?.let { data.remove(it) }
        }
        prefs.edit().putString("stats", data.toString()).apply()
    }

    private fun keys(s: StreamItem): List<String> = listOfNotNull("a:" + s.addonName, releaseGroup(s)?.let { "g:$it" })

    fun started(s: StreamItem, ms: Long) = keys(s).forEach { k ->
        update(k) { o ->
            val n = o.optInt("ok")
            val avg = o.optLong("ms")
            o.put("ok", n + 1)
            o.put("ms", if (n == 0) ms else (avg * n + ms) / (n + 1))
        }
    }

    fun failed(s: StreamItem) = keys(s).forEach { k -> update(k) { it.put("fail", it.optInt("fail") + 1) } }

    fun switchedAway(s: StreamItem) = keys(s).forEach { k -> update(k) { it.put("sw", it.optInt("sw") + 1) } }

    fun reset() {
        data = JSONObject()
        prefs.edit().remove("stats").apply()
    }

    /** The less reliable of the source's add-on and release group (null until it's been tried a few times). */
    fun of(s: StreamItem): Stat? = keys(s).map { stat(it) }.filter { it.attempts >= 3 }.minByOrNull { it.reliability }

    /**
     * A nudge for sorting: up to about ±150, so reliable sources rise within the same quality but a
     * 1080p source never jumps above 4K just for that; very unreliable ones sink further.
     */
    fun bonus(s: StreamItem): Int {
        val st = of(s) ?: return 0
        val r = st.reliability
        var b = ((r - 0.5) * 300).toInt()
        if (r < 0.35) b -= 400
        if (st.avgStartMs in 1..3_000) b += 30
        return b
    }

    /** "Reliable" / "Often fails" for the sources list, once there's enough to go on. */
    fun note(s: StreamItem): String? {
        val st = of(s) ?: return null
        return when {
            st.reliability >= 0.8 -> "Reliable"
            st.reliability < 0.4 -> "Often fails"
            else -> null
        }
    }

    companion object {
        /** The release group from a file name or description: "…x264-RARBG.mkv" gives "RARBG". */
        fun releaseGroup(s: StreamItem): String? {
            val text = s.filename ?: s.description.lineSequence().firstOrNull { '.' in it || '-' in it } ?: return null
            val name = text.substringAfterLast('/').substringBeforeLast('.').trim()
            val g = Regex("-([A-Za-z0-9]{2,20})(?:\\[[^]]*])?$").find(name)?.groupValues?.get(1) ?: return null
            return g.uppercase().takeIf { it !in setOf("DL", "RIP", "HD", "SDR", "HDR", "X264", "X265", "H264", "H265", "HEVC") }
        }
    }
}
