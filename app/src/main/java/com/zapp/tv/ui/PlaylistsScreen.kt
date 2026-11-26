@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.data.TYPE_XTREAM

// ---------------------------------------------------------------------------- phone setup card

@Composable
fun PhoneSetupCard(
    vm: AppViewModel,
    qrSize: Int,
    modifier: Modifier = Modifier,
    path: String = "/",
    caption: String = "Scan with your phone camera",
    /** Opens this tab of the phone page (for example "stremio"). */
    tab: String? = null,
) {
    DisposableEffect(Unit) {
        vm.startSetup()
        onDispose { vm.stopSetup() }
    }
    val port = vm.setupServer.port
    val address = remember(port) { vm.setupServer.address() }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (address != null) {
            QrCode("http://$address$path?t=${vm.setupServer.token}" + (tab?.let { "&tab=$it" } ?: ""), qrSize.dp)
            Spacer(Modifier.height(14.dp))
            Text(caption, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (path == "/") "Or open $address and enter code ${vm.setupServer.token}" else "Same Wi-Fi as the TV",
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
            )
        } else {
            Box(
                Modifier
                    .size((qrSize + 20).dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZappColors.Glass),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (port == 0) "Starting…" else "No network",
                    color = ZappColors.Faint,
                    fontSize = TextSize.Small,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Connect your TV to Wi-Fi or Ethernet to set up from your phone.",
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
            )
        }
    }
}

// ---------------------------------------------------------------------------- welcome

@Composable
fun WelcomeScreen(vm: AppViewModel, onForm: (xtream: Boolean) -> Unit, onSkip: () -> Unit = {}) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstButton.tryRequest() }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZappLogo(30.dp)
            Spacer(Modifier.height(36.dp))
            Text("Add your first playlist", fontSize = TextSize.Hero, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Scan the code and type your provider's details on your phone. It's much quicker than the remote.",
                color = ZappColors.Dim,
                fontSize = TextSize.Body,
                lineHeight = 20.sp,
                modifier = Modifier.widthIn(max = 380.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text("Or use the remote", color = ZappColors.Faint, fontSize = TextSize.Small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZButton("Xtream login", { onForm(true) }, style = ButtonStyle.Secondary, focusRequester = firstButton)
                ZButton("M3U link", { onForm(false) })
                ZButton("Only films and series", onSkip, style = ButtonStyle.Quiet)
            }
        }
        Spacer(Modifier.width(40.dp))
        Panel {
            PhoneSetupCard(vm, qrSize = 190, modifier = Modifier.padding(28.dp).width(230.dp))
        }
    }
}

// ---------------------------------------------------------------------------- sources (in Settings)

@Composable
fun SourcesPane(
    vm: AppViewModel,
    onForm: (xtream: Boolean) -> Unit,
    onWatch: (Long) -> Unit,
    onPhone: () -> Unit = {},
) {
    val playlists by vm.playlists.collectAsState()
    val scope by vm.scope.collectAsState()
    val now = rememberNow(60_000)
    var editingGuide by remember { mutableStateOf<PlaylistItem?>(null) }

    editingGuide?.let { item ->
        GuideLinkDialog(vm, item, onClose = { editingGuide = null })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "add") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZButton("Add on my phone", onPhone, style = ButtonStyle.Primary)
                ZButton("Xtream login", { onForm(true) })
                ZButton("M3U link", { onForm(false) })
            }
        }
        vm.error?.let { message ->
            item(key = "error") { ErrorBanner(message) }
        }
        items(playlists, key = { it.playlist.id }) { item ->
            PlaylistCard(
                item = item,
                current = scope?.playlistId == item.playlist.id,
                now = now,
                onWatch = { onWatch(item.playlist.id) },
                onRefresh = { vm.refresh(item.playlist.id) },
                onGuide = { vm.refreshGuideFor(item.playlist.id) },
                onGuideLink = { editingGuide = item },
                onDelete = { vm.delete(item.playlist.id) },
            )
        }
    }
}

