package com.zapp.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Set to show "What's new" over everything (after an update, or from Settings). */
object WhatsNewState {
    var notes by androidx.compose.runtime.mutableStateOf<List<ReleaseNotes>?>(null)
}

/** One version's notes from the changelog that ships inside the app. */
data class ReleaseNotes(val version: String, val lines: List<String>)

object Changelog {
    /** Reads the bundled changelog: each "## 1.2.3" section, its lines joined back into whole bullets. */
    fun read(context: android.content.Context): List<ReleaseNotes> {
        val text = try {
            context.assets.open("changelog.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<ReleaseNotes>()
        var version: String? = null
        var lines = ArrayList<String>()
        fun flush() { version?.let { out.add(ReleaseNotes(it, lines)) } }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    flush()
                    version = line.removePrefix("## ").trim()
                    lines = ArrayList()
                }
                version == null || line.isBlank() -> {}
                line.trimStart().startsWith("- ") -> lines.add((if (line.startsWith("  ")) "  " else "") + line.trimStart())
                line.startsWith("**") -> lines.add(line)
                lines.isNotEmpty() -> lines[lines.size - 1] = lines.last() + " " + line.trim()
                else -> lines.add(line.trim())
            }
        }
        flush()
        return out
    }

    /** Notes for versions newer than [since], up to and including [current] (newest first). */
    fun between(all: List<ReleaseNotes>, since: String, current: String): List<ReleaseNotes> {
        fun parts(v: String) = v.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        fun cmp(a: String, b: String): Int {
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
        return all.filter { cmp(it.version, since) > 0 && cmp(it.version, current) <= 0 }.take(5)
    }
}

/**
 * "What's new": shown once after an update (and from Settings, Updates and about). Up and Down
 * scroll; OK or Back closes it.
 */
@Composable
fun WhatsNewScreen(notes: List<ReleaseNotes>, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        focus.tryRequest()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Bg)
            .onPreviewKeyEvent { e ->
                if (e.key in OkKeys) {
                    if (e.type == KeyEventType.KeyUp) onDone()
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(260f) }; true }
                    Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-260f) }; true }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        AmbientGlow(0.8f)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 96.dp, vertical = 48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("WHAT'S NEW", color = ZappColors.Brand2, fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp)
                    MovieTitle("Axon ${notes.firstOrNull()?.version.orEmpty()}", 34.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Text("OK to close   ·   ↑ ↓ to scroll", color = ZappColors.Faint, fontSize = TextSize.Small)
            }
            notes.forEachIndexed { i, n ->
                Spacer(Modifier.height(if (i == 0) 26.dp else 34.dp))
                if (notes.size > 1) {
                    Text("Version ${n.version}", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, color = ZappColors.Text)
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    n.lines.forEach { l ->
                        when {
                            l.startsWith("**") -> {
                                Spacer(Modifier.height(6.dp))
                                Text(l.trim('*'), color = ZappColors.Brand2, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                            }
                            else -> {
                                val sub = l.startsWith("  ")
                                Row(Modifier.padding(start = if (sub) 22.dp else 0.dp)) {
                                    Text("•", color = ZappColors.Brand2, fontSize = TextSize.Body)
                                    Spacer(Modifier.width(10.dp))
                                    Text(l.trim().removePrefix("- "), color = Color(0xFFD6D9E2), fontSize = TextSize.Body, lineHeight = 21.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** The notes to show now, if Zapp has been updated since it last ran (nothing on a fresh install). */
@Composable
fun rememberPendingNotes(settings: com.zapp.tv.data.Settings): List<ReleaseNotes> {
    val context = LocalContext.current
    return remember {
        val current = com.zapp.tv.BuildConfig.VERSION_NAME
        val last = settings.lastVersionSeen
        if (last == current) {
            emptyList()
        } else if (last.isBlank()) {
            settings.lastVersionSeen = current
            emptyList()
        } else {
            Changelog.between(Changelog.read(context), last, current).also { if (it.isEmpty()) settings.lastVersionSeen = current }
        }
    }
}
