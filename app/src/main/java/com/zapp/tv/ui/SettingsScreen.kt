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
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

const val UPDATE_LINK = "github.com/jsbccy7mks-art/Eureka/releases/latest/download/zapp-tv.apk"

private enum class SettingsSection(val label: String, val listed: Boolean = true) {
    Phone("Phone setup", listed = false),
    Sources("Playlists"),
    Addons("Add-ons"),
    Accounts("Accounts"),
    HomeLayout("Home"),
    Appearance("Appearance"),
    Playback("Playback"),
    Live("Live TV"),
    Profiles("Profiles"),
    About("System"),
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
    NewSettings.init(vm.settings)
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
        Column(Modifier.width(220.dp)) {
            Text("Settings", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, modifier = Modifier.padding(start = 14.dp))
            Spacer(Modifier.height(22.dp))
            LazyColumn(
                modifier = Modifier
                    .onFocusChanged { sectionsHaveFocus = it.hasFocus }
                    .restoreFocus(sectionMemory),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(SettingsSection.entries.filter { it.listed }, key = { it.name }) { s ->
                    FocusSurface(
                        onClick = { section = s },
                        modifier = Modifier
                            .rememberFocus(sectionMemory, s.name)
                            .fillMaxWidth(),
                        selected = s == section || (section == SettingsSection.Phone && s == SettingsSection.About),
                        focusRequester = if (s == section) firstFocus else if (s == SettingsSection.entries.first()) firstItem else null,
                        onFocusChange = { if (it) focusedSection = s },
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1.02f,
                    ) { focused ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionIcon(
                                s,
                                when {
                                    focused -> ZappColors.OnFocus
                                    s == section -> ZappColors.Text
                                    else -> ZappColors.Faint
                                },
                            )
                            Spacer(Modifier.width(13.dp))
                            Text(
                                s.label,
                                color = when {
                                    focused -> ZappColors.OnFocus
                                    s == section -> ZappColors.Text
                                    else -> ZappColors.Dim
                                },
                                fontSize = TextSize.Body,
                                fontWeight = FontWeight.Medium,
                            )
                            if (NewSettings.sectionHasNew(s.name)) {
                                Spacer(Modifier.width(8.dp))
                                NewDot()
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(32.dp))

        LaunchedEffect(section) { SettingsHelp.clear() }
        LaunchedEffect(sectionsHaveFocus) { if (sectionsHaveFocus) SettingsHelp.clear() }
        androidx.compose.runtime.CompositionLocalProvider(LocalHelpAtFoot provides true) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                SettingsSection.Accounts -> AccountsPane(vm, mvm) { openPhone(it) }
                SettingsSection.About -> AboutPane(vm, onPhoneSetup = { openPhone("xtream") }) { openPhone("help") }
            }
        }
        HelpFoot()
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
    ) {
        SettingsGroup("Watching", "") {
            SettingRow(
                title = "Pause live TV",
                body = "Keeps the last 5 minutes of the channel you're watching, so you can pause, rewind and catch up. Cleared when you change channel. Uses about 100–400 MB while watching.",
                value = SettingValue.Toggle(vm.settings.liveRewind),
                onClick = { vm.settings.liveRewind = !vm.settings.liveRewind },
            )
            SettingRow(
                title = "Channel previews",
                body = "Plays the channel you rest on in the corner of Live TV and in the Home header.",
                value = SettingValue.Toggle(vm.settings.preview),
                onClick = {
                    vm.settings.preview = !vm.settings.preview
                    if (!vm.settings.preview) playback.stop()
                },
            )
        }
        SettingsGroup("Guide and playlists", "") {
            SettingRow(
                title = "Guide updates",
                body = "How often the TV guide is downloaded again while the app is open.",
                value = SettingValue.Choice(if (vm.settings.guideHours == 0) "Off" else "Every ${vm.settings.guideHours} hours"),
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
                title = "Refresh playlists",
                body = "Reloads channel lists from your providers. Favourites and history are kept.",
                value = SettingValue.Action,
                onClick = { vm.refreshAll() },
            )
        }
        Spacer(Modifier.height(20.dp))
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
    ) {
        SettingsGroup("Starting", "") {
            SettingRow(
                title = "Play automatically",
                body = "Starts the best source straight away instead of showing the list. You can still pick one with \"Let me choose\".",
                value = SettingValue.Toggle(s.autoPlayFirst),
                onClick = { s.autoPlayFirst = !s.autoPlayFirst },
            )
            SettingRow(
                title = "Prepare in the background",
                body = "While you're on a details page, fetches sources and gets a cached debrid link ready, so Play starts almost at once.",
                value = SettingValue.Toggle(s.prepareLinks),
                onClick = { s.prepareLinks = !s.prepareLinks },
            )
            SettingRow(
                title = "Instant sources only",
                body = "Only lists sources that play straight away: direct links and torrents your debrid service already has.",
                value = SettingValue.Toggle(s.cachedOnly),
                onClick = { s.cachedOnly = !s.cachedOnly },
            )
            SettingRow(
                title = "Best quality first",
                body = "Within each add-on, ready sources first, then by quality. Off keeps the add-on's own order.",
                value = SettingValue.Toggle(s.bestFirst),
                onClick = { s.bestFirst = !s.bestFirst },
            )
            SettingRow(
                title = "Quality limit",
                body = "Skips sources above this quality, for slower connections or TVs that struggle with 4K.",
                value = SettingValue.Choice(listOf("None", "1080p", "720p")[s.maxQuality.coerceIn(0, 2)]),
                onClick = { s.maxQuality = (s.maxQuality + 1) % 3 },
            )
            SettingRow(
                title = "Remember source",
                body = "Tries the source you used last time first; for series, the same release for the next episode.",
                value = SettingValue.Toggle(s.sameSource),
                onClick = { s.sameSource = !s.sameSource },
            )
            var learnReset by remember { mutableStateOf(false) }
            SettingRow(
                title = "Smart source order",
                body = (if (learnReset) "Cleared. " else "") + "Learns how each add-on and release plays on this TV and lists reliable ones higher. OK starts over.",
                value = SettingValue.Choice(if (learnReset) "Cleared" else "Learning"),
                onClick = {
                    (vm.getApplication<com.zapp.tv.ZappApp>()).sourceStats.reset()
                    learnReset = true
                },
            )
        }
        SettingsGroup("Episodes and endings", "") {
            SettingRow(
                title = "Skip intros",
                body = "Uses IntroDB's community timings, where an episode has them.",
                value = SettingValue.Choice(listOf("Off", "Show button", "Automatically")[s.skipIntro.coerceIn(0, 2)]),
                onClick = { s.skipIntro = (s.skipIntro + 1) % 3 },
            )
            SettingRow(
                title = "Skip recaps",
                body = "\"Previously on…\" at the start of episodes.",
                value = SettingValue.Choice(listOf("Off", "Show button", "Automatically")[s.skipRecap.coerceIn(0, 2)]),
                onClick = { s.skipRecap = (s.skipRecap + 1) % 3 },
            )
            SettingRow(
                title = "Autoplay next episode",
                body = "Up next appears at the credits. On: it counts down from 10, then plays. Off: it waits for you.",
                value = SettingValue.Toggle(s.autoNextEpisode),
                onClick = { s.autoNextEpisode = !s.autoNextEpisode },
            )
            SettingRow(
                title = "Suggestions at the end",
                body = "At a film's credits it shrinks to a corner and something to watch next fills the screen. Needs your TMDB key.",
                value = SettingValue.Toggle(s.postPlaySuggest),
                onClick = { s.postPlaySuggest = !s.postPlaySuggest },
            )
        }
        SettingsGroup("Picture", "") {
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            val system = activity?.let { DisplayMatch.systemPreference(it) }
            SettingRow(
                title = "Match frame rate",
                body = "Switches your TV to the film's frame rate before it starts, so motion is smooth." +
                    when (system) {
                        0 -> " Your box blocks this: set Display & Sound, Advanced display settings, Match content frame rate to Always."
                        1 -> " Your box only allows seamless switches: set Match content frame rate to Always in its settings."
                        else -> " The screen may go black for a moment."
                    },
                value = SettingValue.Choice(listOf("Off", "On", "On, switch back after")[s.frameRateMode.coerceIn(0, 2)]),
                onClick = { s.frameRateMode = (s.frameRateMode + 1) % 3 },
            )
            SettingRow(
                title = "Match resolution",
                body = "Also switches to the video's resolution. Off lets your TV upscale, which is often better and quicker.",
                value = SettingValue.Toggle(s.matchResolution),
                onClick = { s.matchResolution = !s.matchResolution },
            )
        }
        SettingsGroup("Sound", "") {
            SettingRow(
                title = "Audio language",
                body = "Used when there's more than one language. You can still switch in the player.",
                value = SettingValue.Choice(languageLabel(s.audioLanguage)),
                onClick = { s.audioLanguage = next(s.audioLanguage) },
            )
            SettingRow(
                title = "Bigger buffer",
                body = "Holds more video in reserve for unsteady connections; channels change a little slower. Applies next time Axon starts.",
                value = SettingValue.Toggle(s.largeBuffer),
                onClick = { s.largeBuffer = !s.largeBuffer },
            )
        }
        SubtitleSettings(vm)
        Text(
            "In the player: OK for controls, Left for channels, Right for what's on, Up and Down to change channel.",
            color = ZappColors.Faint,
            fontSize = TextSize.Small,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun SubtitleSettings(vm: AppViewModel) {
    val s = vm.settings
    val sizes = listOf("Small", "Medium", "Large", "Extra large")
    val styles = listOf("Shadow", "Dark box", "Yellow")
    SettingsGroup("Subtitles", "") {
        // How they'll look.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .height(84.dp)
                .clip(Shapes.Row)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF2E3B55), Color(0xFF55482E), Color(0xFF223030)),
                    ),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.padding(bottom = 10.dp)) {
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
            title = "Films and series",
            body = "Turns subtitles on by themselves for films and series. Font, size and timing are also in the player's Subtitles menu.",
            value = SettingValue.Choice(if (s.vodSubtitlesOn) "On" else "Off"),
            onClick = { s.vodSubtitlesOn = !s.vodSubtitlesOn },
        )
        SettingRow(
            title = "Live TV",
            body = "Turns subtitles on by themselves when a channel has them.",
            value = SettingValue.Choice(if (s.subtitlesOn) "On" else "Off"),
            onClick = { s.subtitlesOn = !s.subtitlesOn },
        )
        SettingRow(
            title = "Language",
            body = "Preferred language when subtitles turn on by themselves.",
            value = SettingValue.Choice(languageLabel(s.subtitleLanguage)),
            onClick = { s.subtitleLanguage = next(s.subtitleLanguage) },
        )
        SettingRow(
            title = "Size",
            body = "For text subtitles and captions. Picture-based (DVB) subtitles keep the broadcaster's look.",
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
            body = "Raise subtitles if the edge of your TV hides them.",
            value = SettingValue.Choice(if (s.subtitleRaised) "Raised" else "Bottom"),
            onClick = { s.subtitleRaised = !s.subtitleRaised },
        )
    }
}

