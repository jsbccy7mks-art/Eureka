@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
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
    Phone("Phone setup"),
    Sources("Playlists"),
    Addons("Add-ons"),
    Appearance("Appearance"),
    Profiles("Profiles"),
    HomeLayout("Home screen"),
    Live("Live TV and guide"),
    Playback("Playback"),
    Subtitles("Subtitles"),
    Accounts("Accounts and sync"),
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
    // The phone page is in one place only (Phone setup); other sections send you there, on the right tab.
    var phoneTab by remember { mutableStateOf<String?>(null) }
    fun openPhone(tab: String) {
        phoneTab = tab
        section = SettingsSection.Phone
    }
    val firstFocus = remember { FocusRequester() }
    val sectionMemory = rememberFocusMemory()
    var handledTick by remember { mutableIntStateOf(focusTick) }

    var hasFocusInside by remember { mutableStateOf(false) }
    var sectionsHaveFocus by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = hasFocusInside && !sectionsHaveFocus) { firstFocus.tryRequest() }
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        // The list may still be settling in; keep trying briefly so focus leaves the sidebar.
        repeat(8) {
            if (hasFocusInside) return@LaunchedEffect
            firstFocus.tryRequest()
            delay(60)
            if (!hasFocusInside) firstItem.tryRequest()
            delay(60)
        }
    }
    LaunchedEffect(focusedSection) {
        val s = focusedSection ?: return@LaunchedEffect
        delay(200)
        if (s != section) phoneTab = null
        section = s
    }

    Row(
        Modifier
            .fillMaxSize()
            .onFocusChanged { hasFocusInside = it.hasFocus }
            .padding(start = 28.dp, end = 36.dp, top = 28.dp, bottom = 20.dp),
    ) {
        Column(Modifier.width(210.dp)) {
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(18.dp))
            LazyColumn(
                modifier = Modifier
                    .onFocusChanged { sectionsHaveFocus = it.hasFocus }
                    .restoreFocus(sectionMemory),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(SettingsSection.entries, key = { it.name }) { s ->
                    FocusSurface(
                        onClick = { section = s },
                        modifier = Modifier
                            .rememberFocus(sectionMemory, s.name)
                            .fillMaxWidth(),
                        selected = s == section,
                        focusRequester = if (s == section) firstFocus else if (s == SettingsSection.entries.first()) firstItem else null,
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
                SettingsSection.Phone -> PhoneSetupPane(vm, phoneTab)
                SettingsSection.Sources -> SourcesPane(vm, onForm, onWatchPlaylist, onPhone = { openPhone("xtream") })
                SettingsSection.Addons -> AddonsPane(vm, mvm) { openPhone("addon") }
                SettingsSection.Appearance -> AppearancePane(vm)
                SettingsSection.Profiles -> ProfilesPane(mvm)
                SettingsSection.HomeLayout -> HomeLayoutPane(mvm)
                SettingsSection.Live -> LiveSettingsPane(vm, playback)
                SettingsSection.Playback -> PlaybackSettingsPane(vm)
                SettingsSection.Subtitles -> SubtitleSettingsPane(vm)
                SettingsSection.Accounts -> AccountsPane(vm, mvm) { openPhone(it) }
                SettingsSection.About -> AboutPane(vm) { openPhone("help") }
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
        SettingsGroup("Display matching", "Frame rate and resolution switching") {
            SettingRow(
                title = "Match frame rate",
                body = "Switches your TV to the film's frame rate (24, 25, 50, 60) while it plays, so motion is smooth. The screen may go dark for a moment as it switches.",
                value = SettingValue.Toggle(s.matchFrameRate),
                onClick = { s.matchFrameRate = !s.matchFrameRate },
            )
            SettingRow(
                title = "Match resolution",
                body = "Also switches to the video's resolution (1080p, 4K). Off lets your TV upscale, which is often better and quicker.",
                value = SettingValue.Toggle(s.matchResolution),
                onClick = { s.matchResolution = !s.matchResolution },
            )
        }
        SettingsGroup("Starting films and series", "Autoplay, which sources, quality", startOpen = true) {
        SettingRow(
            title = "Autoplay",
            body = "For films and series: start the best source straight away instead of showing the list. You can still choose with \"Let me choose\".",
            value = SettingValue.Toggle(s.autoPlayFirst),
            onClick = { s.autoPlayFirst = !s.autoPlayFirst },
        )
        SettingRow(
            title = "Ready to play only",
            body = "Only list sources that play straight away: direct links, and torrents your debrid service already has. Hides ones that would have to download first.",
            value = SettingValue.Toggle(s.cachedOnly),
            onClick = { s.cachedOnly = !s.cachedOnly },
        )
        SettingRow(
            title = "Best quality first",
            body = "Within each add-on, list ready sources first, then by quality (4K, 1080p, 720p). Off keeps the add-on's own order.",
            value = SettingValue.Toggle(s.bestFirst),
            onClick = { s.bestFirst = !s.bestFirst },
        )
        SettingRow(
            title = "Highest quality",
            body = "Skip sources above this quality, for slower connections or TVs that struggle with 4K.",
            value = SettingValue.Choice(listOf("Any", "Up to 1080p", "Up to 720p")[s.maxQuality.coerceIn(0, 2)]),
            onClick = { s.maxQuality = (s.maxQuality + 1) % 3 },
        )
        SettingRow(
            title = "Same source when resuming",
            body = "Tries the source you used last time first; for series, the same release for the next episode (like Nuvio's binge groups).",
            value = SettingValue.Toggle(s.sameSource),
            onClick = { s.sameSource = !s.sameSource },
        )
        SettingRow(
            title = "Skip intros",
            body = "Using IntroDB's community timings, where an episode has them.",
            value = SettingValue.Choice(listOf("Off", "Show a Skip button", "Skip by itself")[s.skipIntro.coerceIn(0, 2)]),
            onClick = { s.skipIntro = (s.skipIntro + 1) % 3 },
        )
        SettingRow(
            title = "Skip recaps",
            body = "\"Previously on…\" at the start of episodes.",
            value = SettingValue.Choice(listOf("Off", "Show a Skip button", "Skip by itself")[s.skipRecap.coerceIn(0, 2)]),
            onClick = { s.skipRecap = (s.skipRecap + 1) % 3 },
        )
        SettingRow(
            title = "Play the next episode automatically",
            body = "The Up next card always appears at the credits. On: it counts down from 10, then plays the next episode. Off: it waits for you.",
            value = SettingValue.Toggle(s.autoNextEpisode),
            onClick = { s.autoNextEpisode = !s.autoNextEpisode },
        )
        }
        SettingsGroup("Player", "Language and buffering") {
        SettingRow(
            title = "Preferred audio language",
            body = "Used when a channel offers more than one language. You can still switch in the player.",
            value = SettingValue.Choice(languageLabel(s.audioLanguage)),
            onClick = { s.audioLanguage = next(s.audioLanguage) },
        )
        SettingRow(
            title = "Bigger buffer",
            body = "Holds more video in reserve for unsteady connections, at the cost of slower channel changes. " +
                "Takes effect next time Zapp starts.",
            value = SettingValue.Toggle(s.largeBuffer),
            onClick = { s.largeBuffer = !s.largeBuffer },
        )
        }
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
private fun AboutPane(vm: AppViewModel, onPhone: () -> Unit) {
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
                    Column(Modifier.weight(1f)) {
                        Text("Zapp closed unexpectedly", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            crash.lineSequence().drop(5).firstOrNull { it.isNotBlank() }?.take(160) ?: "",
                            color = ZappColors.Dim,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZButton("Open on my phone", onPhone, style = ButtonStyle.Primary)
                            ZButton("Clear report", { vm.clearCrash() }, style = ButtonStyle.Quiet)
                        }
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
            "Zapp TV doesn't include any channels, playlists or accounts. Add your own from Sources. " +
                "Intro, recap and credits timings come from IntroDB (introdb.app), a community project.",
            color = ZappColors.Faint,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AddonsPane(vm: AppViewModel, mvm: MediaViewModel, onPhone: () -> Unit) {
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
            Column {
                Text(
                    "Paste an add-on's install link (it ends in manifest.json). Zapp doesn't include any add-ons; you choose your own.",
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZButton("Paste on my phone", onPhone, style = ButtonStyle.Primary)
                    ZButton("Type a link", { adding = true })
                    if (addons.isNotEmpty()) ZButton("Refresh add-ons", { mvm.refreshAddons() }, style = ButtonStyle.Quiet)
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
private fun AccountsPane(vm: AppViewModel, mvm: MediaViewModel, onPhone: (String) -> Unit) {
    LaunchedEffect(Unit) { mvm.refreshSimklState() }
    val state = mvm.simklState
    val focus = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsGroup(
            "Simkl",
            when (state) {
                is MediaViewModel.SimklState.SignedIn -> "Connected" + if (state.user.isNotBlank()) " as ${state.user}" else ""
                else -> "Syncs watched, progress and library (and Nuvio through it)"
            },
        ) {
        Text(
            "Keeps what you've watched, where you got to, and your library in step with Simkl, and with Nuvio " +
                "when Nuvio uses Simkl for watch progress and library. Films and series only; live channels aren't sent.",
            color = ZappColors.Dim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(4.dp))
        when (state) {
            MediaViewModel.SimklState.NeedsAppId -> Column {
                Text("First, a Simkl app ID", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create an app at simkl.com/settings/developer (choose a device or TV app), then paste its Client ID on your phone. You only do this once.",
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZButton("Paste it on my phone", { onPhone("simkl") }, style = ButtonStyle.Primary, focusRequester = focus)
                    ZButton("I've added it", { mvm.refreshSimklState() })
                }
            }

            MediaViewModel.SimklState.SignedOut, is MediaViewModel.SimklState.Failed -> {
                if (state is MediaViewModel.SimklState.Failed) ErrorBanner(state.message)
                SettingRow(
                    title = "Connect Simkl",
                    body = "Shows a code here; approve it on your phone. Zapp never sees your password.",
                    value = SettingValue.Action,
                    onClick = { mvm.connectSimkl() },
                    focusRequester = focus,
                )
            }

            is MediaViewModel.SimklState.WaitingForPhone -> Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(state.link, 140.dp)
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Scan with your phone and approve Zapp", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Or go to ${state.shortLink.removePrefix("https://")} and enter", color = ZappColors.Dim, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(state.code, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Waiting for you to approve…", color = ZappColors.Faint, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        ZButton("Cancel", { mvm.cancelSimklSignIn() }, style = ButtonStyle.Quiet, focusRequester = focus)
                    }
                }
            }

            is MediaViewModel.SimklState.SignedIn -> {
                SettingRow(
                    title = if (state.user.isNotBlank()) "Connected as ${state.user}" else "Connected",
                    body = if (mvm.settings.simklLastSync > 0) {
                        "Last synced ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(mvm.settings.simklLastSync)}"
                    } else {
                        "Not synced yet"
                    },
                    value = SettingValue.Choice(if (mvm.simklSyncing) "Syncing…" else "Sync now"),
                    onClick = { mvm.syncSimkl(force = true) },
                    focusRequester = focus,
                )
                mvm.simklMessage?.let { Text(it, color = ZappColors.Dim, fontSize = 12.sp) }
                SettingRow(
                    title = "Disconnect Simkl",
                    body = "Zapp stops syncing and forgets the sign-in. What's already here stays.",
                    value = SettingValue.Action,
                    onClick = { mvm.disconnectSimkl() },
                )
                Text(
                    "In Nuvio, connect the same Simkl account and choose Simkl for watch progress and library.",
                    color = ZappColors.Faint,
                    fontSize = 12.sp,
                )
            }
        }

        }
        SettingsGroup("Stremio", if (mvm.stremio.signedIn) "Signed in as ${mvm.settings.stremioUser}" else "Bring over your Stremio library and Continue watching") {
            StremioSection(vm, mvm) { onPhone("stremio") }
        }
        SettingsGroup("Nuvio", nuvioSummary(mvm)) {
            NuvioSyncSection(mvm) { onPhone("nuvio") }
            Spacer(Modifier.height(6.dp))
            NuvioSection(mvm)
        }
        SettingsGroup("TMDB", if (mvm.tmdb.enabled) "Connected: cast photos, reviews and recommendations" else "Optional: cast photos, reviews and recommendations on details pages") {
            Text(
                "With your own free TMDB key, details pages show cast photos, age ratings, reviews, \"More with…\" and \"People also watched\".",
                color = ZappColors.Dim,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZButton(if (mvm.tmdb.enabled) "Change key on my phone" else "Add key on my phone", { onPhone("tmdb") }, style = ButtonStyle.Primary)
                if (mvm.tmdb.enabled) ZButton("Remove key", { mvm.settings.setSecret("tmdbKey", "") }, style = ButtonStyle.Quiet)
            }
        }
        SettingsGroup("Debrid services", mvm.debridNames().takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Connected: $it" } ?: "Real-Debrid, AllDebrid, Premiumize, TorBox") {
            DebridSection(vm, mvm)
        }
    }
}

@Composable
private fun StremioSection(vm: AppViewModel, mvm: MediaViewModel, onPhone: () -> Unit) {
    mvm.stremioVersion
    val s = mvm.settings
    if (mvm.stremio.signedIn) {
        SettingRow(
            title = "Signed in as ${s.stremioUser.ifBlank { "your Stremio account" }}",
            body = mvm.stremioStatus ?: "Your Stremio library and Continue watching come into this profile when Zapp opens and every 30 minutes, and what you watch here goes back to Stremio.",
            value = SettingValue.Choice(if (mvm.stremioWorking) "Syncing…" else "Sync now"),
            onClick = { mvm.syncStremio() },
        )
        SettingRow(
            title = "Sign out of Stremio",
            body = "Stops syncing. What's already here stays.",
            value = SettingValue.Action,
            onClick = { mvm.signOutStremio() },
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Bring over your Stremio library and Continue watching. Scan the code and sign in to Stremio on your phone; " +
                        "Zapp keeps only Stremio's sign-in key, never your password.",
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                mvm.stremioStatus?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ZappColors.Faint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            ZButton("Sign in on my phone", onPhone, style = ButtonStyle.Primary)
        }
    }
}

private fun nuvioSummary(mvm: MediaViewModel): String {
    mvm.nuvioVersion
    val pid = mvm.settings.profileId
    return when {
        !mvm.nuvio.signedIn(pid) -> "Sync Continue watching, watched and library, and import add-ons"
        mvm.nuvio.linkedProfile(pid) == 0 -> "Connected: choose a Nuvio profile"
        else -> "Syncing with ${mvm.nuvio.linkedProfileName(pid).ifBlank { "your profile" }}"
    }
}

@Composable
private fun NuvioSyncSection(mvm: MediaViewModel, onPhone: () -> Unit) {
    mvm.nuvioVersion
    val pid = mvm.settings.profileId
    val nuvio = mvm.nuvio
    if (!nuvio.signedIn(pid)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Two-way sync with your Nuvio account: where you got to, what you've watched and your library. " +
                        "Sign in on your phone; Zapp keeps only the Nuvio session, encrypted, never your password.",
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                mvm.nuvioSyncStatus?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ZappColors.Faint, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            ZButton("Connect on my phone", onPhone, style = ButtonStyle.Primary)
        }
        return
    }
    LaunchedEffect(pid, mvm.nuvioVersion) { if (mvm.nuvioProfiles.isEmpty()) mvm.loadNuvioProfiles() }
    val linked = nuvio.linkedProfile(pid)
    if (linked != 0) {
        val last = nuvio.lastSync(pid)
        val pending = nuvio.pending(pid)
        SettingRow(
            title = "Connected as ${nuvio.user(pid).ifBlank { "your Nuvio account" }}",
            body = buildString {
                append(
                    mvm.nuvioSyncStatus ?: if (last > 0) {
                        "Last synced " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(last)
                    } else {
                        "Not synced yet"
                    },
                )
                if (pending > 0) append(" · $pending waiting to send")
            },
            value = SettingValue.Choice(if (mvm.nuvioSyncing) "Syncing…" else "Sync now"),
            onClick = { mvm.syncNuvio() },
        )
    } else {
        Text(
            "Connected as ${nuvio.user(pid).ifBlank { "your Nuvio account" }}. Choose which Nuvio profile this Zapp profile syncs with.",
            color = ZappColors.Dim,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        mvm.nuvioSyncStatus?.let { Text(it, color = ZappColors.Faint, fontSize = 12.sp) }
    }
    if (mvm.nuvioProfiles.size > 1 || linked == 0) {
        Text("Nuvio profile", color = ZappColors.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        mvm.nuvioProfiles.forEach { p ->
            SettingRow(
                title = p.name,
                body = if (p.index == linked) "Linked to this Zapp profile" else "Sync this Zapp profile with ${p.name}",
                value = SettingValue.Choice(if (p.index == linked) "Linked" else "Link"),
                onClick = { if (p.index != linked) mvm.linkNuvioProfile(p) },
            )
        }
    }
    if (linked != 0) {
        SettingRow(
            title = "Nuvio is the source for Continue watching",
            body = "On: Continue watching shows exactly what Nuvio's does, in the same order. Titles only in Zapp are hidden from the row, not deleted.",
            value = SettingValue.Toggle(nuvio.mirror(pid)),
            onClick = {
                nuvio.setMirror(pid, !nuvio.mirror(pid))
                mvm.syncNuvio()
            },
        )
    }
    SettingRow(
        title = "Disconnect Nuvio",
        body = "Stops syncing and signs Zapp out of Nuvio. What's already here stays.",
        value = SettingValue.Action,
        onClick = { mvm.disconnectNuvio() },
    )
    Text(
        "Syncs when Zapp opens, when you switch profile, when you come back to Zapp and every 30 minutes; " +
            "what you watch is sent while you watch and when you pause or stop. Films and episodes only, never live TV.",
        color = ZappColors.Faint,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun NuvioSection(mvm: MediaViewModel) {
    var otherDevice by remember { mutableStateOf("") }
    Text(
        "Add-ons: in Nuvio TV open Add-ons, choose Manage from phone, then press Home (leave it open), " +
            "come back here and press Import.",
        color = ZappColors.Dim,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
    SettingRow(
        title = "Import add-ons from Nuvio",
        body = mvm.nuvioStatus ?: "Copies your Nuvio add-ons, with their settings, into Zapp.",
        value = SettingValue.Choice(if (mvm.nuvioWorking) "Working…" else "Import"),
        onClick = { mvm.importFromNuvio(otherDevice.ifBlank { null }) },
    )
    TvTextField(
        label = "Nuvio on another device? Its address (optional)",
        value = otherDevice,
        onValueChange = { otherDevice = it },
        placeholder = "The address shown under Nuvio's QR code, like 192.168.1.20:8080",
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
        last = true,
    )
}

@Composable
private fun DebridSection(vm: AppViewModel, mvm: MediaViewModel) {
    val version = mvm.settings.debridVersion
    Text(
        "Lets torrent sources from your add-ons play: Zapp asks your own debrid account for a normal link. " +
            "If you connect more than one, they're tried in this order.",
        color = ZappColors.Dim,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
    mvm.message?.let { Text(it, color = ZappColors.Signal, fontSize = 12.sp) }

    when (val s = mvm.debridSignIn) {
        is MediaViewModel.DebridSignIn.Waiting -> Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(s.link, 140.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scan with your phone and approve Zapp on ${s.service}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Or go to ${s.shortLink.removePrefix("https://")} and enter", color = ZappColors.Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(s.code, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Spacer(Modifier.height(10.dp))
                    val cancel = remember { FocusRequester() }
                    LaunchedEffect(Unit) { cancel.tryRequest() }
                    ZButton("Cancel", { mvm.cancelDebridSignIn() }, style = ButtonStyle.Quiet, focusRequester = cancel)
                }
            }
        }
        is MediaViewModel.DebridSignIn.Failed -> ErrorBanner(s.message)
        MediaViewModel.DebridSignIn.None -> Unit
    }

    val d = mvm.debrid
    listOf<Triple<com.zapp.tv.data.DebridService, String, (() -> Unit)?>>(
        Triple(d.realDebrid, "Sign in with a code on your phone.", { mvm.connectRealDebrid() }),
        Triple(d.allDebrid, "Sign in with a PIN on your phone.", { mvm.connectAllDebrid() }),
        Triple(d.premiumize, "Paste your API key (premiumize.me, Account) on the phone page, Debrid tab.", null),
        Triple(d.torBox, "Paste your API key (torbox.app, Settings) on the phone page, Debrid tab.", null),
    ).forEach { (service, how, connect) ->
        val on = version >= 0 && service.connected
        SettingRow(
            title = service.name,
            body = if (on) "Connected" else how,
            value = SettingValue.Choice(if (on) "Disconnect" else if (connect != null) "Connect" else "From phone"),
            onClick = {
                when {
                    on -> mvm.disconnectDebrid(service.id)
                    connect != null -> connect()
                    else -> vm.showToast("Open the phone page from Settings, Playlists and choose the Debrid tab")
                }
            },
        )
    }
}

@Composable
private fun AddAddonDialog(mvm: MediaViewModel, onClose: () -> Unit) {
    var link by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.tryRequest() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
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
private fun AppearancePane(vm: AppViewModel) {
    val s = vm.settings
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup("Theme", (Palettes.all.firstOrNull { it.id == s.theme }?.name ?: "Custom") + ": colours for the glow, highlights and progress bars", startOpen = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (Palettes.all + Palettes.custom(s.customColors.first, s.customColors.second)).forEach { p ->
                val chosen = s.theme == p.id
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusSurface(
                        onClick = { s.theme = p.id },
                        modifier = Modifier
                            .width(84.dp)
                            .height(54.dp)
                            .border(2.dp, if (chosen) Color.White else Color.Transparent, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        focusScale = 1.05f,
                        focusColor = Color.White,
                    ) { focused ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(if (focused) 3.dp else 0.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.bg)
                                .background(
                                    Brush.linearGradient(
                                        listOf(p.brand1.copy(alpha = 0.85f), Color.Transparent, p.brand2.copy(alpha = 0.85f)),
                                    ),
                                ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, fontSize = 12.sp, color = if (chosen) ZappColors.Text else ZappColors.Dim, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (s.theme == "custom") {
            Spacer(Modifier.height(16.dp))
            listOf("First colour" to true, "Second colour" to false).forEach { (label, firstColour) ->
                Text(label, color = ZappColors.Dim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Palettes.Swatches.forEachIndexed { i, c ->
                        val chosen = if (firstColour) s.customColors.first == i else s.customColors.second == i
                        FocusSurface(
                            onClick = { s.customColors = if (firstColour) i to s.customColors.second else s.customColors.first to i },
                            modifier = Modifier
                                .size(32.dp)
                                .border(2.dp, if (chosen) Color.White else Color.Transparent, androidx.compose.foundation.shape.CircleShape),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            focusScale = 1.15f,
                            focusColor = Color.Transparent,
                            ring = true,
                            ringCorner = 16.dp,
                        ) {
                            Box(Modifier.fillMaxSize().padding(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        }
        SettingsGroup("Focus", "Outline and glow around the card you're on") {
        SettingRow(
            title = "Focus outline",
            body = "How thick the outline is around the card you're on.",
            value = SettingValue.Choice(listOf("Thin", "Medium", "Thick")[s.focusRing.coerceIn(0, 2)]),
            onClick = { s.focusRing = (s.focusRing + 1) % 3 },
        )
        SettingRow(
            title = "Pulsing glow",
            body = "A soft glow in the theme's colour that gently pulses around the card you're on.",
            value = SettingValue.Toggle(s.focusGlow),
            onClick = { s.focusGlow = !s.focusGlow },
        )
        }
        SettingsGroup("Trailers and motion", if (s.trailers) "Trailers on" else "Trailers off") {
        SettingRow(
            title = "Moving artwork",
            body = "A slow zoom and pan on the pictures behind Home and details. Turn off if your TV feels slow.",
            value = SettingValue.Toggle(s.drift),
            onClick = { s.drift = !s.drift },
        )
        SettingRow(
            title = "Colour from artwork",
            body = "The background glow takes its colour from the title you're on.",
            value = SettingValue.Toggle(s.artGlow),
            onClick = { s.artGlow = !s.artGlow },
        )
        SettingRow(
            title = "Trailers",
            body = "After a moment on a title, its trailer plays silently behind the header on Home and the details page.",
            value = SettingValue.Toggle(s.trailers),
            onClick = { s.trailers = !s.trailers },
        )
        if (s.trailers) {
            SettingRow(
                title = "Start after",
                body = "How long you rest on a title before its trailer begins.",
                value = SettingValue.Choice("${s.trailerDelay} seconds"),
                onClick = {
                    val options = listOf(2, 4, 8, 15)
                    s.trailerDelay = options[(options.indexOf(s.trailerDelay) + 1).mod(options.size)]
                },
            )
            SettingRow(
                title = "Trailer sound",
                body = "Off keeps trailers silent behind the page.",
                value = SettingValue.Toggle(s.trailerSound),
                onClick = { s.trailerSound = !s.trailerSound },
            )
            SettingRow(
                title = "Trailer quality",
                body = "Best asks YouTube for up to 4K. Choose Auto on slower boxes if trailers stutter.",
                value = SettingValue.Choice(if (s.trailerBest) "Best" else "Auto"),
                onClick = { s.trailerBest = !s.trailerBest },
            )
            SettingRow(
                title = "Startup sound",
                body = "A short chime as Zapp opens.",
                value = SettingValue.Toggle(s.startupSound),
                onClick = { s.startupSound = !s.startupSound },
            )
        }
        }
    }
}

@Composable
private fun HomeLayoutPane(mvm: MediaViewModel) {
    val addons by mvm.addons.collectAsState()
    val catalogs by androidx.compose.runtime.produceState(emptyList<com.zapp.tv.addons.CatalogRef>(), addons) { value = mvm.catalogs(null) }
    val rows = remember(catalogs, mvm.settings.homeRowsJson) { mvm.homeLayout(catalogs) }
    val continueCount = mvm.continueWatching.collectAsState().value.size

    fun update(newRows: List<HomeRowSetting>) = mvm.saveLayout(newRows)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item(key = "intro") {
            Text(
                "Choose which catalogues appear on Home and in what order. Continue watching always comes first. On Home, hold OK on any card to change that row's size or shape.",
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
                        body = (if (!r.on) "Hidden" else "Shown, " + listOf("small", "medium", "large")[r.size] + if (r.landscape) " landscape cards" else " posters") +
                            if (r.id == CONTINUE_ROW) ". ${continueCount.let { if (it == 0) "Nothing in progress yet" else if (it == 1) "1 title in progress" else "$it titles in progress" }}" else "",
                        value = SettingValue.Toggle(r.on),
                        onClick = { update(rows.map { if (it.id == r.id) it.copy(on = !it.on) else it }) },
                    )
                }
                if (i > 1) ZButton("Up", { mvm.moveRow(rows, r.id, -1) })
                if (i in 1 until rows.size - 1) ZButton("Down", { mvm.moveRow(rows, r.id, 1) })
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
    // Clear on any theme: "On" is a coloured track with a dark knob; "Off" is an empty outline.
    val knobX by androidx.compose.animation.core.animateDpAsState(if (checked) 20.dp else 0.dp, androidx.compose.animation.core.tween(160), label = "knob")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (checked) "On" else "Off",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                focused -> ZappColors.OnFocusDim
                checked -> ZappColors.Text
                else -> ZappColors.Faint
            },
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(42.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (checked) Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
                )
                .border(1.5.dp, if (checked) Color.Transparent else if (focused) Color(0x66000000) else Color(0x55FFFFFF), RoundedCornerShape(50))
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .padding(start = knobX)
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            checked -> ZappColors.Bg
                            focused -> Color(0x99000000)
                            else -> Color(0x88FFFFFF)
                        },
                    ),
            )
        }
    }
}

/** A heading that opens and closes its settings, so long pages stay short. */
@Composable
fun SettingsGroup(title: String, summary: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, androidx.compose.animation.core.tween(200), label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        FocusSurface(
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            focusScale = 1.01f,
            restColor = ZappColors.Glass,
        ) { focused ->
            Row(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else ZappColors.Text)
                    Text(summary, fontSize = 12.sp, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text(
                    "›",
                    fontSize = 22.sp,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Faint,
                    modifier = Modifier.graphicsLayer { rotationZ = turn },
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        ) {
            Column(
                Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

/** The one place with the phone code: everything you can type on your phone instead of the remote. */
@Composable
private fun PhoneSetupPane(vm: AppViewModel, tab: String?) {
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Panel {
            PhoneSetupCard(vm, qrSize = 200, modifier = Modifier.padding(24.dp).width(230.dp), tab = tab)
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set up from your phone", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Scan the code with your phone's camera (same Wi-Fi as the TV). Typing there is much quicker than the remote, and everything appears here straight away.",
                color = ZappColors.Dim,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            listOf(
                "Playlist" to "Xtream logins and M3U links, with a TV guide link",
                "Add-on" to "Stremio add-ons by their install link",
                "Accounts" to "Stremio and Nuvio sign-in, the Simkl app ID, TMDB, Premiumize and TorBox keys",
                "Help" to "The crash report, to copy into your chat about the app",
            ).forEach { (t, b) ->
                Row {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Brush.linearGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(b, color = ZappColors.Dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
