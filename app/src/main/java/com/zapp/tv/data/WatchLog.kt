package com.zapp.tv.data

import android.content.Context

/**
 * How long things were actually on screen, month by month, for Your month / Your year: films and
 * episodes, live TV, and each live channel. Kept on this TV only, as simple running totals.
 */
object WatchLog {
    private const val FILE = "watch_log"

    private fun month(at: Long = System.currentTimeMillis()): String {
        val d = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    /** Adds [seconds] of watching now: [kind] is "vod" or "live"; [channel] the live channel's name. */
    fun add(context: Context, kind: String, channel: String?, seconds: Int) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val m = month()
        val e = p.edit()
        e.putLong("$m|$kind", p.getLong("$m|$kind", 0) + seconds)
        if (kind == "live" && !channel.isNullOrBlank()) {
            val k = "$m|ch|${channel.take(60)}"
            e.putLong(k, p.getLong(k, 0) + seconds)
        }
        e.apply()
    }

    /** Seconds watched in the given months ("2026-11"), by kind. */
    fun seconds(context: Context, months: List<String>, kind: String): Long {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return months.sumOf { p.getLong("$it|$kind", 0) }
    }

    /** Live channels watched most in the given months: name to seconds, most first. */
    fun channels(context: Context, months: List<String>): List<Pair<String, Long>> {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val out = HashMap<String, Long>()
        p.all.forEach { (k, v) ->
            val parts = k.split('|', limit = 3)
            if (parts.size == 3 && parts[1] == "ch" && parts[0] in months) out[parts[2]] = (out[parts[2]] ?: 0) + ((v as? Long) ?: 0)
        }
        return out.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
