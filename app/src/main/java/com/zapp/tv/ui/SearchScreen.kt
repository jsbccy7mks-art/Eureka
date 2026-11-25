@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ProgrammeHit
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    mvm: MediaViewModel,
    onOpenTitle: (com.zapp.tv.addons.MetaPreview) -> Unit,
    onWatch: (Channel) -> Unit,
) {
    val results by vm.searchResults.collectAsState()
    val mediaResults by mvm.searchResults.collectAsState()
    LaunchedEffect(vm.searchText) { mvm.search(vm.searchText) }
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()
    val scope = rememberCoroutineScope()
    val keyFocus = remember { FocusRequester() }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        keyFocus.tryRequest()
    }

    val query = vm.searchText
    fun type(t: String) = vm.setSearch((query + t).take(60))
    fun saveRecent(q: String) = mvm.addRecentSearch(q)
    var menuFor by remember { mutableStateOf<com.zapp.tv.addons.MetaPreview?>(null) }
    menuFor?.let { m -> TitleMenu(mvm, m) { menuFor = null } }

    // Voice: the TV's own speech recogniser, when it has one.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) vm.setSearch(text)
    }
    fun startVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a film, series or channel")
        try {
            voice.launch(intent)
        } catch (e: Exception) {
            vm.showToast("Voice search isn't available on this TV")
        }
    }

    // Kids profiles search films and series only, never live TV.
    val r = results?.let { if (mvm.isKids) it.copy(channels = emptyList(), programmes = emptyList()) else it }

    // Suggestions as you type: titles and channels that match, then your recent searches.
    val suggestions = remember(query, mediaResults, r) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            emptyList()
        } else {
            val titles = mediaResults.orEmpty().flatMap { it.second }.map { it.name }
            val channels = r?.channels.orEmpty().map { it.channel.name }
            val recent = mvm.recentSearches.filter { it.lowercase().startsWith(q) }
            (recent + titles.filter { it.lowercase().startsWith(q) } + titles + channels)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(query.trim(), ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(6)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(top = 26.dp)
            .onPreviewKeyEvent { e ->
                // A real keyboard (or a remote with letters) types straight in.
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val c = e.nativeKeyEvent.unicodeChar
                when {
                    e.key == androidx.compose.ui.input.key.Key.Backspace -> {
                        vm.setSearch(query.dropLast(1))
                        true
                    }
                    c != 0 && (Character.isLetterOrDigit(c) || c == ' '.code) -> {
                        type(c.toChar().toString())
                        true
                    }
                    else -> false
                }
            },
    ) {
        // ------------------------------------------------------------ keyboard side
        Column(
            Modifier
                .width(316.dp)
                .fillMaxHeight()
                .padding(start = 36.dp),
        ) {
            Text("Search", color = ZappColors.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(6.dp))
            // What you've typed, with a blinking caret.
            val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
                .animateFloat(1f, 0f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    query.ifEmpty { "Films, series, channels" },
                    fontSize = if (query.isEmpty()) 16.sp else 22.sp,
                    fontWeight = if (query.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (query.isEmpty()) ZappColors.Faint else ZappColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    Modifier
                        .padding(start = 2.dp)
                        .width(2.dp)
                        .height(24.dp)
                        .graphicsLayer { alpha = blink.value }
                        .background(Brush.verticalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ZappColors.Line),
            )
            Spacer(Modifier.height(14.dp))
            Keyboard(
                firstKey = keyFocus,
                onType = { type(it) },
                onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) type(" ") },
                onDelete = { vm.setSearch(query.dropLast(1)) },
                onClear = { vm.setSearch("") },
                onVoice = { startVoice() },
            )
            Spacer(Modifier.height(16.dp))
            val list = if (query.isBlank()) mvm.recentSearches.take(6) else suggestions
            if (list.isNotEmpty()) {
                Text(
                    if (query.isBlank()) "RECENT" else "SUGGESTIONS",
                    color = ZappColors.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEach { sug ->
                        FocusSurface(
                            onClick = {
                                vm.setSearch(sug)
                                saveRecent(sug)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            focusScale = 1f,
                        ) { focused ->
                            Text(
                                sug,
                                fontSize = 13.sp,
                                color = if (focused) ZappColors.OnFocus else ZappColors.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        // ------------------------------------------------------------ results side
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                query.trim().length < 2 -> Hint("Type with the keyboard, or press the microphone. Results come from your add-ons, playlists and the TV guide.")
                r == null && mediaResults == null -> Hint("Searching…")
                r != null && r.channels.isEmpty() && r.programmes.isEmpty() && mediaResults.isNullOrEmpty() ->
                    Hint("Nothing found for “${r.query}”.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 30.dp, top = 4.dp),
                ) {
                    mediaResults.orEmpty().forEach { (ref, metas) ->
                        item(key = "m:" + ref.key) {
                            val memory = rememberFocusMemory()
                            Column {
                                SectionTitle((if (ref.def.type == "movie") "Films" else "Series") + " · ${ref.addon.manifest.name}")
                                LazyRow(
                                    modifier = Modifier.restoreFocus(memory),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) {
                                    items(metas, key = { it.id }) { meta ->
                                        PosterCard(
                                            meta = meta,
                                            modifier = Modifier.rememberFocus(memory, meta.id),
                                            onFocus = {},
                                            onClick = {
                                                saveRecent(query.trim())
                                                onOpenTitle(meta)
                                            },
                                            onLongClick = { menuFor = meta },
                                            width = cardWidth(1, false),
                                            showTitle = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (r != null && r.channels.isNotEmpty()) {
                        item(key = "channels") {
                            val channelMemory = rememberFocusMemory()
                            Column {
                                SectionTitle("Channels")
                                LazyRow(
                                    modifier = Modifier.restoreFocus(channelMemory),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) {
                                    items(r.channels, key = { it.channel.id }) { row ->
                                        ChannelCard(
                                            modifier = Modifier.rememberFocus(channelMemory, row.channel.id),
                                            row = row,
                                            now = now,
                                            playing = playback.isOn(row.channel),
                                            requester = remember { FocusRequester() },
                                            onFocus = {},
                                            onClick = {
                                                saveRecent(query.trim())
                                                vm.setScope(row.channel.playlistId, null)
                                                onWatch(row.channel)
                                            },
                                            onLongClick = { vm.toggleFavorite(row.channel) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (r != null && r.programmes.isNotEmpty()) {
                        item(key = "programmes-title") { SectionTitle("In the TV guide") }
                        items(r.programmes, key = { "p" + it.programmeId }) { hit ->
                            ProgrammeHitRow(
                                hit = hit,
                                now = now,
                                timeFormat = timeFormat,
                                onClick = {
                                    if (hit.start <= now && hit.stop > now) {
                                        scope.launch {
                                            val channel = vm.channelById(hit.channelId) ?: return@launch
                                            vm.setScope(channel.playlistId, null)
                                            onWatch(channel)
                                        }
                                    } else {
                                        vm.showToast("${hit.title} starts at ${timeFormat.format(hit.start)} on ${hit.channelName}")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** On-screen keyboard: letters and numbers in a grid, then space, delete, clear and voice. */
@Composable
private fun Keyboard(
    firstKey: FocusRequester,
    onType: (String) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onVoice: () -> Unit,
) {
    val keys = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        keys.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { k ->
                    Key(k, Modifier.width(41.dp), if (k == "a") firstKey else null) { onType(k) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Key("space", Modifier.width(87.dp), null, onSpace)
            Key("⌫", Modifier.width(41.dp), null, onDelete)
            Key("clear", Modifier.width(64.dp), null, onClear)
            Key("🎤", Modifier.width(41.dp), null, onVoice)
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, requester: FocusRequester?, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        focusRequester = requester,
        shape = RoundedCornerShape(9.dp),
        focusScale = 1.08f,
        restColor = ZappColors.Glass,
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = if (label.length > 1 && label != "⌫" && label != "🎤") 11.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) ZappColors.OnFocus else ZappColors.Text,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = ZappColors.Dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 10.dp, top = 60.dp, end = 40.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
    )
}

@Composable
private fun ProgrammeHitRow(hit: ProgrammeHit, now: Long, timeFormat: java.text.DateFormat, onClick: () -> Unit) {
    val live = hit.start <= now && hit.stop > now
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .padding(start = 4.dp, end = 34.dp)
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.015f,
        restColor = ZappColors.Glass,
    ) { focused ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hit.icon != null) {
                AsyncImage(
                    model = hit.icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(46.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZappColors.Glass),
                )
            } else {
                Box(Modifier.width(82.dp), contentAlignment = Alignment.Center) {
                    LogoTile(hit.channelLogo, hit.channelName, 40.dp, onLight = focused)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hit.title,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${hit.channelName}, ${timeFormat.format(hit.start)} – ${timeFormat.format(hit.stop)}",
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (live) {
                Spacer(Modifier.width(10.dp))
                LivePill()
            }
        }
    }
}