@Composable
private fun AboutPane(vm: AppViewModel, onPhoneSetup: () -> Unit, onPhone: () -> Unit) {
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
        Row(Modifier.padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(40.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Axon", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                Text("Version ${version ?: "unknown"} · build ${vm.currentBuild}", color = ZappColors.Dim, fontSize = TextSize.Small)
            }
        }
        val u = vm.update
        SettingsGroup("Updates", "") {
            SettingRow(
                title = "Check for updates",
                body = when (u) {
                    UpdateState.Idle -> "Looks for a newer version of Axon."
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "You have the latest version."
                    is UpdateState.Available -> "Build ${u.release.build} is ready to install."
                    is UpdateState.Downloading -> "Downloading build ${u.release.build}…"
                    is UpdateState.Failed -> u.message
                },
                value = when (u) {
                    UpdateState.Checking -> SettingValue.Choice("Checking…")
                    UpdateState.UpToDate -> SettingValue.Choice("Up to date")
                    is UpdateState.Available -> SettingValue.Choice("Build ${u.release.build} ready")
                    is UpdateState.Failed -> SettingValue.Choice("Didn't work")
                    else -> SettingValue.Action
                },
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
                    ProgressLine(u.progress, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
            SettingRow(
                title = "What's new",
                body = "What changed in this version and the few before it.",
                value = SettingValue.Action,
                onClick = { WhatsNewState.notes = Changelog.read(context).take(3).ifEmpty { null } },
            )
        }
        SettingsGroup("Phone", "") {
            SettingRow(
                title = "Phone setup",
                body = "Add playlists, add-ons, keys and accounts by typing on your phone instead of the remote.",
                value = SettingValue.Action,
                onClick = onPhoneSetup,
            )
        }
        SettingsGroup("Checks", "") {
            var trailerTest by remember { mutableStateOf<String?>(null) }
            val testScope = androidx.compose.runtime.rememberCoroutineScope()
            SettingRow(
                title = "Test trailers",
                body = trailerTest ?: "Checks that trailers and theme tunes can play, and says what went wrong if not.",
                value = if (trailerTest == "Testing…") SettingValue.Choice("Testing…") else SettingValue.Action,
                onClick = {
                    trailerTest = "Testing…"
                    testScope.launch { trailerTest = (context.applicationContext as com.zapp.tv.ZappApp).youtube.test() }
                },
            )
            SettingRow(
                title = "Show frame rate",
                body = "A small counter at the top of the screen: frames per second and how many were slow.",
                value = SettingValue.Toggle(vm.settings.showFps),
                onClick = { vm.settings.showFps = !vm.settings.showFps },
            )
        }
        val crash = vm.lastCrash
        if (crash != null) {
            Spacer(Modifier.height(18.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Axon closed unexpectedly", fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            crash.lineSequence().drop(5).firstOrNull { it.isNotBlank() }?.take(160) ?: "",
                            color = ZappColors.Dim,
                            fontSize = TextSize.Small,
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
                Text("Updating another way", fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "If the built-in updater can't install on this TV, open the Downloader app and enter this address. " +
                        "It always points to the newest version.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(UPDATE_LINK, fontSize = TextSize.Body, fontWeight = FontWeight.Medium, color = ZappColors.Signal)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Axon doesn't include any channels, playlists or accounts. Add your own from Sources. " +
                "Intro, recap and credits timings come from IntroDB (introdb.app), a community project.",
            color = ZappColors.Faint,
            fontSize = TextSize.Small,
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
                    "Paste an add-on's install link (it ends in manifest.json). Axon doesn't include any add-ons; you choose your own.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
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
                                Text(a.manifest.name.take(1).uppercase(), fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.manifest.name + if (!a.addon.enabled) " (off)" else "",
                                fontSize = TextSize.Heading,
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
                                fontSize = TextSize.Small,
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
                    fontSize = TextSize.Small,
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsCaption("Sync", Modifier.padding(top = 0.dp))
        SettingsGroup(
            "Simkl",
            when (state) {
                is MediaViewModel.SimklState.SignedIn -> if (state.user.isNotBlank()) state.user else "Connected"
                else -> "Not connected"
            },
            collapsible = true,
            help = "Keeps watched, progress and your library in step with Simkl (and Nuvio through it).",
        ) {
        Text(
            "Keeps what you've watched, where you got to, and your library in step with Simkl, and with Nuvio " +
                "when Nuvio uses Simkl for watch progress and library. Films and series only; live channels aren't sent.",
            color = ZappColors.Dim,
            fontSize = TextSize.Small,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(4.dp))
        when (state) {
            MediaViewModel.SimklState.NeedsAppId -> Column {
                Text("First, a Simkl app ID", fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Create an app at simkl.com/settings/developer (choose a device or TV app), then paste its Client ID on your phone. You only do this once.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
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
                    body = "Shows a code here; approve it on your phone. Axon never sees your password.",
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
                        Text("Scan with your phone and approve Axon", fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Or go to ${state.shortLink.removePrefix("https://")} and enter", color = ZappColors.Dim, fontSize = TextSize.Small)
                        Spacer(Modifier.height(6.dp))
                        Text(state.code, fontSize = TextSize.Hero, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Waiting for you to approve…", color = ZappColors.Faint, fontSize = TextSize.Small)
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
                mvm.simklMessage?.let { Text(it, color = ZappColors.Dim, fontSize = TextSize.Small) }
                SettingRow(
                    title = "Disconnect Simkl",
                    body = "Axon stops syncing and forgets the sign-in. What's already here stays.",
                    value = SettingValue.Action,
                    onClick = { mvm.disconnectSimkl() },
                )
                Text(
                    "In Nuvio, connect the same Simkl account and choose Simkl for watch progress and library.",
                    color = ZappColors.Faint,
                    fontSize = TextSize.Small,
                )
            }
        }

        }
        SettingsGroup("Stremio", if (mvm.stremio.signedIn) mvm.settings.stremioUser.ifBlank { "Signed in" } else "Not connected", collapsible = true, help = "Brings over your Stremio library and Continue watching.") {
            StremioSection(vm, mvm) { onPhone("stremio") }
        }
        SettingsGroup("Nuvio", nuvioSummary(mvm), collapsible = true, help = "Syncs Continue watching, watched and library with Nuvio, and imports its add-ons.") {
            NuvioSyncSection(mvm) { onPhone("nuvio") }
            Spacer(Modifier.height(6.dp))
            NuvioSection(mvm)
        }
        SettingsCaption("Extras")
        SettingsGroup("TMDB", if (mvm.tmdb.enabled) "Connected" else "Not set up", collapsible = true, help = "Your free TMDB key: cast photos, reviews, recommendations and the Collections page.") {
            Text(
                "With your own free TMDB key, details pages show cast photos, age ratings, reviews, \"More with…\" and \"People also watched\".",
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
                lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZButton(if (mvm.tmdb.enabled) "Change key on my phone" else "Add key on my phone", { onPhone("tmdb") }, style = ButtonStyle.Primary)
                if (mvm.tmdb.enabled) ZButton("Remove key", { mvm.settings.setSecret("tmdbKey", "") }, style = ButtonStyle.Quiet)
            }
        }
        SettingsGroup("MDBList", if (mvm.mdblist.enabled) "Connected" else "Not set up", collapsible = true, help = "Your free MDBList key: IMDb, Rotten Tomatoes, Metacritic and Letterboxd scores.") {
            Text(
                "With your own free MDBList key (and TMDB above), details pages show IMDb, Rotten Tomatoes critics and audience, Metacritic and Letterboxd scores, and Home shows Rotten Tomatoes beside the year.",
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
                lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZButton(if (mvm.mdblist.enabled) "Change key on my phone" else "Add key on my phone", { onPhone("mdblist") }, style = ButtonStyle.Primary)
                if (mvm.mdblist.enabled) ZButton("Remove key", { mvm.settings.setSecret("mdblistKey", "") }, style = ButtonStyle.Quiet)
            }
        }
        SettingsCaption("Streaming")
        SettingsGroup("Debrid", mvm.debridNames().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Not connected", collapsible = true, help = "Real-Debrid, AllDebrid, Premiumize or TorBox, so torrent sources can play.") {
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
            body = mvm.stremioStatus ?: "Your Stremio library and Continue watching come into this profile when Axon opens and every 30 minutes, and what you watch here goes back to Stremio.",
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
                        "Axon keeps only Stremio's sign-in key, never your password.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                    lineHeight = 17.sp,
                )
                mvm.stremioStatus?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ZappColors.Faint, fontSize = TextSize.Small)
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
        !mvm.nuvio.signedIn(pid) -> "Not connected"
        mvm.nuvio.linkedProfile(pid) == 0 -> "Choose a profile"
        else -> mvm.nuvio.linkedProfileName(pid).ifBlank { "Connected" }
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
                        "Sign in on your phone; Axon keeps only the Nuvio session, encrypted, never your password.",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                    lineHeight = 17.sp,
                )
                mvm.nuvioSyncStatus?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = ZappColors.Faint, fontSize = TextSize.Small)
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
            "Connected as ${nuvio.user(pid).ifBlank { "your Nuvio account" }}. Choose which Nuvio profile this Axon profile syncs with.",
            color = ZappColors.Dim,
            fontSize = TextSize.Small,
            lineHeight = 17.sp,
        )
        mvm.nuvioSyncStatus?.let { Text(it, color = ZappColors.Faint, fontSize = TextSize.Small) }
    }
    if (mvm.nuvioProfiles.size > 1 || linked == 0) {
        Text("Nuvio profile", color = ZappColors.Faint, fontSize = TextSize.Small, modifier = Modifier.padding(top = 4.dp))
        mvm.nuvioProfiles.forEach { p ->
            SettingRow(
                title = p.name,
                body = if (p.index == linked) "Linked to this Axon profile" else "Sync this Axon profile with ${p.name}",
                value = SettingValue.Choice(if (p.index == linked) "Linked" else "Link"),
                onClick = { if (p.index != linked) mvm.linkNuvioProfile(p) },
            )
        }
    }
    if (linked != 0) {
        SettingRow(
            title = "Nuvio is the source for Continue watching",
            body = "On: Continue watching shows exactly what Nuvio's does, in the same order. Titles only in Axon are hidden from the row, not deleted.",
            value = SettingValue.Toggle(nuvio.mirror(pid)),
            onClick = {
                nuvio.setMirror(pid, !nuvio.mirror(pid))
                mvm.syncNuvio()
            },
        )
    }
    SettingRow(
        title = "Disconnect Nuvio",
        body = "Stops syncing and signs Axon out of Nuvio. What's already here stays.",
        value = SettingValue.Action,
        onClick = { mvm.disconnectNuvio() },
    )
    Text(
        "Syncs when Axon opens, when you switch profile, when you come back to Axon and every 30 minutes; " +
            "what you watch is sent while you watch and when you pause or stop. Films and episodes only, never live TV.",
        color = ZappColors.Faint,
        fontSize = TextSize.Small,
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
        fontSize = TextSize.Small,
        lineHeight = 17.sp,
    )
    SettingRow(
        title = "Import add-ons from Nuvio",
        body = mvm.nuvioStatus ?: "Copies your Nuvio add-ons, with their settings, into Axon.",
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
        "Lets torrent sources from your add-ons play: Axon asks your own debrid account for a normal link. " +
            "If you connect more than one, they're tried in this order.",
        color = ZappColors.Dim,
        fontSize = TextSize.Small,
        lineHeight = 17.sp,
    )
    mvm.message?.let { Text(it, color = ZappColors.Signal, fontSize = TextSize.Small) }

    when (val s = mvm.debridSignIn) {
        is MediaViewModel.DebridSignIn.Waiting -> Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(s.link, 140.dp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scan with your phone and approve Axon on ${s.service}", fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Or go to ${s.shortLink.removePrefix("https://")} and enter", color = ZappColors.Dim, fontSize = TextSize.Small)
                    Spacer(Modifier.height(6.dp))
                    Text(s.code, fontSize = TextSize.Hero, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
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
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add-on link", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
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
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SettingsGroup("Theme", (Palettes.all.firstOrNull { it.id == s.theme }?.name ?: "Custom") + ": colours for the glow, highlights and progress bars", startOpen = true) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Text(p.name, fontSize = TextSize.Small, color = if (chosen) ZappColors.Text else ZappColors.Dim, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (s.theme == "custom") {
            Spacer(Modifier.height(16.dp))
            listOf("First colour" to true, "Second colour" to false).forEach { (label, firstColour) ->
                Text(label, color = ZappColors.Dim, fontSize = TextSize.Small)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Palettes.Swatches.forEachIndexed { i, c ->
                        val chosen = if (firstColour) s.customColors.first == i else s.customColors.second == i
                        FocusSurface(
                            onClick = { s.customColors = if (firstColour) i to s.customColors.second else s.customColors.first to i },
                            modifier = Modifier
                                .size(32.dp)
                                .border(
                                    if (chosen) 2.dp else 1.dp,
                                    // Black needs an edge to be seen on the dark page.
                                    if (chosen) Color.White else if (c.luminance() < 0.05f) Color(0x55FFFFFF) else Color.Transparent,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
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
        SettingsGroup("Look", "Black, lettering and glow") {
        SettingRow(
            title = "True black",
            body = "Pure black pages, best on OLED TVs. The colour glow stays.",
            value = SettingValue.Toggle(s.trueBlack),
            onClick = { s.trueBlack = !s.trueBlack },
        )
        SettingRow(
            title = "Title lettering",
            body = "How film and series names are written when there's no logo.",
            value = SettingValue.Choice(listOf("Cinematic", "Clean modern", "Bold condensed")[s.titleFont.coerceIn(0, 2)]),
            onClick = { s.titleFont = (s.titleFont + 1) % 3 },
        )
        SettingRow(
            title = "Glow strength",
            body = "How strongly the theme's colour glows across the page.",
            value = SettingValue.Choice(listOf("Subtle", "Normal", "Vivid")[s.glowLevel.coerceIn(0, 2)]),
            onClick = { s.glowLevel = (s.glowLevel + 1) % 3 },
        )
        }
        SettingsGroup("Card style", "Corners, gloss and names on posters and cards") {
        SettingRow(
            title = "Corners",
            body = "The corners of posters and cards, and an optional thin border.",
            value = SettingValue.Choice(listOf("Rounded", "Square", "Rounded with border")[s.cardStyle.coerceIn(0, 2)]),
            onClick = { s.cardStyle = (s.cardStyle + 1) % 3 },
        )
        SettingRow(
            title = "Glossy cards",
            body = "A soft gloss over the top of each poster, like light on glass (a little brighter on the one you're on).",
            value = SettingValue.Toggle(s.gloss),
            onClick = { s.gloss = !s.gloss },
        )
        SettingRow(
            title = "Names under posters",
            body = "Show each title's name beneath its poster in rows.",
            value = SettingValue.Toggle(s.cardTitles),
            onClick = { s.cardTitles = !s.cardTitles },
        )
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
        SettingsGroup("Motion and sound", "") {
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
                title = "Collection theme music",
                body = "Opening a collection (Harry Potter, Bond…) plays its theme tune softly, when ThemerrDB has one.",
                value = SettingValue.Toggle(s.themeMusic),
                onClick = { s.themeMusic = !s.themeMusic },
            )
            if (s.themeMusic) {
                SettingRow(
                    title = "Theme music volume",
                    body = "How loud the theme plays behind a collection.",
                    value = SettingValue.Choice("${s.themeVolume}%"),
                    onClick = {
                        val options = listOf(15, 30, 45, 60)
                        s.themeVolume = options[(options.indexOf(s.themeVolume) + 1).mod(options.size)]
                    },
                )
            }
            SettingRow(
                title = "Startup sound",
                body = "A short chime as Axon opens.",
                value = SettingValue.Toggle(s.startupSound),
                onClick = { s.startupSound = !s.startupSound },
            )
        }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HomeLayoutPane(mvm: MediaViewModel) {
    val addons by mvm.addons.collectAsState()
    val catalogs by androidx.compose.runtime.produceState(emptyList<com.zapp.tv.addons.CatalogRef>(), addons) { value = mvm.catalogs(null) }
    val rows = remember(catalogs, mvm.settings.homeRowsJson) { mvm.homeLayout(catalogs) }
    val continueCount = mvm.continueWatching.collectAsState().value.size

    fun update(newRows: List<HomeRowSetting>) = mvm.saveLayout(newRows)
    var moving by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<HomeRowSetting?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item(key = "intro") { SettingsCaption("Personal", Modifier.padding(top = 0.dp)) }
        item(key = "rate") {
            SettingRow(
                title = "Rate films and series",
                body = "Like, love or dislike what you've watched. For you, Because you loved and the % match on details pages all come from these.",
                value = SettingValue.Choice("${mvm.ratings.all().size} rated"),
                onClick = { NavShortcuts.rate() },
            )
        }
        item(key = "collections") {
            SettingRow(
                title = "Collections in the sidebar",
                body = if (mvm.tmdb.enabled) "Franchises, studios, decades and themes, on a page of their own."
                else "Needs your TMDB key (Accounts and sync, TMDB). Then franchises, studios, decades and themes get a page of their own.",
                value = SettingValue.Toggle(mvm.settings.libraryCollections && mvm.tmdb.enabled),
                onClick = { if (mvm.tmdb.enabled) mvm.settings.libraryCollections = !mvm.settings.libraryCollections },
            )
        }
        if (mvm.tmdb.enabled && mvm.settings.libraryCollections) {
            item(key = "colShelves") {
                val shelves = mvm.collectionShelfLayout()
                SettingsGroup("Collections layout", "${shelves.count { it.third }} of ${shelves.size} shown", collapsible = true) {
                    shelves.forEachIndexed { i, (id, title, on) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                SettingRow(title = title, body = if (on) "Shown" else "Hidden", value = SettingValue.Toggle(on), onClick = { mvm.setCollectionShelfShown(id, !on) })
                            }
                            if (i > 0) ZButton("Up", { mvm.moveCollectionShelf(id, -1) })
                            if (i < shelves.size - 1) ZButton("Down", { mvm.moveCollectionShelf(id, 1) })
                        }
                    }
                }
            }
        }
        // ---- On Home, in order
        val shown = rows.filter { it.on }
        item(key = "onHead") { LayoutHeading("On Home", "${shown.size} rows  ·  OK for options") }
        items(shown.size, key = { "on:" + shown[it].id }) { i ->
            val r = shown[i]
            LayoutRow(
                r,
                moving = moving == r.id,
                pinned = r.id == CONTINUE_ROW,
                onClick = { if (moving == r.id) moving = null else menuFor = r },
                onMove = { by -> mvm.moveShownRow(rows, r.id, by) },
                onDrop = { moving = null },
            )
        }
        // ---- Not on Home, grouped by where they come from
        val hidden = rows.filter { !it.on }
        if (hidden.isNotEmpty()) {
            item(key = "offHead") { LayoutHeading("Not on Home", "OK to add") }
            hidden.groupBy { it.source.ifBlank { "Other" } }.forEach { (group, list) ->
                item(key = "group:$group") {
                    Text(group.uppercase(), color = ZappColors.Faint, fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
                }
                items(list.size, key = { "off:" + list[it].id }) { i ->
                    val r = list[i]
                    LayoutRow(r, moving = false, pinned = false, onClick = { menuFor = r }, onMove = {}, onDrop = {})
                }
            }
        }
        item(key = "reset") {
            Box(Modifier.padding(top = 12.dp)) {
                ZButton("Reset Home to the default", { confirmReset = true }, style = ButtonStyle.Quiet)
            }
        }
    }

    menuFor?.let { r -> LayoutMenu(mvm, rows, r, onMove = { moving = r.id }) { menuFor = null } }
    if (confirmReset) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmReset = false }) {
            Column(Modifier.width(420.dp).clip(Shapes.Panel).background(ZappColors.Panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reset Home?", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
                Text("Every row goes back to its usual place, size and name.", color = ZappColors.Dim, fontSize = TextSize.Body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZButton("Reset", { mvm.resetLayout(); confirmReset = false }, style = ButtonStyle.Danger)
                    ZButton("Cancel", { confirmReset = false }, style = ButtonStyle.Quiet)
                }
            }
        }
    }
}

@Composable
private fun LayoutHeading(title: String, hint: String) {
    Row(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp, color = ZappColors.Faint)
        Spacer(Modifier.width(10.dp))
        Text(hint, color = ZappColors.Faint, fontSize = TextSize.Label)
    }
}

/** One slim line per row: its name, where it's from, and its size and shape. Moving: Up/Down move it, OK drops it. */
@Composable
private fun LayoutRow(r: HomeRowSetting, moving: Boolean, pinned: Boolean, onClick: () -> Unit, onMove: (Int) -> Unit, onDrop: () -> Unit) {
    val tags = if (!r.on) emptyList() else listOfNotNull(
        listOf("S", "M", "L")[r.size.coerceIn(0, 2)],
        if (r.landscape) "Wide" else "Poster",
        if (r.top10 == true) "Top 10" else null,
    )
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (!moving || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionUp -> { onMove(-1); true }
                    androidx.compose.ui.input.key.Key.DirectionDown -> { onMove(1); true }
                    androidx.compose.ui.input.key.Key.Back, androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight -> { onDrop(); true }
                    else -> false
                }
            },
        shape = Shapes.Row,
        focusScale = 1f,
        restColor = if (moving) ZappColors.GlassHigh else Color.Transparent,
    ) { focused ->
        Row(Modifier.heightIn(min = 42.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (moving) {
                Text("↕", color = if (focused) ZappColors.OnFocus else ZappColors.Brand2, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = TextSize.Body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.name != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("(${r.title})", color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, fontSize = TextSize.Small, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (moving) "Up/Down to move, OK to drop" else if (pinned) "Always first" else r.source,
                color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                fontSize = TextSize.Small,
                maxLines = 1,
            )
            tags.forEach { t ->
                Spacer(Modifier.width(6.dp))
                Text(
                    t,
                    fontSize = TextSize.Label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Dim,
                    modifier = Modifier
                        .border(1.dp, if (focused) Color(0x55000000) else Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** OK on a row in Home layout. */
@Composable
private fun LayoutMenu(mvm: MediaViewModel, rows: List<HomeRowSetting>, r: HomeRowSetting, onMove: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        RenameRowDialog(r, onRename = { n -> mvm.updateRow(rows, r.id, { it.copy(name = n) }) }) {
            renaming = false
            onClose()
        }
        return
    }
    fun change(f: (HomeRowSetting) -> HomeRowSetting) = mvm.updateRow(rows, r.id, f)
    val fixedShape = r.id == CONTINUE_ROW || r.id == NEW_EPISODES_ROW
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(420.dp).clip(Shapes.Panel).background(ZappColors.Panel).padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(r.label, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
            Text(r.source, color = ZappColors.Faint, fontSize = TextSize.Small, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
            if (!r.on) {
                MenuItem("↓", "Add to the bottom of Home", first) { mvm.showRow(rows, r.id, atTop = false); onClose() }
                MenuItem("↑", "Add to the top (under Continue watching)", null) { mvm.showRow(rows, r.id, atTop = true); onClose() }
                MenuItem("✎", "Rename", null) { renaming = true }
            } else {
                if (r.id != CONTINUE_ROW) MenuItem("↕", "Move", first) { onMove(); onClose() }
                MenuItem("✎", "Rename", if (r.id == CONTINUE_ROW) first else null) { renaming = true }
                MenuItem("▣", "Size", null, trailing = listOf("Small", "Medium", "Large")[r.size.coerceIn(0, 2)]) { change { it.copy(size = (it.size + 1) % 3) } }
                if (!fixedShape) {
                    MenuItem("▭", "Shape", null, trailing = if (r.landscape) "Wide" else "Poster") { change { it.copy(landscape = !it.landscape) } }
                    MenuItem("#", "Top 10", null, trailing = if (r.top10 == true) "On" else "Off") { change { it.copy(top10 = it.top10 != true) } }
                }
                if (r.id != CONTINUE_ROW) MenuItem("✕", "Remove from Home", null) { change { it.copy(on = false) }; onClose() }
            }
        }
    }
}

sealed interface SettingValue {
    data class Toggle(val on: Boolean) : SettingValue
    data class Choice(val label: String) : SettingValue
    data object Action : SettingValue
}

/** What the setting you're on does: shown in one line at the foot of Settings, rather than under every row. */
object SettingsHelp {
    var title by mutableStateOf<String?>(null)
    var body by mutableStateOf("")

    fun show(t: String, b: String) {
        if (title != t) title = t
        if (body != b) body = b
    }

    fun clear() {
        title = null
        body = ""
    }
}

/** True inside Settings, where descriptions go to the foot of the page; elsewhere they sit under the row. */
val LocalHelpAtFoot = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun SettingRow(
    title: String,
    body: String,
    value: SettingValue,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val isNew = NewSettings.isNew(title)
    val atFoot = LocalHelpAtFoot.current
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // A hairline between rows.
                val y = size.height + 1.dp.toPx()
                drawLine(Color(0x12FFFFFF), androidx.compose.ui.geometry.Offset(14.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width - 14.dp.toPx(), y), 1f)
            },
        focusRequester = focusRequester,
        // Seen once you move off it (so the dot is there while you read it).
        onFocusChange = { if (!it && isNew) NewSettings.markSeen(title) },
        shape = Shapes.Row,
        focusScale = 1.01f,
    ) { focused ->
        if (focused && atFoot) androidx.compose.runtime.SideEffect { SettingsHelp.show(title, body) }
        Row(
            Modifier
                .heightIn(min = 46.dp)
                .padding(horizontal = 16.dp, vertical = if (atFoot) 10.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = TextSize.Body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (isNew) {
                        Spacer(Modifier.width(8.dp))
                        NewDot()
                    }
                }
                if (!atFoot && body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(body, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = TextSize.Small, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(16.dp))
            when (value) {
                is SettingValue.Toggle -> Switch(value.on, focused)
                is SettingValue.Choice -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value.label,
                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                        fontSize = TextSize.Body,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    ChevronGlyph(if (focused) ZappColors.OnFocusDim else ZappColors.Faint, 9.dp, down = false)
                }
                SettingValue.Action -> ChevronGlyph(if (focused) ZappColors.OnFocusDim else ZappColors.Faint, 10.dp, down = false)
            }
        }
    }
}

@Composable
private fun Switch(checked: Boolean, focused: Boolean) {
    // Small and quiet: a coloured track with a white knob when on, a faint empty track when off.
    val knobX by androidx.compose.animation.core.animateDpAsState(if (checked) 13.dp else 0.dp, androidx.compose.animation.core.tween(160), label = "knob")
    Box(
        Modifier
            .width(30.dp)
            .height(17.dp)
            .clip(Shapes.Pill)
            .background(
                if (checked) Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))
                else Brush.horizontalGradient(listOf(if (focused) Color(0x26000000) else Color(0x24FFFFFF), if (focused) Color(0x26000000) else Color(0x24FFFFFF))),
            )
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .padding(start = knobX)
                .size(11.dp)
                .clip(Shapes.Pill)
                .background(
                    when {
                        checked -> Color.White
                        focused -> Color(0x80000000)
                        else -> Color(0xFF9CA0B2)
                    },
                ),
        )
    }
}

/**
 * A set of settings under a heading. [collapsible] makes the heading a row that opens and closes
 * them (for long blocks such as accounts); otherwise it's a small capitals caption, always open.
 */
@Composable
fun SettingsGroup(title: String, summary: String, startOpen: Boolean = false, collapsible: Boolean = false, help: String = summary, content: @Composable () -> Unit) {
    if (!collapsible) {
        Column(Modifier.fillMaxWidth()) {
            SettingsCaption(title)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
        return
    }
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, androidx.compose.animation.core.tween(200), label = "chevron")
    val atFoot = LocalHelpAtFoot.current
    Column(Modifier.fillMaxWidth()) {
        FocusSurface(
            onClick = { open = !open },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val y = size.height + 1.dp.toPx()
                    drawLine(Color(0x12FFFFFF), androidx.compose.ui.geometry.Offset(14.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width - 14.dp.toPx(), y), 1f)
                },
            onFocusChange = { if (!it) NewSettings.markSeen("group:$title") },
            shape = Shapes.Row,
            focusScale = 1.01f,
        ) { focused ->
            if (focused && atFoot) androidx.compose.runtime.SideEffect { SettingsHelp.show(title, help) }
            Row(Modifier.heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = TextSize.Body, fontWeight = FontWeight.Medium, color = if (focused) ZappColors.OnFocus else ZappColors.Text)
                if (NewSettings.groupHasNew(title)) {
                    Spacer(Modifier.width(8.dp))
                    NewDot()
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    summary,
                    fontSize = TextSize.Body,
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.graphicsLayer { rotationZ = turn }) {
                    ChevronGlyph(if (focused) ZappColors.OnFocusDim else ZappColors.Faint, 10.dp, down = false)
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        ) {
            Column(
                Modifier.padding(start = 12.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { content() }
        }
    }
}

/** Small capitals over a set of rows. */
@Composable
fun SettingsCaption(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        fontSize = TextSize.Label,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.3.sp,
        color = ZappColors.Faint,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** The foot of Settings: what the setting you're on does. */
@Composable
private fun HelpFoot() {
    val t = SettingsHelp.title
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .drawBehind { drawLine(Color(0x14FFFFFF), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1f) }
            .padding(top = 12.dp),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = t to SettingsHelp.body,
            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
            label = "help",
        ) { (title, body) ->
            if (title != null && body.isNotBlank()) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = ZappColors.Text, fontWeight = FontWeight.Medium))
                        append(title)
                        pop()
                        append("   ")
                        append(body)
                    },
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A small line icon for each Settings section. */
@Composable
private fun SectionIcon(section: SettingsSection, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val u = size.width / 24f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(1.7f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, o(x1, y1), o(x2, y2), st.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) = drawRoundRect(color, o(x, y), androidx.compose.ui.geometry.Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(r * u), style = st)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * u, o(x, y), style = st)
        fun path(vararg p: Pair<Float, Float>, close: Boolean = false) = drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(p[0].first * u, p[0].second * u)
                p.drop(1).forEach { lineTo(it.first * u, it.second * u) }
                if (close) close()
            },
            color, style = st,
        )
        when (section) {
            SettingsSection.Sources -> { rect(3f, 5f, 18f, 12f, 2f); line(8f, 21f, 16f, 21f) }
            SettingsSection.Addons -> { rect(4f, 4f, 7f, 7f, 1.5f); rect(13f, 4f, 7f, 7f, 1.5f); rect(4f, 13f, 7f, 7f, 1.5f); line(16.5f, 13.5f, 16.5f, 19.5f); line(13.5f, 16.5f, 19.5f, 16.5f) }
            SettingsSection.Accounts -> { circle(12f, 8f, 4f); path(4f to 21f, 7f to 16.5f, 12f to 15f, 17f to 16.5f, 20f to 21f) }
            SettingsSection.HomeLayout -> { rect(3f, 4f, 18f, 6f, 2f); rect(3f, 14f, 18f, 6f, 2f) }
            SettingsSection.Appearance -> { circle(12f, 12f, 9f); drawArc(color, 90f, 180f, true, o(3f, 3f), androidx.compose.ui.geometry.Size(18f * u, 18f * u)) }
            SettingsSection.Playback -> path(7f to 5f, 19f to 12f, 7f to 19f, close = true)
            SettingsSection.Live -> { rect(3f, 7f, 18f, 13f, 2f); path(8f to 3f, 12f to 7f, 16f to 3f) }
            SettingsSection.Profiles -> { circle(9f, 9f, 3.5f); circle(17f, 10f, 2.5f); path(3f to 20f, 5.5f to 16f, 9f to 15f, 12.5f to 16f, 15f to 20f); path(15.5f to 15.2f, 17f to 15f, 19.5f to 16f, 21f to 19f) }
            SettingsSection.About -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 17f); drawCircle(color, 1.1f * u, o(12f, 7.6f)) }
            SettingsSection.Phone -> { rect(7f, 3f, 10f, 18f, 2.5f); line(11f, 18f, 13f, 18f) }
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
            Text("Set up from your phone", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
            Text(
                "Scan the code with your phone's camera (same Wi-Fi as the TV). Typing there is much quicker than the remote, and everything appears here straight away.",
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
                lineHeight = 18.sp,
            )
            listOf(
                "Playlist" to "Xtream logins and M3U links, with a TV guide link",
                "Add-on" to "Stremio add-ons by their install link",
                "Accounts" to "Stremio and Nuvio sign-in, the Simkl app ID, TMDB, MDBList, Premiumize and TorBox keys",
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
                        Text(t, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                        Text(b, color = ZappColors.Dim, fontSize = TextSize.Small)
                    }
                }
            }
        }
    }
}
