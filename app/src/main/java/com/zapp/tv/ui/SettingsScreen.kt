@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val UPDATE_LINK = "github.com/jsbccy7mks-art/Eureka/releases/latest/download/zapp-tv.apk"

private enum class SettingsSection(val label: String) {
    Sources("Playlists"),
    Addons("Add-ons"),
    HomeLayout("Home screen"),
    Live("Live TV and guide"),
    Playback("Playback"),
    Subtitles("Subtitles"),
    About("Updates and about"),
}

@Composable
fun SettingsScreen(
    mvm: MediaViewModel,
    initialSection: String?,
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    onForm: (xtream: Boolean) -> Unit,
    onWatchPlaylist: (Long) -> Unit,
) {
    var section by rememberSaveable {
        mutableStateOf(SettingsSection.entries.firstOrNull { it.name == initialSection } ?: SettingsSection.Sources)
    }
    var focusedSection by remember { mutableStateOf<SettingsSection?>(null) }
    val firstFocus = remember { FocusRequester() }
    val sectionMemory = rememberFocusMemory()
    var handledTick by remember { mutableIntStateOf(focusTick) }

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        firstFocus.tryRequest()
    }
    LaunchedEffect(focusedSection) {
        val s = focusedSection ?: return@LaunchedEffect
        delay(200)
        section = s
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 36.dp, top = 28.dp, bottom = 20.dp),
    ) {
        Column(Modifier.width(210.dp)) {
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                modifier = Modifier.restoreFocus(sectionMemory),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(SettingsSection.entries, key = { it.name }) { s ->
                    FocusSurface(
                        onClick = { section = s },
                        modifier = Modifier
                            .rememberFocus(sectionMemory, s.name)
                            .fillMaxWidth(),
                        selected = s == section,
                        focusRequester = if (s == section) firstFocus else null,
                        onFocusChange = { if (it) focusedSection = s },
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1.03f,
                    ) { focused ->
                        Text(
                            s.label,
                            color = when {
                                focused -> ZappColors.OnFocus
                                s == section -> ZappColors.Text
                                else -> ZappColors.Dim
                            },
                            fontSize = 14.sp,
                            fontWeight = if (s == section || focused) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(28.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (section) {
                SettingsSection.Sources -> SourcesPane(vm, onForm, onWatchPlaylist)
                SettingsSection.Addons -> AddonsPane(vm, mvm)
                SettingsSection.HomeLayout -> HomeLayoutPane(mvm)
                SettingsSection.Live -> LiveSettingsPane(vm, playback)
                SettingsSection.Playback -> PlaybackSettingsPane(vm)
                SettingsSection.Subtitles -> SubtitleSettingsPane(vm)
                SettingsSection.About -> AboutPane(vm)
            }
        }
    }
}

@Composable
private fun LiveSettingsPane(vm: AppViewModel, playback: Playback) {
    val intervals = listOf(0, 6, 12, 24)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingRow(
            title = "Preview channels while browsing",
            body = "Plays the channel you rest on in the corner of Live TV and in the Home hero.",
            value = SettingValue.Toggle(vm.settings.preview),
            onClick = {
                vm.settings.preview = !vm.settings.preview
                if (!vm.settings.preview) playback.stop()
            },
        )
        SettingRow(
            title = "Update the guide automatically",
            body = "How often the TV guide is downloaded again while the app is open.",
            value = SettingValue.Choice(
                when (vm.settings.guideHours) {
                    0 -> "Off"
                    else -> "Every ${vm.settings.guideHours} hours"
                },
            ),
            onClick = {
                val i = intervals.indexOf(vm.settings.guideHours)
                vm.settings.guideHours = intervals[(i + 1).mod(intervals.size)]
            },
        )
        SettingRow(
            title = "Update guide now",
            body = "Downloads the latest guide for every playlist.",
            value = SettingValue.Action,
            onClick = { vm.refreshGuideAll() },
        )
        SettingRow(
            title = "Refresh all playlists",
            body = "Reloads channel lists from your providers. Favourites and history are kept.",
            value = SettingValue.Action,
            onClick = { vm.refreshAll() },
        )
    }
}

/** Languages offered for audio and subtitles; "" follows the TV's own language. */
private val Languages = listOf("", "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "ar", "sv", "da", "no", "el", "ro", "hi")

private fun languageLabel(code: String): String =
    if (code.isEmpty()) "Same as the TV" else Playback.languageName(code) ?: code

private fun next(code: String): String = Languages[(Languages.indexOf(code) + 1).mod(Languages.size)]

@Composable
private fun PlaybackSettingsPane(vm: AppViewModel) {
    val s = vm.settings
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingRow(
            title = "Preferred audio language",
            body = "Used when a channel offers more than one language. You can still switch in the player.",
            value = SettingValue.Choice(languageLabel(s.audioLanguage)),
            onClick = { s.audioLanguage = next(s.audioLanguage) },
        )
        SettingRow(
            title = "Play the first source automatically",
            body = "For films and series: start the first suitable source (following your add-on order) instead of showing the list.",
            value = SettingValue.Toggle(s.autoPlayFirst),
            onClick = { s.autoPlayFirst = !s.autoPlayFirst },
        )
        SettingRow(
            title = "Play the next episode automatically",
            body = "Counts down from 10 at the end of an episode, then plays the next one.",
            value = SettingValue.Toggle(s.autoNextEpisode),
            onClick = { s.autoNextEpisode = !s.autoNextEpisode },
        )
        SettingRow(
            title = "Bigger buffer",
            body = "Holds more video in reserve for unsteady connections, at the cost of slower channel changes. " +
                "Takes effect next time Zapp starts.",
            value = SettingValue.Toggle(s.largeBuffer),
            onClick = { s.largeBuffer = !s.largeBuffer },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "In the player: OK opens the controls (audio, subtitles, stream info), Left opens the channel list, " +
                "Right shows what's on, and Up or Down changes channel.",
            color = ZappColors.Faint,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun SubtitleSettingsPane(vm: AppViewModel) {
    val s = vm.settings
    val sizes = listOf("Small", "Medium", "Large", "Extra large")
    val styles = listOf("White with shadow", "White on dark box", "Yellow with shadow")
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Live preview of the chosen look.
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF3A4A6B), Color(0xFF6B5A3A), Color(0xFF2A3A3A)),
                    ),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.padding(bottom = 12.dp)) {
                SubtitleText(
                    "This is how subtitles will look.",
                    when (s.subtitleSize) {
                        0 -> 14.sp
                        2 -> 21.sp
                        3 -> 25.sp
                        else -> 17.sp
                    },
                    s.subtitleStyle,
                )
            }
        }
        SettingRow(
            title = "Subtitles",
            body = "Turn subtitles on automatically when a channel has them. You can always switch in the player.",
            value = SettingValue.Choice(if (s.subtitlesOn) "On when available" else "Off"),
            onClick = { s.subtitlesOn = !s.subtitlesOn },
        )
        SettingRow(
            title = "Subtitle language",
            body = "Preferred language when subtitles turn on automatically.",
            value = SettingValue.Choice(languageLabel(s.subtitleLanguage)),
            onClick = { s.subtitleLanguage = next(s.subtitleLanguage) },
        )
        SettingRow(
            title = "Size",
            body = "Applies to text subtitles and captions.",
            value = SettingValue.Choice(sizes[s.subtitleSize.coerceIn(0, 3)]),
            onClick = { s.subtitleSize = (s.subtitleSize + 1).mod(sizes.size) },
        )
        SettingRow(
            title = "Style",
            body = "How the text stands out from the picture.",
            value = SettingValue.Choice(styles[s.subtitleStyle.coerceIn(0, 2)]),
            onClick = { s.subtitleStyle = (s.subtitleStyle + 1).mod(styles.size) },
        )
        SettingRow(
            title = "Position",
            body = "Raise subtitles if they're hidden by the edge of your TV.",
            value = SettingValue.Choice(if (s.subtitleRaised) "Raised" else "Bottom"),
            onClick = { s.subtitleRaised = !s.subtitleRaised },
        )
        Text(
            "Picture-based subtitles (DVB) keep the look the broadcaster gave them.",
            color = ZappColors.Faint,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AboutPane(vm: AppViewModel) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Zapp TV", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Version ${version ?: "unknown"}, build ${vm.currentBuild}", color = ZappColors.Dim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        val u = vm.update
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow(
                title = "Check for updates",
                body = when (u) {
                    UpdateState.Idle -> "Looks for a newer version of Zapp."
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "You have the latest version."
                    is UpdateState.Available -> "Build ${u.release.build} is ready to install."
                    is UpdateState.Downloading -> "Downloading build ${u.release.build}…"
                    is UpdateState.Failed -> u.message
                },
                value = SettingValue.Action,
                onClick = { vm.checkForUpdates(manual = true) },
            )
            val release = when (u) {
                is UpdateState.Available -> u.release
                is UpdateState.Downloading -> u.release
                is UpdateState.Failed -> u.release
                else -> null
            }
            if (release != null) {
                SettingRow(
                    title = if (u is UpdateState.Downloading) "Downloading ${(u.progress * 100).toInt()}%" else "Install build ${release.build}",
                    body = "Downloads the update and opens the installer. Your playlists and settings are kept.",
                    value = SettingValue.Action,
                    onClick = { vm.installUpdate() },
                )
                if (u is UpdateState.Downloading) {
                    ProgressLine(u.progress, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                }
            }
        }
        val crash = vm.lastCrash
        if (crash != null) {
            Spacer(Modifier.height(18.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhoneSetupCard(vm, qrSize = 110, modifier = Modifier.width(150.dp), path = "/crash", caption = "Open on your phone")
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Zapp closed unexpectedly", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            crash.lineSequence().drop(5).firstOrNull { it.isNotBlank() }?.take(160) ?: "",
                            color = ZappColors.Dim,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Scan the code to open the full report on your phone, then copy it into your chat about the app.",
                            color = ZappColors.Faint,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        ZButton("Clear report", { vm.clearCrash() }, style = ButtonStyle.Quiet)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Updating another way", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "If the built-in updater can't install on this TV, open the Downloader app and enter this address. " +
                        "It always points to the newest version.",
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(UPDATE_LINK, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ZappColors.Signal)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Zapp TV doesn't include any channels, playlists or accounts. Add your own from Sources.",
            color = ZappColors.Faint,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AddonsPane(vm: AppViewModel, mvm: MediaViewModel) {
    val addons by mvm.addons.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Long?>(null) }

    if (adding) AddAddonDialog(mvm) { adding = false }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item(key = "add") {
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhoneSetupCard(vm, qrSize = 120, modifier = Modifier.width(170.dp), caption = "Paste a link on your phone")
                    Spacer(Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Add a Stremio add-on", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scan the code and choose Add-on, then paste the add-on's install link (it ends in manifest.json). " +
                                "Zapp doesn't include any add-ons; you choose your own.",
                            color = ZappColors.Dim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZButton("Type a link", { adding = true })
                            if (addons.isNotEmpty()) ZButton("Refresh add-ons", { mvm.refreshAddons() }, style = ButtonStyle.Quiet)
                        }
                    }
                }
            }
        }
        mvm.message?.let { m -> item(key = "msg") { ErrorBanner(m) } }
        if (addons.none { it.manifest.id == "com.linvo.cinemeta" }) {
            item(key = "cinemeta") {
                SettingRow(
                    title = "Suggested: Cinemeta",
                    body = "Stremio's own film and series information (posters, descriptions, episodes). It has no video sources.",
                    value = SettingValue.Choice("Install"),
                    onClick = { mvm.addAddon(CINEMETA_URL) },
                )
            }
        }
        items(addons.size) { i ->
            val a = addons[i]
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ZappColors.GlassHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (a.manifest.logo != null) {
                                coil.compose.AsyncImage(model = a.manifest.logo, contentDescription = null, modifier = Modifier.size(30.dp))
                            } else {
                                Text(a.manifest.name.take(1).uppercase(), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.manifest.name + if (!a.addon.enabled) " (off)" else "",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (a.addon.enabled) ZappColors.Text else ZappColors.Faint,
                            )
                            val offers = a.manifest.resources.map { it.name }.mapNotNull {
                                when (it) {
                                    "catalog" -> "catalogues"
                                    "meta" -> "details"
                                    "stream" -> "sources"
                                    "subtitles" -> "subtitles"
                                    else -> null
                                }
                            }
                            Text(
                                "Version ${a.manifest.version}" + if (offers.isNotEmpty()) ", offers " + offers.joinToString(", ") else "",
                                color = ZappColors.Dim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZButton(if (a.addon.enabled) "Turn off" else "Turn on", { mvm.setEnabled(a.addon.id, !a.addon.enabled) })
                        if (i > 0) ZButton("Move up", { mvm.move(a.addon.id, up = true) })
                        if (i < addons.size - 1) ZButton("Move down", { mvm.move(a.addon.id, up = false) })
                        ZButton(
                            if (confirmRemove == a.addon.id) "Press again to remove" else "Remove",
                            {
                                if (confirmRemove == a.addon.id) {
                                    mvm.removeAddon(a.addon.id)
                                    confirmRemove = null
                                } else {
                                    confirmRemove = a.addon.id
                                }
                            },
                            style = ButtonStyle.Danger,
                            onFocusChange = { if (!it && confirmRemove == a.addon.id) confirmRemove = null },
                        )
                    }
                }
            }
        }
        if (addons.size > 1) {
            item(key = "order-note") {
                Text(
                    "Order matters: details come from the first add-on that has them, and sources are listed in this order.",
                    color = ZappColors.Faint,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun AddAddonDialog(mvm: MediaViewModel, onClose: () -> Unit) {
    var link by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.tryRequest() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .width(500.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ZappColors.Panel)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add-on link", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TvTextField(
                "Install link",
                link,
                { link = it },
                placeholder = "https://example.com/manifest.json",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                last = true,
                focusRequester = field,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZButton("Install", { if (link.isNotBlank()) mvm.addAddon(link, onClose) }, style = ButtonStyle.Primary)
                ZButton("Cancel", onClose, style = ButtonStyle.Quiet)
            }
        }
    }
}

@Composable
private fun HomeLayoutPane(mvm: MediaViewModel) {
    val addons by mvm.addons.collectAsState()
    val catalogs by androidx.compose.runtime.produceState(emptyList<com.zapp.tv.addons.CatalogRef>(), addons) { value = mvm.catalogs(null) }
    val rows = remember(catalogs, mvm.settings.homeRowsJson) { mvm.homeLayout(catalogs) }

    fun update(newRows: List<HomeRowSetting>) = mvm.saveLayout(newRows)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item(key = "intro") {
            Text(
                "Choose which rows appear on Home and in what order. Rows with nothing in them stay hidden.",
                color = ZappColors.Dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        items(rows.size, key = { rows[it].id }) { i ->
            val r = rows[i]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    SettingRow(
                        title = r.title,
                        body = if (r.on) "Shown on Home" else "Hidden",
                        value = SettingValue.Toggle(r.on),
                        onClick = { update(rows.map { if (it.id == r.id) it.copy(on = !it.on) else it }) },
                    )
                }
                if (i > 0) {
                    ZButton("Up", {
                        val m = rows.toMutableList()
                        m.add(i - 1, m.removeAt(i))
                        update(m)
                    })
                }
                if (i < rows.size - 1) {
                    ZButton("Down", {
                        val m = rows.toMutableList()
                        m.add(i + 1, m.removeAt(i))
                        update(m)
                    })
                }
            }
        }
    }
}

sealed interface SettingValue {
    data class Toggle(val on: Boolean) : SettingValue
    data class Choice(val label: String) : SettingValue
    data object Action : SettingValue
}

@Composable
fun SettingRow(
    title: String,
    body: String,
    value: SettingValue,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        shape = RoundedCornerShape(14.dp),
        focusScale = 1.015f,
        restColor = ZappColors.Glass,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(body, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = 12.sp)
            }
            Spacer(Modifier.width(16.dp))
            when (value) {
                is SettingValue.Toggle -> Switch(value.on, focused)
                is SettingValue.Choice -> Text(
                    value.label,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Signal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                SettingValue.Action -> ChevronGlyph(if (focused) ZappColors.OnFocus else ZappColors.Faint, 12.dp, down = false)
            }
        }
    }
}

@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    Box(
        Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    checked -> ZappColors.Brand2
                    focused -> Color(0xFFCBD2E0)
                    else -> ZappColors.Line
                },
            )
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
    }
}
