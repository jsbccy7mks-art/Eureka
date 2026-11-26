@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.ui.draw.blur
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.compose.collectAsLazyPagingItems
import com.zapp.tv.data.ChannelRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Dest(val label: String, val glyph: Glyph) {
    Home("Home", Glyph.Home),
    Search("Search", Glyph.Search),
    Live("Live TV", Glyph.Live),
    Guide("TV guide", Glyph.Guide),
    Movies("Movies", Glyph.Movies),
    Series("Series", Glyph.Series),
    Library("Library", Glyph.Library),
    Settings("Settings", Glyph.Settings),
}

private val RailWidth = 72.dp
private val RailOpenWidth = 220.dp

@Composable
fun ZappRoot(vm: AppViewModel) {
    val mvm: MediaViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playback = remember { Playback(context.applicationContext, coroutineScope, vm.settings) { vm.resolveUrl(it) } }
    val playlists by vm.playlists.collectAsState()
    val installedAddons by mvm.addons.collectAsState()

    var dest by rememberSaveable { mutableStateOf(Dest.Home) }
    var playerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<Boolean?>(null) } // true = Xtream, false = M3U
    var focusTick by remember { mutableIntStateOf(0) }
    var exitArmed by remember { mutableStateOf(false) }
    // The sidebar opens only when you go to it (Left from the page, or Back). If focus lands on it any
    // other way (something on the page closed under the remote), it's sent straight back to the page.
    var railFocused by remember { mutableStateOf(false) }
    var railIntentAt by remember { mutableStateOf(0L) }
    var rootHasFocus by remember { mutableStateOf(true) }
    var settingsSection by remember { mutableStateOf<String?>(null) }

    // Films and series: detail page, then source list, then player.
    var detail by remember { mutableStateOf<DetailRequest?>(null) }
    var playRequest by remember { mutableStateOf<PlayRequest?>(null) }
    var vodOpen by remember { mutableStateOf(false) }
    val railFocus = remember { FocusRequester() }
    var launching by rememberSaveable { mutableStateOf(true) }
    // Just closed a title, the sources or the player: the sidebar can't take focus for a moment, so
    // focus goes back to the card you were on instead of falling onto the sidebar.
    var railLockUntil by remember { mutableStateOf(0L) }
    fun lockRail() {
        railLockUntil = System.currentTimeMillis() + 900
    }

    // A details page has its own focus requests, so the page underneath never takes focus while
    // it's showing (that left the remote "stuck" on a page you couldn't see).
    var detailTick by remember { mutableIntStateOf(0) }

    /** Ask the visible screen (or the open details page) to take focus once it has been drawn. */
    fun requestContentFocus(waitMs: Long = 120) {
        coroutineScope.launch {
            delay(waitMs)
            if (detail != null) detailTick++ else focusTick++
        }
    }

    LaunchedEffect(Unit) { vm.onStremioSignedIn = { mvm.stremioSignedInFromPhone() } }
    LaunchedEffect(Unit) { vm.onNuvioSignedIn = { mvm.nuvioSignedInFromPhone() } }
    // Trailers stop whenever something covers them.
    TrailerGate.playing = playRequest != null || vodOpen || playerOpen
    TrailerGate.detailOpen = detail != null
    WatchedMarks.ids = mvm.watchedTitles.collectAsState().value

    // ---- profiles
    val profiles by mvm.profiles.collectAsState()
    var choosingProfile by remember { mutableStateOf(false) }
    var askedAtStart by rememberSaveable { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<com.zapp.tv.data.Profile?>(null) }
    var settingsPin by remember { mutableStateOf(false) }
    val kids = mvm.isKids
    LaunchedEffect(profiles.isNotEmpty()) {
        if (profiles.isEmpty() || askedAtStart) return@LaunchedEffect
        askedAtStart = true
        if (profiles.size > 1 && mvm.settings.askProfileOnStart) choosingProfile = true
    }

    fun open(d: Dest) {
        // Kids profiles: no live TV; Settings needs the main profile's PIN when it has one.
        if (kids && (d == Dest.Live || d == Dest.Guide)) return
        if (kids && d == Dest.Settings && profiles.firstOrNull { it.id == 1L }?.pin != null) {
            settingsPin = true
            return
        }
        dest = d
        requestContentFocus()
    }

    LaunchedEffect(kids) { if (kids && (dest == Dest.Live || dest == Dest.Guide)) dest = Dest.Home }

    fun useProfile(p: com.zapp.tv.data.Profile) {
        mvm.switchProfile(p.id)
        choosingProfile = false
        pinFor = null
        playback.stop()
        dest = Dest.Home
        StartFocus.fresh = true
        requestContentFocus(250)
    }

    NavShortcuts.search = { open(Dest.Search) }
    NavShortcuts.settings = { open(Dest.Settings) }

    fun pickProfile(p: com.zapp.tv.data.Profile) {
        if (p.pin != null && p.id != mvm.settings.profileId) pinFor = p else useProfile(p)
    }

    fun watch(row: ChannelRow) {
        vm.lastChannelUrl = row.channel.streamUrl
        playback.play(row.channel)
        playerOpen = true
    }

    fun openTitle(meta: com.zapp.tv.addons.MetaPreview) {
        detail = DetailRequest(meta.type, meta.id, meta)
    }

    var starting by remember { mutableStateOf(false) }
    /** The source now playing (its link or torrent), so a failing one can be skipped. */
    var currentSourceKey by remember { mutableStateOf<String?>(null) }
    // For "sources that learn": the source now starting or playing, and when Play was pressed.
    var currentStream by remember { mutableStateOf<com.zapp.tv.addons.StreamItem?>(null) }
    var streamPickedAt by remember { mutableStateOf(0L) }
    var streamStartedAt by remember { mutableStateOf(0L) }

    fun startStream(req: PlayRequest, stream: com.zapp.tv.addons.StreamItem, resolvedUrl: String? = null) {
        if (resolvedUrl == null) {
            currentStream = stream
            streamPickedAt = System.currentTimeMillis()
            streamStartedAt = 0L
        }
        if (resolvedUrl == null && stream.url.isNullOrBlank() && stream.infoHash != null) {
            // Torrent source: ask the debrid service for a link first.
            mvm.resolveTorrent(
                stream = stream,
                title = req.meta.name,
                onReady = { url -> startStream(req, stream, url) },
                onError = { msg ->
                    mvm.sourceStats.failed(stream)
                    vm.showToast(msg)
                },
            )
            return
        }
        currentSourceKey = stream.url ?: stream.infoHash
        if (!req.videoId.startsWith("catchup:")) mvm.rememberSource(req.meta.id, req.videoId, stream)
        val info = MediaInfo(
            videoId = req.videoId,
            metaId = req.meta.id,
            type = req.meta.type,
            title = req.meta.name,
            episodeLabel = req.episodeLabel,
            poster = req.meta.poster,
            background = req.video?.thumbnail ?: req.meta.background,
            url = resolvedUrl ?: stream.url.orEmpty(),
            headers = stream.headers,
        )
        // Add-on subtitles were fetched while the sources loaded; wait a moment for them if needed.
        if (starting) return
        starting = true
        coroutineScope.launch {
            val subs = try {
                mvm.subtitlesFor(req.meta.type, req.videoId)
            } catch (e: Exception) {
                emptyList()
            }
            playback.playMedia(info.copy(subtitles = subs), req.resumeMs, req.resumePercent)
            playRequest = req.copy(preferBingeGroup = stream.bingeGroup, autoPlay = false)
            vodOpen = true
            starting = false
        }
    }

    /** Replays a past programme (catch-up) in the film player, from its start. */
    /** Replays a past programme on [channel] from its start (from the guide, or "Watch from the start"). */
    fun playCatchUpOn(channel: com.zapp.tv.data.Channel, p: com.zapp.tv.data.Programme) {
        val row = object { val channel = channel }
        val url = vm.catchupUrl(channel, p.start, p.stop)
        if (url == null) {
            vm.showToast("This programme can't be replayed")
            return
        }
        val fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(p.start)
        val info = MediaInfo(
            videoId = "catchup:${row.channel.id}:${p.start}",
            metaId = "catchup",
            type = "tv",
            title = p.title,
            episodeLabel = "${row.channel.name}, $day ${fmt.format(p.start)}",
            poster = p.icon ?: row.channel.logo,
            background = p.icon,
            url = url,
            headers = emptyMap(),
        )
        playback.stop()
        playback.playMedia(info, 0)
        playRequest = PlayRequest(
            meta = com.zapp.tv.addons.MetaPreview("catchup", "tv", p.title, p.icon, "landscape", p.icon, null, p.description, null, null, emptyList()),
            video = null,
            videos = emptyList(),
            resumeMs = 0,
            autoPlay = false,
        )
        vodOpen = true
    }

    fun playCatchUp(row: ChannelRow, p: com.zapp.tv.data.Programme) = playCatchUpOn(row.channel, p)

    fun watchChannel(channel: com.zapp.tv.data.Channel) {
        vm.lastChannelUrl = channel.streamUrl
        playback.play(channel)
        playerOpen = true
    }

    DisposableEffect(Unit) { onDispose { playback.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                TrailerGate.appVisible = true
                mvm.onAppResumed()
            }
            if (event == Lifecycle.Event.ON_STOP) {
                TrailerGate.appVisible = false
                playback.media?.let { m ->
                    val d = playback.player.duration
                    if (d > 0) mvm.saveProgress(m, playback.player.currentPosition, d)
                }
                playback.stop()
                playerOpen = false
                vodOpen = false
                playRequest = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm.loaded, playlists.isEmpty()) {
        if (vm.loaded && playlists.isNotEmpty()) requestContentFocus(300)
    }

    // Home and Live TV keep a preview running; elsewhere the player stops.
    LaunchedEffect(dest, playerOpen) {
        if (!playerOpen && !vodOpen && dest != Dest.Live && dest != Dest.Home) playback.stop()
    }

    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(2_500)
            exitArmed = false
        }
    }

    // Safety net: if nothing has focus (the remote would do nothing), give it back to the page.
    LaunchedEffect(rootHasFocus) {
        if (!rootHasFocus) {
            delay(450)
            // (Not while a dialog has the remote: then this window doesn't have focus on purpose.)
            if (!rootHasFocus && (context as? Activity)?.hasWindowFocus() != false) requestContentFocus(0)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { rootHasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionLeft) {
                    railIntentAt = System.currentTimeMillis()
                }
                false
            },
    ) {
        AmbientBackground()

        when {
            !vm.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZappLogo(34.dp)
            }

            // Add-on-only setups skip the playlist welcome.
            playlists.isEmpty() && installedAddons.isEmpty() && !mvm.settings.welcomeSkipped ->
                WelcomeScreen(vm, onForm = { form = it }, onSkip = {
                    mvm.settings.welcomeSkipped = true
                    settingsSection = "Addons"
                    dest = Dest.Settings
                    requestContentFocus(300)
                })

            else -> {
                // Back from a screen goes to the sidebar; Back on the sidebar twice exits.
                BackHandler(enabled = !playerOpen && form == null && detail == null && playRequest == null) {
                    if (!railFocused) {
                        railIntentAt = System.currentTimeMillis()
                        railFocused = true
                        railFocus.tryRequest()
                    } else if (exitArmed) {
                        (context as? Activity)?.finish()
                    } else {
                        exitArmed = true
                        vm.showToast("Press back again to exit")
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = RailWidth),
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = dest,
                        transitionSpec = {
                            (fadeIn(tween(280, delayMillis = 70)) + androidx.compose.animation.scaleIn(tween(320, delayMillis = 70), initialScale = 0.985f)) togetherWith
                                fadeOut(tween(140))
                        },
                        label = "page",
                    ) { shown ->
                    when (shown) {
                        Dest.Home -> HomeScreen(
                            vm = vm,
                            playback = playback,
                            focusTick = focusTick,
                            mvm = mvm,
                            onOpenTitle = { openTitle(it) },
                            onResume = { p ->
                                detail = DetailRequest(p.type, p.metaId, null)
                            },
                            onOpenAddons = {
                                settingsSection = "Addons"
                                open(Dest.Settings)
                            },
                        )
                        Dest.Movies -> BrowseScreen(mvm, "movie", focusTick, { openTitle(it) }) {
                            settingsSection = "Addons"
                            open(Dest.Settings)
                        }
                        Dest.Series -> BrowseScreen(mvm, "series", focusTick, { openTitle(it) }) {
                            settingsSection = "Addons"
                            open(Dest.Settings)
                        }
                        Dest.Library -> LibraryScreen(mvm, focusTick) { openTitle(it) }
                        Dest.Live -> Column(Modifier.fillMaxSize()) {
                            ScreenHeader("Live TV", vm.guideStatus)
                            LiveScreen(vm, playback, focusTick) { watch(it) }
                        }
                        Dest.Guide -> Column(Modifier.fillMaxSize()) {
                            ScreenHeader("TV guide", vm.guideStatus)
                            GuideScreen(vm, focusTick, onCatchUp = { row, p -> playCatchUp(row, p) }) { watch(it) }
                        }
                        Dest.Search -> SearchScreen(vm, playback, focusTick, mvm, { openTitle(it) }) { watchChannel(it) }
                        Dest.Settings -> SettingsScreen(
                            mvm = mvm,
                            initialSection = settingsSection,
                            vm = vm,
                            playback = playback,
                            focusTick = focusTick,
                            onForm = { form = it },
                            onWatchPlaylist = { id ->
                                vm.selectPlaylist(id)
                                open(Dest.Live)
                            },
                        )
                    }
                    }
                }

                var railTick by remember { mutableIntStateOf(0) }
                LaunchedEffect(railLockUntil) {
                    val wait = railLockUntil - System.currentTimeMillis()
                    if (wait > 0) {
                        delay(wait)
                        railTick++
                    }
                }
                Sidebar(
                    locked = (railTick >= 0 && System.currentTimeMillis() < railLockUntil) || detail != null || playRequest != null,
                    onRight = { requestContentFocus(0) },
                    dests = Dest.entries.filter { !kids || (it != Dest.Live && it != Dest.Guide) },
                    profile = mvm.currentProfile?.takeIf { profiles.size > 1 || it.kids },
                    onProfiles = { choosingProfile = true },
                    current = dest,
                    updateReady = vm.update is UpdateState.Available,
                    railFocus = railFocus,
                    open = railFocused,
                    onOpenChange = { railFocused = it },
                    intended = { System.currentTimeMillis() - railIntentAt < 700 },
                    onStray = { requestContentFocus(0) },
                    onSelect = { open(it) },
                )
            }
        }

        if (choosingProfile && profiles.isNotEmpty()) {
            WhoIsWatching(
                profiles = profiles,
                currentId = mvm.settings.profileId,
                canClose = true,
                onPick = { pickProfile(it) },
                onClose = {
                    choosingProfile = false
                    requestContentFocus()
                },
            )
        }
        pinFor?.let { p ->
            PinPad(
                title = "PIN for ${p.name}",
                subtitle = null,
                check = { mvm.pinMatches(p, it) },
                onDone = { useProfile(p) },
                onCancel = { pinFor = null },
            )
        }
        if (settingsPin) {
            val main = profiles.firstOrNull { it.id == 1L }
            PinPad(
                title = "Settings are locked",
                subtitle = "Enter ${main?.name ?: "the main profile"}'s PIN",
                check = { pin -> main != null && mvm.pinMatches(main, pin) },
                onDone = {
                    settingsPin = false
                    dest = Dest.Settings
                    requestContentFocus()
                },
                onCancel = { settingsPin = false },
            )
        }
        // Opening animation (once per launch) while Home loads behind it.
        if (launching) {
            LaunchScreen(
                sound = mvm.settings.startupSound,
                ready = { HomeReady.ready || dest != Dest.Home },
                onDone = {
                    launching = false
                    StartFocus.fresh = true
                    requestContentFocus(0)
                },
            )
        }

        form?.let { xtream ->
            PlaylistForm(
                xtream = xtream,
                vm = vm,
                onDone = {
                    form = null
                    dest = Dest.Live
                    requestContentFocus(400)
                },
                onCancel = {
                    form = null
                    requestContentFocus()
                },
            )
        }

        // Details glide in over the page and fade away again.
        var shownDetail by remember { mutableStateOf<DetailRequest?>(null) }
        if (detail != null) shownDetail = detail
        AnimatedVisibility(
            visible = detail != null,
            enter = fadeIn(tween(260)) + androidx.compose.animation.scaleIn(tween(320), initialScale = 0.97f),
            exit = fadeOut(tween(180)) + androidx.compose.animation.scaleOut(tween(180), targetScale = 0.985f),
        ) {
            shownDetail?.let { d ->
                DetailScreen(
                    mvm = mvm,
                    request = d,
                    focusTick = detailTick,
                    onPlay = { playRequest = it },
                    onOpenTitle = { m -> detail = DetailRequest(m.type, m.id, m) },
                    onClose = {
                        lockRail()
                        detail = null
                        requestContentFocus()
                    },
                )
            }
        }

        val pr = playRequest
        if (pr != null && !vodOpen) {
            SourcesOverlay(
                mvm = mvm,
                request = pr,
                onPick = { stream -> startStream(pr, stream) },
                onClose = {
                    lockRail()
                    playRequest = null
                    requestContentFocus()
                },
            )
        }

        if (vodOpen && pr != null && playback.media != null) {
            VodPlayer(
                vm = vm,
                mvm = mvm,
                playback = playback,
                request = pr,
                onFirstFrame = {
                    currentStream?.let { s ->
                        if (streamStartedAt == 0L) {
                            streamStartedAt = System.currentTimeMillis()
                            mvm.sourceStats.started(s, streamStartedAt - streamPickedAt)
                        }
                    }
                },
                onChooseSource = {
                    // Switching away within two minutes of it starting counts against that source.
                    currentStream?.let { s -> if (streamStartedAt > 0 && System.currentTimeMillis() - streamStartedAt < 120_000) mvm.sourceStats.switchedAway(s) }
                    val at = playback.player.currentPosition
                    vodOpen = false
                    playback.stop()
                    playRequest = pr.copy(resumeMs = at, autoPlay = false)
                },
                onSourceFailed = {
                    currentStream?.let { mvm.sourceStats.failed(it) }
                    val at = playback.player.currentPosition
                    vodOpen = false
                    playback.stop()
                    vm.showToast("That source stopped working. Trying another…")
                    playRequest = pr.copy(resumeMs = at, autoPlay = true, failed = pr.failed + listOfNotNull(currentSourceKey))
                },
                onPlayNext = { next ->
                    vodOpen = false
                    playback.stop()
                    playRequest = pr.copy(video = next, resumeMs = 0, autoPlay = true)
                },
                onClose = {
                    vodOpen = false
                    playRequest = null
                    playback.stop()
                    requestContentFocus(60)
                },
            )
        }

        mvm.busy?.let { BusyOverlay(it) }
        // While a film is starting, the sources screen shows this itself.
        mvm.resolving?.let { if (playRequest == null || vodOpen) BusyOverlay(it) }

        if (playerOpen && playback.channel != null) {
            val rows = vm.channels.collectAsLazyPagingItems()
            PlayerOverlay(
                vm = vm,
                playback = playback,
                rows = rows,
                onClose = {
                    playerOpen = false
                    requestContentFocus(60)
                },
                onRestart = { c, p ->
                    playerOpen = false
                    playCatchUpOn(c, p)
                },
            )
        }

        vm.busy?.let { BusyOverlay(it) }

        ToastHost(
            message = vm.toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

/** Title row above Live TV and the guide, with the guide status and a clock. */
@Composable
private fun ScreenHeader(title: String, guideStatus: String?) {
    val now = rememberNow(15_000)
    val timeFormat = rememberTimeFormat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(start = 28.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
        Spacer(Modifier.weight(1f))
        if (guideStatus != null) {
            CircularProgressIndicator(color = ZappColors.Dim, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(8.dp))
            Text(guideStatus, color = ZappColors.Dim, fontSize = 12.sp)
            Spacer(Modifier.width(20.dp))
        }
        HeaderShortcuts()
        Spacer(Modifier.width(14.dp))
        Text(timeFormat.format(now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Icon rail that opens into a labelled menu while the remote is on it. */
@Composable
private fun Sidebar(
    locked: Boolean,
    onRight: () -> Unit,
    dests: List<Dest>,
    profile: com.zapp.tv.data.Profile?,
    onProfiles: () -> Unit,
    current: Dest,
    updateReady: Boolean,
    railFocus: FocusRequester,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    intended: () -> Boolean,
    onStray: () -> Unit,
    onSelect: (Dest) -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val width by animateDpAsState(if (open) RailOpenWidth else RailWidth, tween(200), label = "rail")
    // The selection bar follows the title's colour too, so the sidebar matches the page.
    val sideTint by androidx.compose.animation.animateColorAsState(
        (if (FocusStyle.artGlow) ArtTint.color else null) ?: ZappColors.Brand1,
        tween(900),
        label = "sideTint",
    )
    val scrim by androidx.compose.animation.core.animateFloatAsState(if (open) 1f else 0f, tween(200), label = "scrim")

    // Closed: a slim column of icons floating over the page. Open: labels slide out over a soft fade.
    Box(
        Modifier
            .width(RailOpenWidth + 120.dp)
            .fillMaxHeight(),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = scrim }
                .background(
                    Brush.horizontalGradient(
                        0f to ZappColors.Bg.copy(alpha = 0.97f),
                        0.55f to ZappColors.Bg.copy(alpha = 0.85f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                // The whole sidebar (destinations, Settings and the profile) opens while the remote
                // is anywhere on it, and closes the moment focus leaves it.
                .onFocusChanged {
                    if (it.hasFocus == hasFocus) return@onFocusChanged
                    hasFocus = it.hasFocus
                    when {
                        !it.hasFocus -> onOpenChange(false)
                        intended() -> onOpenChange(true)
                        else -> onStray() // landed here by accident: back to the page, closed
                    }
                }
                .focusProperties { enter = { railFocus } }
                .onPreviewKeyEvent { e ->
                    // Right goes back to where you were on the page. If the page can't take focus
                    // (still loading), focus moves right anyway, so the sidebar always closes.
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionRight) {
                        onOpenChange(false)
                        onRight()
                        scope.launch {
                            delay(220)
                            if (hasFocus) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
                        }
                        true
                    } else {
                        false
                    }
                }
                .focusGroup()
                .padding(start = 16.dp, end = 12.dp, top = 26.dp, bottom = 26.dp),
        ) {
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(24.dp)
                AnimatedVisibility(visible = open, enter = fadeIn(tween(200)), exit = fadeOut(tween(80))) {
                    Text("Zapp", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            @Composable
            fun Item(d: Dest) {
                    val selected = d == current
                    FocusSurface(
                        onClick = { onSelect(d) },
                        modifier = Modifier
                            .focusProperties { canFocus = !locked }
                            .width(if (open) RailOpenWidth - 28.dp else 44.dp),
                        focusRequester = if (selected) railFocus else null,
                        shape = RoundedCornerShape(12.dp),
                        focusScale = 1f,
                    ) { focused ->
                        Row(
                            Modifier.height(42.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The current page: a short gradient bar beside its icon.
                            Box(
                                Modifier
                                    .padding(start = 3.dp)
                                    .width(3.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (selected && !focused) Brush.verticalGradient(listOf(sideTint, ZappColors.Brand2))
                                        else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                                    ),
                            )
                            Box(Modifier.padding(start = 8.dp)) {
                                GlyphIcon(
                                    d.glyph,
                                    when {
                                        focused -> ZappColors.OnFocus
                                        selected -> ZappColors.Text
                                        else -> ZappColors.Faint
                                    },
                                    20.dp,
                                )
                                if (d == Dest.Settings && updateReady) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .size(6.dp)
                                            .background(ZappColors.Brand2, RoundedCornerShape(50)),
                                    )
                                }
                            }
                            AnimatedVisibility(visible = open, enter = fadeIn(tween(200)), exit = fadeOut(tween(80))) {
                                Text(
                                    d.label,
                                    color = when {
                                        focused -> ZappColors.OnFocus
                                        selected -> ZappColors.Text
                                        else -> ZappColors.Dim
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 14.dp),
                                )
                            }
                        }
                    }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                dests.filter { it != Dest.Settings }.forEach { Item(it) }
            }
            Spacer(Modifier.weight(1.3f))
            // Settings stays at the bottom.
            if (Dest.Settings in dests) {
                Item(Dest.Settings)
                Spacer(Modifier.height(6.dp))
            }
            // Who's watching: tap to switch profile.
            if (profile != null) {
                FocusSurface(
                    onClick = onProfiles,
                    modifier = Modifier
                        .focusProperties { canFocus = !locked }
                        .width(if (open) RailOpenWidth - 28.dp else 44.dp),
                    shape = RoundedCornerShape(12.dp),
                    focusScale = 1f,
                ) { focused ->
                    Row(Modifier.height(44.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile, 28.dp)
                        AnimatedVisibility(visible = open, enter = fadeIn(tween(200)), exit = fadeOut(tween(80))) {
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(profile.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else ZappColors.Text, maxLines = 1)
                                Text("Switch profile", fontSize = 10.5.sp, color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
