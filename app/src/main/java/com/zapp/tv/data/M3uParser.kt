package com.zapp.tv.data

import java.io.BufferedReader

data class ParsedChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val epgId: String?,
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
                    hasInfo = true
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substringAfter(':').trim()
                }

                line.startsWith("#") -> Unit

                else -> {
                    if (hasInfo) {
                        val resolvedGroup = group ?: extGroup?.takeIf { it.isNotBlank() }
                        channels.add(ParsedChannel(name, line, logo, resolvedGroup, epgId))
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