/** Set or change the TV guide link of an existing playlist. */
@Composable
private fun GuideLinkDialog(vm: AppViewModel, item: PlaylistItem, onClose: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(item.playlist.id) {
        link = vm.guideUrl(item.playlist.id)
        loaded = true
        field.tryRequest()
    }
    BackHandler(onBack = onClose)
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .width(480.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("TV guide link", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
            Text(
                if (item.playlist.type == TYPE_XTREAM) {
                    "Leave empty to use your provider's own guide."
                } else {
                    "An XMLTV link (.xml or .xml.gz). Leave empty to use the link inside the playlist, if it has one."
                },
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
            )
            if (loaded) {
                TvTextField(
                    "Guide link",
                    link,
                    { link = it },
                    placeholder = "http://example.com/guide.xml.gz",
                    keyboardType = KeyboardType.Uri,
                    last = true,
                    focusRequester = field,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZButton("Save", { vm.saveGuideUrl(item.playlist.id, link, onClose) }, style = ButtonStyle.Primary)
                ZButton("Cancel", onClose, style = ButtonStyle.Quiet)
            }
        }
    }
}

private fun ago(time: Long, now: Long): String {
    val minutes = ((now - time) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}

@Composable
private fun PlaylistCard(
    item: PlaylistItem,
    current: Boolean,
    now: Long,
    onWatch: () -> Unit,
    onRefresh: () -> Unit,
    onGuide: () -> Unit,
    onGuideLink: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = item.playlist
    var confirmDelete by remember { mutableStateOf(false) }

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(ZappColors.GlassHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.name.trim().take(1).uppercase(), fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            fontSize = TextSize.Heading,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (current) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(ZappColors.GlassHigh)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Watching", fontSize = TextSize.Label, color = ZappColors.Text, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${if (p.type == TYPE_XTREAM) "Xtream login" else "M3U link"}, ${formatCount(item.channelCount)} channels",
                        color = ZappColors.Dim,
                        fontSize = TextSize.Small,
                    )
                    Text(
                        if (p.epgUpdated > 0) "Guide updated ${ago(p.epgUpdated, now)}" else "No guide yet",
                        color = ZappColors.Faint,
                        fontSize = TextSize.Small,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZButton("Watch", onWatch)
                ZButton("Refresh", onRefresh)
                ZButton("Update guide", onGuide)
                ZButton("Guide link", onGuideLink)
                ZButton(
                    text = if (confirmDelete) "Press again to delete" else "Delete",
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                    style = ButtonStyle.Danger,
                    onFocusChange = { if (!it) confirmDelete = false },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- manual form

@Composable
fun PlaylistForm(
    xtream: Boolean,
    vm: AppViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("ts") }
    var epg by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        vm.clearError()
        first.tryRequest()
    }

    fun submit() {
        if (xtream) {
            if (server.isBlank() || user.isBlank() || pass.isBlank()) {
                localError = "Fill in the server address, username and password."
                return
            }
            localError = null
            vm.addXtream(name, server, user, pass, format, epg, onDone)
        } else {
            if (server.isBlank()) {
                localError = "Enter the playlist link first."
                return
            }
            localError = null
            vm.addM3u(name, server, epg, onDone)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(520.dp)
                .fillMaxHeight()
                .padding(vertical = 28.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column {
                    Text(if (xtream) "Xtream login" else "M3U link", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Press OK on a field to type. Up and down move between fields.", color = ZappColors.Dim, fontSize = TextSize.Small)
                }
                TvTextField("Name (optional)", name, { name = it }, placeholder = "My provider", focusRequester = first)
                if (xtream) {
                    TvTextField("Server address", server, { server = it }, placeholder = "http://example.com:8080", keyboardType = KeyboardType.Uri)
                    TvTextField("Username", user, { user = it }, keyboardType = KeyboardType.Ascii)
                    TvTextField("Password", pass, { pass = it }, password = true, keyboardType = KeyboardType.Ascii)
                    Column {
                        Text("Stream type", color = ZappColors.Dim, fontSize = TextSize.Small, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Segmented(listOf("ts" to "TS", "m3u8" to "HLS"), format) { format = it }
                    }
                } else {
                    TvTextField("Playlist link", server, { server = it }, placeholder = "http://example.com/playlist.m3u", keyboardType = KeyboardType.Uri)
                }
                TvTextField(
                    "TV guide link (optional)",
                    epg,
                    { epg = it },
                    placeholder = if (xtream) "Uses your provider's guide" else "Uses the link inside the playlist",
                    keyboardType = KeyboardType.Uri,
                    last = true,
                )
                (localError ?: vm.error)?.let { ErrorBanner(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZButton("Add playlist", { submit() }, style = ButtonStyle.Primary)
                    ZButton("Cancel", onCancel, style = ButtonStyle.Quiet)
                }
            }
        }
    }
}

