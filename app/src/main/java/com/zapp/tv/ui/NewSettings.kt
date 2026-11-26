package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Settings added recently get a small dot until you've moved over them, and so do the section and
 * group they're in, so nothing new gets missed. Add each new setting here as it's made.
 */
object NewSettings {
    /** A new setting: its title (or "group:<title>" for a whole new group), its section, and its group. */
    private data class Entry(val key: String, val section: String, val group: String? = null)

    private val entries = listOf(
        Entry("Pause and rewind live TV", "Live"),
        Entry("Match frame rate", "Playback", "Display matching"),
        Entry("Match resolution", "Playback", "Display matching"),
        Entry("Get ready while I browse", "Playback", "Starting films and series"),
        Entry("Ready to play only", "Playback", "Starting films and series"),
        Entry("Sources that learn", "Playback", "Starting films and series"),
        Entry("Suggest something when a film ends", "Playback", "Starting films and series"),
        Entry("Play the next episode automatically", "Playback", "Starting films and series"),
        Entry("Films and series: subtitles", "Subtitles"),
        Entry("Live TV: subtitles", "Subtitles"),
        Entry("True black", "Appearance", "Look"),
        Entry("Title lettering", "Appearance", "Look"),
        Entry("Card style", "Appearance", "Look"),
        Entry("Names under posters", "Appearance", "Look"),
        Entry("Glow strength", "Appearance", "Look"),
        Entry("Focus outline", "Appearance", "Focus"),
        Entry("Pulsing glow", "Appearance", "Focus"),
        Entry("Trailer quality", "Appearance", "Trailers and motion"),
        Entry("Collection theme music", "Appearance", "Trailers and motion"),
        Entry("Theme music volume", "Appearance", "Trailers and motion"),
        Entry("Startup sound", "Appearance", "Trailers and motion"),
        Entry("Rate films and series", "HomeLayout"),
        Entry("Collections in the sidebar", "HomeLayout"),
        Entry("group:Collections layout", "HomeLayout", "Collections layout"),
        Entry("Test trailers", "About"),
        Entry("group:Nuvio", "Accounts", "Nuvio"),
        Entry("group:MDBList", "Accounts", "MDBList"),
    )

    private var settings: com.zapp.tv.data.Settings? = null
    private val seen = mutableStateOf<Set<String>>(emptySet())

    fun init(s: com.zapp.tv.data.Settings) {
        if (settings != null) return
        settings = s
        seen.value = s.seenSettings.split('\n').filter { it.isNotBlank() }.toSet()
    }

    private fun unseen() = entries.filter { it.key !in seen.value }

    fun isNew(title: String): Boolean = entries.any { it.key == title } && title !in seen.value
    fun sectionHasNew(section: String): Boolean = unseen().any { it.section == section }
    fun groupHasNew(group: String): Boolean = unseen().any { it.group == group }

    fun markSeen(key: String) {
        if (entries.none { it.key == key } || key in seen.value) return
        seen.value = seen.value + key
        settings?.seenSettings = seen.value.joinToString("\n")
    }
}

/** The little "new" dot. */
@Composable
fun NewDot(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.size(7.dp).clip(CircleShape).background(ZappColors.Brand2))
}
