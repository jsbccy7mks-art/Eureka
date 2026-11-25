package com.zapp.tv.data

import java.io.BufferedReader

data class ParsedChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val epgId: String?,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
)

data class M3uResult(val epgUrl: String?, val channels: List<ParsedChannel>)

object M3uParser {
    private val attrRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(reader: BufferedReader): M3uResult {
        var epgUrl: String? = null
        val channels = ArrayList<ParsedChannel>()
        var hasInfo = false
        var name = ""
        var logo: String? = null
        var group: String? = null
        var epgId: String? = null
        var catchupDays = 0
        var catchupSource: String? = null
        var extGroup: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"])
                        ?.substringBefore(',')
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    name = extractName(line)
                        .ifBlank { attrs["tvg-name"].orEmpty() }
                        .ifBlank { "Channel ${channels.size + 1}" }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    // Catch-up: "catchup" (default, append, shift, flussonic), "catchup-days", "catchup-source".
                    val type = attrs["catchup"]?.lowercase()
                    catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.trim()?.toIntOrNull() ?: if (type != null) 7 else 0
                    catchupSource = when (type) {
                        null -> attrs["catchup-source"]?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "append:$it" }
                        "append" -> attrs["catchup-source"]?.let { "append:$it" }
                        "shift", "timeshift" -> "append:?utc={utc}&lutc={lutc}"
                        "flussonic", "fs" -> "flussonic"
                        else -> attrs["catchup-source"]?.let { if (it.startsWith("http")) it else "append:$it" }
                    }
                    if (catchupSource == null) catchupDays = 0
                    hasInfo = true
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substringAfter(':').trim()
                }

                line.startsWith("#") -> Unit

                else -> {
                    if (hasInfo) {
                        val resolvedGroup = group ?: extGroup?.takeIf { it.isNotBlank() }
                        channels.add(ParsedChannel(name, line, logo, resolvedGroup, epgId, catchupDays, catchupSource))
                        hasInfo = false
                        extGroup = null
                    }
                }
            }
        }
        return M3uResult(epgUrl, channels)
    }

    private fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in attrRegex.findAll(line)) {
            map[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return map
    }

    /** Channel name is whatever follows the first comma after the last attribute. */
    private fun extractName(line: String): String {
        val from = attrRegex.findAll(line).lastOrNull()?.range?.last?.plus(1) ?: 0
        val comma = line.indexOf(',', from)
        return if (comma < 0) "" else line.substring(comma + 1).trim()
    }
}
