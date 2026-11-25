package com.zapp.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Streaming XMLTV reader: never holds the whole guide in memory. */
object EpgParser {

    fun parse(
        input: InputStream,
        onChannel: (id: String, names: List<String>) -> Unit,
        onProgramme: (channel: String, start: Long, stop: Long, title: String, description: String?) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            // Many provider guides contain stray "&" characters; relaxed mode tolerates them.
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (e: Exception) {
            // Not supported by this parser; continue in strict mode.
        }
        parser.setInput(input, null)

        var inChannel = false
        var channelId: String? = null
        val names = ArrayList<String>()

        var inProgramme = false
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title: String? = null
        var desc: String? = null

        var target = 0 // 1 display-name, 2 title, 3 desc
        val text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        channelId = parser.getAttributeValue(null, "id")
                        names.clear()
                    }
                    "display-name" -> if (inChannel) {
                        target = 1
                        text.setLength(0)
                    }
                    "programme" -> {
                        inProgramme = true
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = parseTime(parser.getAttributeValue(null, "start")) ?: 0L
                        progStop = parseTime(parser.getAttributeValue(null, "stop")) ?: 0L
                        title = null
                        desc = null
                    }
                    "title" -> if (inProgramme && title == null) {
                        target = 2
                        text.setLength(0)
                    }
                    "desc" -> if (inProgramme && desc == null) {
                        target = 3
                        text.setLength(0)
                    }
                }

                XmlPullParser.TEXT -> if (target != 0) text.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> if (target == 1) {
                        names.add(text.toString().trim())
                        target = 0
                    }
                    "title" -> if (target == 2) {
                        title = text.toString().trim()
                        target = 0
                    }
                    "desc" -> if (target == 3) {
                        desc = text.toString().trim().take(600)
                        target = 0
                    }
                    "channel" -> {
                        val id = channelId
                        if (inChannel && id != null) onChannel(id, names.toList())
                        inChannel = false
                    }
                    "programme" -> {
                        val ch = progChannel
                        if (inProgramme && ch != null && progStart > 0 && progStop > progStart) {
                            onProgramme(ch, progStart, progStop, title.orEmpty(), desc?.takeIf { it.isNotBlank() })
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        }
    }

    /** XMLTV times look like "20261125093000 +0000"; the offset is optional. */
    fun parseTime(value: String?): Long? {
        val s = value?.trim() ?: return null
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        if (i < 12) return null
        val y = s.substring(0, 4).toInt()
        val mo = s.substring(4, 6).toInt()
        val d = s.substring(6, 8).toInt()
        val h = s.substring(8, 10).toInt()
        val mi = s.substring(10, 12).toInt()
        val sec = if (i >= 14) s.substring(12, 14).toInt() else 0
        val rest = s.substring(i).trim()
        var offsetMinutes = 0
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val hh = rest.substring(1, 3).toIntOrNull() ?: 0
            val mm = rest.substring(3, 5).toIntOrNull() ?: 0
            offsetMinutes = (hh * 60 + mm) * (if (rest[0] == '-') -1 else 1)
        }
        val days = daysFromCivil(y, mo, d)
        val utcSeconds = days * 86_400L + h * 3_600L + mi * 60L + sec - offsetMinutes * 60L
        return utcSeconds * 1000L
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private val bracketed = Regex("\\[.*?]|\\(.*?\\)")
    private val countryPrefix = Regex("^[a-z]{2,3}\\s*[:|]\\s*")
    private val qualityTags = Regex("\\b(uhd|fhd|hd|sd|4k|hevc|h265|backup)\\b")
    private val nonAlnum = Regex("[^a-z0-9+]")

    /** Loose channel-name matching for playlists that don't carry guide ids. */
    fun normalizeName(name: String): String {
        var n = name.lowercase()
        n = bracketed.replace(n, " ")
        n = countryPrefix.replace(n.trim(), "")
        n = qualityTags.replace(n, " ")
        return nonAlnum.replace(n, "")
    }
}
