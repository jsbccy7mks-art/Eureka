package com.zapp.tv.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.zapp.tv.addons.Video
import kotlinx.coroutines.delay

private enum class VodPanel { None, Controls, Audio, Subtitles, SubStyle, Details }

/**
 * Player for films and episodes.
 * Left/Right: back 10 s / forward 10 s (hold to go faster). OK: controls. Play/Pause works anywhere.
 * Progress is saved as you watch, so you can resume later.
 */
@Composable
fun VodPlayer(
    vm: AppViewModel,
    mvm: MediaViewModel,
    playback: Playback,
    request: PlayRequest,
    onChooseSource: () -> Unit,
    onFirstFrame: () -> Unit = {},
    onPlayNext: (Video) -> Unit,
    /** From the end screen: play a suggestion, or open its details. */
    onPlaySuggestion: (com.zapp.tv.addons.MetaPreview) -> Unit = {},
    onSuggestionDetails: (com.zapp.tv.addons.MetaPreview) -> Unit = {},
    onClose: () -> Unit,
    onSourceFailed: () -> Unit = {},
) {
    val info = playback.media ?: return
    val player = playback.player
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    var panel by remember { mutableStateOf(VodPanel.None) }
    var barTick by remember { mutableIntStateOf(0) }
    var barVisible by remember { mutableStateOf(true) }
    var idleTick by remember { mutableIntStateOf(0) }
    var ended by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var nextCard by remember(info.videoId) { mutableStateOf(false) }
    val currentInfo by rememberUpdatedState(info)
    val next = remember(request.videoId) { request.video?.let { mvm.nextEpisode(request.videos, it.id) } }
    // Until the first picture appears, the Zapp starting screen stays up (it carries on from the
    // sources screen, so starting a film looks like one smooth step).
    var started by remember(info.videoId) { mutableStateOf(false) }
    LaunchedEffect(started) { if (started) onFirstFrame() }
    LaunchedEffect(info.videoId) {
        var playingFor = 0
        while (!started) {
            delay(250)
            playingFor = if (player.isPlaying) playingFor + 1 else 0
            if (playingFor >= 6) started = true // fallback if no first-frame signal arrives
        }
    }

    fun save() {
        val d = player.duration
        if (d > 0 && d != C.TIME_UNSET) {
            mvm.saveProgress(currentInfo, player.currentPosition, d)
            if (player.currentPosition >= d * 0.9) next?.let { mvm.markUpNext(currentInfo, it) }
        }
    }

    fun percentNow(): Double {
        val d = player.duration
        return if (d > 0 && d != C.TIME_UNSET) player.currentPosition * 100.0 / d else 0.0
    }

    // Save before leaving: the player stops right after these, so the position must be read first.
    fun close() {
        save()
        mvm.scrobble("stop", currentInfo, percentNow(), player.duration.coerceAtLeast(0))
        onClose()
    }

    fun playNext(v: Video) {
        save()
        mvm.scrobble("stop", currentInfo, percentNow(), player.duration.coerceAtLeast(0))
        onPlayNext(v)
    }

    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
        playback.fullscreen = true
        view.keepScreenOn = true
        // Frame rate / resolution matching: once per video, from its first known format.
        var matchedFor: String? = null
        fun matchDisplay() {
            val a = activity ?: return
            val f = player.videoFormat ?: return
            if (matchedFor == currentInfo.videoId) return
            if (f.frameRate <= 0f && f.height <= 0) return
            matchedFor = currentInfo.videoId
            DisplayMatch.apply(a, f.frameRate, f.height, mvm.settings.matchFrameRate, mvm.settings.matchResolution)
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                started = true
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                matchDisplay()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    save()
                    mvm.scrobble("stop", currentInfo, 100.0, player.duration.coerceAtLeast(0))
                    ended = true
                }
            }

            // Tell Simkl when watching starts and pauses, so Nuvio and other apps see it.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                paused = !isPlaying && player.playbackState == Player.STATE_READY && !player.playWhenReady
                if (isPlaying) {
                    mvm.scrobble("start", currentInfo, percentNow(), player.duration.coerceAtLeast(0))
                } else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    mvm.scrobble("pause", currentInfo, percentNow(), player.duration.coerceAtLeast(0))
                }
            }
        }
        player.addListener(listener)
        matchDisplay()
        onDispose {
            save()
            activity?.let { DisplayMatch.reset(it) }
            player.removeListener(listener)
            playback.fullscreen = false
            view.keepScreenOn = false
        }
    }

    // Save progress every 15 seconds while playing.
    LaunchedEffect(info.videoId) {
        ended = false
        while (true) {
            delay(15_000)
            if (player.isPlaying) save()
        }
    }

    // ---- the end screen: when a film (or the latest episode) reaches its credits, it shrinks to a
    // small window and something to watch next fills the screen.
    var endScreen by remember(info.videoId) { mutableStateOf(false) }
    var endDismissed by remember(info.videoId) { mutableStateOf(false) }

    BackHandler {
        if (endScreen) {
            endScreen = false
            endDismissed = true
            focus.tryRequest()
            return@BackHandler
        }
        panel = when (panel) {
            VodPanel.SubStyle -> VodPanel.Subtitles
            VodPanel.Audio, VodPanel.Subtitles, VodPanel.Details -> VodPanel.Controls
            VodPanel.Controls -> VodPanel.None
            VodPanel.None -> {
                close()
                VodPanel.None
            }
        }
    }

    LaunchedEffect(panel) { if (panel == VodPanel.None) focus.tryRequest() }
    LaunchedEffect(playback.error) {
        if (playback.error == null) return@LaunchedEffect
        // With autoplay on, a source that stops working is swapped for the next one by itself.
        if (mvm.settings.autoPlayFirst && !info.videoId.startsWith("catchup:")) {
            delay(1_200)
            save()
            onSourceFailed()
        } else {
            panel = VodPanel.Controls
        }
    }
    LaunchedEffect(barTick) {
        barVisible = true
        delay(4_000)
        barVisible = false
    }
    LaunchedEffect(idleTick, panel) {
        if (panel == VodPanel.Controls && playback.error == null) {
            // Playing: controls go after 5 s. Paused: after 7 s, and the paused view takes over.
            delay(if (player.isPlaying) 5_000 else 7_000)
            panel = VodPanel.None
        }
    }

    // ---- IntroDB: skip intros and recaps, and offer the next episode when the credits start.
    val segments by produceState<com.zapp.tv.data.Segments?>(null, info.videoId) { value = mvm.segments(info.videoId, info.type) }
    val positionNow by produceState(0L, info.videoId) {
        while (true) {
            value = player.currentPosition
            delay(400)
        }
    }
    val inIntro = segments?.intro?.let { positionNow in it } == true
    val inRecap = segments?.recap?.let { positionNow in it } == true
    val skipTarget = when {
        inIntro && mvm.settings.skipIntro > 0 -> segments?.intro
        inRecap && mvm.settings.skipRecap > 0 -> segments?.recap
        else -> null
    }
    var autoSkipped by remember(info.videoId) { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(skipTarget) {
        val t = skipTarget ?: return@LaunchedEffect
        val auto = (t == segments?.intro && mvm.settings.skipIntro == 2) || (t == segments?.recap && mvm.settings.skipRecap == 2)
        if (auto && t.startMs !in autoSkipped) {
            autoSkipped = autoSkipped + t.startMs
            player.seekTo(t.endMs)
            vm.showToast(if (t == segments?.intro) "Skipped the intro" else "Skipped the recap")
        }
    }
    // End credits (and no scene after them): count down to the next episode early.
    var creditsDismissed by remember(info.videoId) { mutableStateOf(false) }
    val credits = next != null && !creditsDismissed && segments?.postCredits == null &&
        segments?.outro?.let { positionNow >= it.startMs && player.duration > 0 && it.startMs > player.duration / 2 } == true

    val suggestHere = mvm.settings.postPlaySuggest && (request.video == null || next == null) && !info.videoId.startsWith("catchup:") && request.meta.id.startsWith("tt")
    val suggestions by produceState<List<MediaViewModel.Suggestion>?>(null, info.videoId, suggestHere) {
        if (suggestHere) {
            // Fetched ahead (in the last quarter), so they're ready when the credits start.
            while (player.duration <= 0 || player.currentPosition < player.duration * 0.75) delay(5_000)
            value = runCatching { mvm.suggestionsAfter(request.meta) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(positionNow, suggestions) {
        if (!suggestHere || endDismissed || !started || suggestions.isNullOrEmpty()) return@LaunchedEffect
        val d = player.duration
        if (d <= 0) return@LaunchedEffect
        val creditsAt = segments?.outro?.startMs?.takeIf { it > d / 2 } ?: (d * 0.97).toLong()
        val after = segments?.postCredits
        if (!endScreen && positionNow >= creditsAt && (after == null || positionNow < after.startMs - 2_000)) {
            panel = VodPanel.None
            save()
            endScreen = true
        }
        // A scene after the credits: back to full screen just before it.
        if (endScreen && after != null && positionNow >= after.startMs - 2_000) {
            endScreen = false
            endDismissed = true
            focus.tryRequest()
        }
    }

    // Next episode: count down after the episode ends (or when the credits start), if autoplay is on.
    var countdown by remember(info.videoId) { mutableIntStateOf(-1) }
    LaunchedEffect(credits) {
        if (!credits) return@LaunchedEffect
        nextCard = true
        if (!mvm.settings.autoNextEpisode) return@LaunchedEffect
        for (i in 10 downTo 1) {
            countdown = i
            delay(1_000)
        }
        countdown = -1
        next?.let { playNext(it) }
    }
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        if (next == null) {
            close()
            return@LaunchedEffect
        }
        nextCard = true
        if (!mvm.settings.autoNextEpisode) return@LaunchedEffect
        for (i in 10 downTo 1) {
            countdown = i
            delay(1_000)
        }
        countdown = -1
        playNext(next)
    }

    fun seekBy(ms: Long) {
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0)
        player.seekTo(if (d > 0 && d != C.TIME_UNSET) target.coerceAtMost(d - 1_000) else target)
        barTick++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) idleTick++
                false
            }
            .onKeyEvent { e ->
                if (endScreen || panel != VodPanel.None || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val fast = e.nativeKeyEvent.repeatCount > 4
                when (e.key) {
                    Key.DirectionLeft, Key.MediaRewind -> {
                        seekBy(if (fast) -60_000 else -10_000)
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        seekBy(if (fast) 60_000 else 10_000)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown, Key.Menu -> {
                        panel = VodPanel.Controls
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        playback.togglePause()
                        barTick++
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val screen = androidx.compose.ui.platform.LocalConfiguration.current
        val sw = screen.screenWidthDp.toFloat()
        val sh = screen.screenHeightDp.toFloat()
        androidx.compose.animation.AnimatedVisibility(
            visible = endScreen,
            enter = fadeIn(androidx.compose.animation.core.tween(600)),
            exit = fadeOut(androidx.compose.animation.core.tween(250)),
        ) {
            PostPlayPage(
                mvm = mvm,
                suggestions = suggestions.orEmpty(),
                onPlay = { m ->
                    save()
                    onPlaySuggestion(m)
                },
                onDetails = { m ->
                    save()
                    onSuggestionDetails(m)
                },
            )
        }
        FullscreenVideo(
            playback = playback,
            modifier = if (endScreen) {
                Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 56.dp).size(352.dp, 198.dp)
            } else when (playback.pictureMode) {
                // Fill: cover the screen, cropping the edges (the parts off-screen are simply not seen).
                1 -> if (playback.aspect > sw / sh) {
                    Modifier.requiredSize((sh * playback.aspect).dp, sh.dp)
                } else {
                    Modifier.requiredSize(sw.dp, (sw / playback.aspect).dp)
                }
                2 -> Modifier.fillMaxSize()
                else -> Modifier.aspectRatio(playback.aspect, matchHeightConstraintsFirst = playback.aspect < 16f / 9f + 0.01f)
            },
        )
        if (!endScreen) VodSubtitleLayer(playback, mvm.settings, raisedForPanel = panel == VodPanel.Controls)
        // The small window: OK (or Back) makes it full screen again.
        if (endScreen) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 56.dp).size(352.dp, 198.dp)) {
                FocusSurface(
                    onClick = {
                        endScreen = false
                        endDismissed = true
                        focus.tryRequest()
                    },
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(10.dp),
                    focusScale = 1f,
                    restColor = Color.Transparent,
                    focusColor = Color.Transparent,
                    ring = true,
                ) {}
                Text(
                    if (segments?.postCredits != null) "Credits  ·  a scene after them" else "Credits",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        if (started && playback.buffering && playback.error == null) {
            ZappLoader(36.dp)
        }

        // Quick progress strip when seeking with the controls hidden.
        AnimatedVisibility(
            visible = barVisible && panel == VodPanel.None && !endScreen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BottomScrim {
                Column {
                    PlayerInfoBlock(playback, request, info, synopsis = false, compact = true)
                    SeekBar(playback, active = false, markers = listOfNotNull(segments?.intro, segments?.recap, segments?.outro))
                }
            }
        }

        AnimatedVisibility(
            visible = panel != VodPanel.None,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            VodTopBar(playback)
        }

        AnimatedVisibility(
            visible = panel != VodPanel.None,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            BottomScrim {
                Column {
                PlayerInfoBlock(playback, request, info, synopsis = false)
                Spacer(Modifier.height(4.dp))
                VodControls(
                    playback = playback,
                    info = info,
                    next = next,
                    focusable = panel == VodPanel.Controls,
                    onAudio = { panel = VodPanel.Audio },
                    onSubtitles = { panel = VodPanel.Subtitles },
                    onDetails = { panel = VodPanel.Details },
                    onSources = {
                        save()
                        onChooseSource()
                    },
                    markers = listOfNotNull(segments?.intro, segments?.recap, segments?.outro),
                    onNext = { next?.let { playNext(it) } },
                )
                }
            }
        }

        // Paused for a while with nothing open: the picture dims and the title, its story and
        // what's playing appear above the seek bar. Any button brings the controls back.
        AnimatedVisibility(
            visible = paused && started && panel == VodPanel.None && !nextCard && !endScreen,
            enter = fadeIn(androidx.compose.animation.core.tween(600)),
            exit = fadeOut(androidx.compose.animation.core.tween(250)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Box(Modifier.align(Alignment.TopCenter)) { VodTopBar(playback) }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    BottomScrim {
                        Column {
                            PlayerInfoBlock(playback, request, info, synopsis = true)
                            SeekBar(playback, active = false, markers = listOfNotNull(segments?.intro, segments?.recap, segments?.outro))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = panel == VodPanel.Audio || panel == VodPanel.Subtitles || panel == VodPanel.SubStyle || panel == VodPanel.Details,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
        ) {
            when (panel) {
                VodPanel.Audio -> TrackPanel(
                    title = "Audio",
                    choices = playback.choices(C.TRACK_TYPE_AUDIO),
                    offOption = null,
                    onPick = {
                        playback.select(it)
                        panel = VodPanel.Controls
                    },
                )
                VodPanel.Subtitles -> TrackPanel(
                    title = "Subtitles",
                    choices = playback.choices(C.TRACK_TYPE_TEXT),
                    offOption = !playback.subtitlesShowing,
                    onOff = {
                        playback.subtitlesOff()
                        panel = VodPanel.Controls
                    },
                    onPick = {
                        playback.select(it)
                        panel = VodPanel.Controls
                    },
                    onStyle = { panel = VodPanel.SubStyle },
                )
                VodPanel.SubStyle -> SubtitleStylePanel(playback, mvm.settings)
                else -> DetailsPanel(playback)
            }
        }

        AnimatedVisibility(
            visible = !started && playback.error == null,
            enter = fadeIn(),
            exit = fadeOut(androidx.compose.animation.core.tween(450)),
        ) {
            StartingScreen(request.meta.background ?: info.background ?: info.poster, info.title, info.episodeLabel)
        }

        // "Skip intro" / "Skip recap": already selected, so one press of OK skips.
        val showSkip = skipTarget != null && panel == VodPanel.None && countdown <= 0 && !endScreen &&
            !((skipTarget == segments?.intro && mvm.settings.skipIntro == 2) || (skipTarget == segments?.recap && mvm.settings.skipRecap == 2))
        AnimatedVisibility(
            visible = showSkip,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 48.dp),
            enter = fadeIn() + slideInHorizontally { it / 4 },
            exit = fadeOut(),
        ) {
            val skipFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(80)
                skipFocus.tryRequest()
            }
            DisposableEffect(Unit) { onDispose { if (panel == VodPanel.None) focus.tryRequest() } }
            ZButton(
                if (skipTarget == segments?.recap && !inIntro) "Skip recap" else "Skip intro",
                {
                    skipTarget?.let { player.seekTo(it.endMs) }
                    focus.tryRequest()
                },
                style = ButtonStyle.Primary,
                focusRequester = skipFocus,
            )
        }

        if ((countdown > 0 || nextCard) && next != null && panel == VodPanel.None) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(40.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ZappColors.Panel.copy(alpha = 0.94f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(176.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZappColors.Glass),
                ) {
                    Artwork(next.thumbnail ?: request.meta.background, shortLabel(next))
                    if (countdown > 0) {
                        ProgressLine(
                            countdown / 10f,
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(),
                            track = Color(0x55FFFFFF),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.width(230.dp)) {
                    Text(
                        if (countdown > 0) "NEXT EPISODE IN $countdown" else "UP NEXT",
                        color = ZappColors.Brand2,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.8.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(episodeLabel(next), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    val playNow = remember { FocusRequester() }
                    LaunchedEffect(Unit) { playNow.tryRequest() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZButton("Play now", { playNext(next) }, style = ButtonStyle.Primary, focusRequester = playNow)
                        if (ended) {
                            ZButton("Close", { close() })
                        } else {
                            ZButton("Keep watching", {
                                creditsDismissed = true
                                nextCard = false
                                countdown = -1
                                focus.tryRequest()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VodTopBar(playback: Playback) {
    val now = rememberNow(15_000)
    val timeFormat = rememberTimeFormat()
    val remaining by produceState(0L) {
        while (true) {
            val d = playback.player.duration
            value = if (d > 0 && d != C.TIME_UNSET) (d - playback.player.currentPosition).coerceAtLeast(0) else 0
            delay(1_000)
        }
    }
    // Only the time, top right: the title lives bottom left, above the seek bar.
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            .padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 50.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(timeFormat.format(now), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (remaining > 0) {
                Text("Ends at ${timeFormat.format(now + remaining)}", color = ZappColors.Dim, fontSize = 11.5.sp)
            }
        }
    }
}

/**
 * Bottom left, above the seek bar: the title's logo (or its name in poster lettering), the episode,
 * and a quiet line saying what's actually playing (4K · HDR10 · Dolby Atmos 5.1 · 24 fps).
 * [synopsis]: the paused view also shows the story.
 */
@Composable
private fun PlayerInfoBlock(playback: Playback, request: PlayRequest, info: MediaInfo, synopsis: Boolean, compact: Boolean = false) {
    val tech by produceState(emptyList<String>(), info.videoId) {
        while (true) {
            value = playback.techParts()
            delay(2_000)
        }
    }
    val logo = request.meta.logo
    Column(Modifier.widthIn(max = 620.dp).padding(bottom = 6.dp)) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = info.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier.heightIn(max = if (compact) 46.dp else 70.dp).widthIn(max = if (compact) 220.dp else 320.dp),
            )
        } else {
            MovieTitle(info.title, if (compact) 22.sp else 30.sp, maxLines = 1)
        }
        val ep = request.video
        if (ep != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(ep.season?.let { "S$it" }, ep.episode?.let { "E$it" }, ep.title.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (synopsis) {
            (ep?.overview ?: request.meta.description)?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color.White.copy(alpha = 0.78f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tech.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(tech.joinToString("  ·  "), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

internal enum class PlayerIcon { Play, Pause, Back10, Forward10, Audio, Subtitles, Sources, Picture, Boost, Info, Next, Channels, Favourite, FavouriteOn, Restart, LastChannel }

@Composable
internal fun PlayerGlyph(icon: PlayerIcon, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            when (icon) {
                PlayerIcon.Play -> drawPath(
                    Path().apply {
                        moveTo(w * 0.3f, w * 0.18f)
                        lineTo(w * 0.84f, w * 0.5f)
                        lineTo(w * 0.3f, w * 0.82f)
                        close()
                    },
                    color,
                )
                PlayerIcon.Pause -> {
                    drawRoundRect(color, Offset(w * 0.24f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.17f, w * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
                    drawRoundRect(color, Offset(w * 0.59f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.17f, w * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
                }
                PlayerIcon.Audio, PlayerIcon.Boost -> {
                    // A speaker; sound waves for audio, a plus for boost.
                    val sw = w * 0.075f
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.14f, w * 0.38f)
                            lineTo(w * 0.3f, w * 0.38f)
                            lineTo(w * 0.5f, w * 0.2f)
                            lineTo(w * 0.5f, w * 0.8f)
                            lineTo(w * 0.3f, w * 0.62f)
                            lineTo(w * 0.14f, w * 0.62f)
                            close()
                        },
                        color,
                    )
                    if (icon == PlayerIcon.Audio) {
                        for (r in listOf(w * 0.16f, w * 0.3f)) {
                            drawArc(color, -45f, 90f, false, Offset(w * 0.5f - r, w * 0.5f - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
                        }
                    } else {
                        drawLine(color, Offset(w * 0.62f, w * 0.5f), Offset(w * 0.9f, w * 0.5f), sw, StrokeCap.Round)
                        drawLine(color, Offset(w * 0.76f, w * 0.36f), Offset(w * 0.76f, w * 0.64f), sw, StrokeCap.Round)
                    }
                }
                PlayerIcon.Subtitles -> {
                    val sw = w * 0.07f
                    drawRoundRect(color, Offset(w * 0.1f, w * 0.2f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.6f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = Stroke(sw))
                    drawLine(color, Offset(w * 0.24f, w * 0.5f), Offset(w * 0.44f, w * 0.5f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.52f, w * 0.5f), Offset(w * 0.76f, w * 0.5f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.24f, w * 0.64f), Offset(w * 0.6f, w * 0.64f), sw, StrokeCap.Round)
                }
                PlayerIcon.Sources -> {
                    val sw = w * 0.075f
                    for (i in 0..2) {
                        val y = w * (0.28f + i * 0.22f)
                        drawCircle(color, w * 0.055f, Offset(w * 0.18f, y))
                        drawLine(color, Offset(w * 0.32f, y), Offset(w * 0.86f, y), sw, StrokeCap.Round)
                    }
                }
                PlayerIcon.Picture -> {
                    // Four corners of a frame.
                    val sw = w * 0.075f
                    val a = w * 0.14f
                    val b = w * 0.86f
                    val l = w * 0.22f
                    listOf(Offset(a, w * 0.22f) to Pair(1f, 1f), Offset(b, w * 0.22f) to Pair(-1f, 1f), Offset(a, w * 0.78f) to Pair(1f, -1f), Offset(b, w * 0.78f) to Pair(-1f, -1f)).forEach { (p, d) ->
                        drawLine(color, p, Offset(p.x + l * d.first, p.y), sw, StrokeCap.Round)
                        drawLine(color, p, Offset(p.x, p.y + l * d.second * 0.8f), sw, StrokeCap.Round)
                    }
                }
                PlayerIcon.Info -> {
                    val sw = w * 0.07f
                    drawCircle(color, w * 0.38f, Offset(w / 2, w / 2), style = Stroke(sw))
                    drawCircle(color, w * 0.05f, Offset(w / 2, w * 0.32f))
                    drawLine(color, Offset(w / 2, w * 0.46f), Offset(w / 2, w * 0.7f), sw * 1.1f, StrokeCap.Round)
                }
                PlayerIcon.Next -> {
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.2f, w * 0.2f)
                            lineTo(w * 0.66f, w * 0.5f)
                            lineTo(w * 0.2f, w * 0.8f)
                            close()
                        },
                        color,
                    )
                    drawRoundRect(color, Offset(w * 0.7f, w * 0.2f), androidx.compose.ui.geometry.Size(w * 0.11f, w * 0.6f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
                }
                PlayerIcon.Channels -> {
                    val sw = w * 0.07f
                    drawRoundRect(color, Offset(w * 0.1f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.52f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(sw))
                    drawLine(color, Offset(w * 0.34f, w * 0.84f), Offset(w * 0.66f, w * 0.84f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.5f, w * 0.7f), Offset(w * 0.5f, w * 0.84f), sw, StrokeCap.Round)
                }
                PlayerIcon.Favourite, PlayerIcon.FavouriteOn -> {
                    val star = Path()
                    for (i in 0 until 10) {
                        val rr = if (i % 2 == 0) w * 0.4f else w * 0.17f
                        val ang = Math.toRadians(-90.0 + i * 36.0)
                        val x = w / 2 + (rr * Math.cos(ang)).toFloat()
                        val y = w * 0.53f + (rr * Math.sin(ang)).toFloat()
                        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
                    }
                    star.close()
                    if (icon == PlayerIcon.FavouriteOn) drawPath(star, ZappColors.Star) else drawPath(star, color, style = Stroke(w * 0.065f, join = StrokeJoin.Round))
                }
                PlayerIcon.Restart -> {
                    val r = w * 0.32f
                    drawArc(color, -60f, 300f, false, Offset(w / 2 - r, w / 2 - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(w * 0.075f, cap = StrokeCap.Round))
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.62f, w * 0.1f)
                            lineTo(w * 0.8f, w * 0.24f)
                            lineTo(w * 0.6f, w * 0.34f)
                            close()
                        },
                        color,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(w * 0.44f, w * 0.38f)
                            lineTo(w * 0.62f, w * 0.5f)
                            lineTo(w * 0.44f, w * 0.62f)
                            close()
                        },
                        color,
                    )
                }
                PlayerIcon.LastChannel -> {
                    val sw = w * 0.075f
                    drawLine(color, Offset(w * 0.18f, w * 0.36f), Offset(w * 0.82f, w * 0.36f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.66f, w * 0.22f), Offset(w * 0.82f, w * 0.36f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.66f, w * 0.5f), Offset(w * 0.82f, w * 0.36f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.18f, w * 0.66f), Offset(w * 0.82f, w * 0.66f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.34f, w * 0.52f), Offset(w * 0.18f, w * 0.66f), sw, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.34f, w * 0.8f), Offset(w * 0.18f, w * 0.66f), sw, StrokeCap.Round)
                }
                PlayerIcon.Back10, PlayerIcon.Forward10 -> {
                    val back = icon == PlayerIcon.Back10
                    val r = w * 0.38f
                    drawArc(
                        color,
                        startAngle = if (back) -60f else -120f,
                        sweepAngle = if (back) 300f else -300f,
                        useCenter = false,
                        topLeft = Offset(w / 2 - r, w / 2 - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = Stroke(w * 0.07f, cap = StrokeCap.Round),
                    )
                    // Arrow head at the top of the circle.
                    val tipX = if (back) w * 0.5f - r * 0.5f else w * 0.5f + r * 0.5f
                    val dir = if (back) -1f else 1f
                    drawPath(
                        Path().apply {
                            moveTo(tipX + dir * w * 0.12f, w * 0.12f - w * 0.02f)
                            lineTo(tipX - dir * w * 0.02f, w * 0.12f + w * 0.06f)
                            lineTo(tipX + dir * w * 0.08f, w * 0.12f + w * 0.16f)
                            close()
                        },
                        color,
                    )
                }
            }
        }
        if (icon == PlayerIcon.Back10 || icon == PlayerIcon.Forward10) {
            Text("10", color = color, fontSize = (size.value * 0.27f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A player icon on its own (no circle): soft shadow at rest; white, larger and underlined on focus. */
@Composable
internal fun RoundButton(icon: PlayerIcon, big: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    val s = if (big) 56.dp else 44.dp
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(s),
        focusRequester = requester,
        shape = CircleShape,
        focusScale = 1.16f,
        restColor = Color.Transparent,
        focusColor = Color.Transparent,
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val g = if (big) 32.dp else 26.dp
            PlayerGlyph(icon, Color.Black.copy(alpha = 0.55f), g, Modifier.graphicsLayer { translationY = 1.5.dp.toPx() }.blur(3.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded))
            PlayerGlyph(icon, if (focused) Color.White else Color.White.copy(alpha = 0.8f), g)
            FocusUnderline(focused, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** A short theme-coloured line under whatever has focus in the player. */
@Composable
internal fun FocusUnderline(focused: Boolean, modifier: Modifier = Modifier) {
    val w by androidx.compose.animation.core.animateDpAsState(if (focused) 18.dp else 0.dp, androidx.compose.animation.core.tween(160), label = "underline")
    Box(
        modifier
            .width(w)
            .height(2.dp)
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
    )
}

/**
 * A player option as a small icon. While it has focus, its name and current value appear above it,
 * so the row itself stays clean.
 */
@Composable
internal fun IconOption(icon: PlayerIcon, label: String, value: String?, onClick: () -> Unit, requester: FocusRequester? = null, accent: Boolean = false) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        focusRequester = requester,
        shape = CircleShape,
        focusScale = 1.16f,
        restColor = Color.Transparent,
        focusColor = Color.Transparent,
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerGlyph(icon, Color.Black.copy(alpha = 0.55f), 22.dp, Modifier.graphicsLayer { translationY = 1.5.dp.toPx() }.blur(3.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded))
            PlayerGlyph(
                icon,
                when {
                    focused -> Color.White
                    accent -> ZappColors.Brand2
                    else -> Color.White.copy(alpha = 0.75f)
                },
                22.dp,
            )
            FocusUnderline(focused, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** A player option as quiet text; its current value appears beneath it while it has focus. */
@Composable
private fun ChipButton(label: String, value: String?, onClick: () -> Unit, requester: FocusRequester? = null, accent: Boolean = false) {
    FocusSurface(
        onClick = onClick,
        focusRequester = requester,
        shape = RoundedCornerShape(10.dp),
        focusScale = 1.04f,
        restColor = Color.Transparent,
        focusColor = Color.Transparent,
    ) { focused ->
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).widthIn(max = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                fontSize = 13.5.sp,
                fontWeight = if (focused || accent) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    focused -> Color.White
                    accent -> ZappColors.Brand2
                    else -> Color.White.copy(alpha = 0.72f)
                },
                maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(0f, 1.5f), 6f)),
            )
            Spacer(Modifier.height(4.dp))
            FocusUnderline(focused)
            Box(Modifier.height(16.dp)) {
                if (focused && value != null) {
                    Text(value, fontSize = 10.5.sp, color = ZappColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun VodControls(
    playback: Playback,
    info: MediaInfo,
    next: Video?,
    focusable: Boolean,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onDetails: () -> Unit,
    onSources: () -> Unit,
    onNext: () -> Unit,
    markers: List<com.zapp.tv.data.Segment> = emptyList(),
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusable) { if (focusable) first.tryRequest() }
    val playing by produceState(playback.player.playWhenReady) {
        while (true) {
            value = playback.player.playWhenReady
            delay(300)
        }
    }
    val audio = playback.choices(C.TRACK_TYPE_AUDIO)
    val subtitles = playback.choices(C.TRACK_TYPE_TEXT)
    val audioNow = audio.firstOrNull { it.selected }?.let { listOf(it.label, it.detail).filter { s -> s.isNotBlank() }.joinToString(", ") }
    val subsNow = when {
        subtitles.isEmpty() -> "None available"
        else -> subtitles.firstOrNull { it.selected }?.label ?: "Off"
    }

    fun seekBy(ms: Long) {
        val p = playback.player
        val d = p.duration
        val target = (p.currentPosition + ms).coerceAtLeast(0)
        p.seekTo(if (d > 0 && d != C.TIME_UNSET) target.coerceAtMost(d - 1_000) else target)
    }

    Column {
        playback.error?.let {
            ErrorBanner("This source isn't playing. Try again, or choose another source.")
        }
        SeekBar(playback, active = focusable, markers = markers)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            if (playback.error != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipButton("Choose another source", null, onSources, requester = first, accent = true)
                    ChipButton("Try again", null, { playback.retry() })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(PlayerIcon.Back10, false, null) { seekBy(-10_000) }
                    RoundButton(if (playing) PlayerIcon.Pause else PlayerIcon.Play, true, first) { playback.togglePause() }
                    RoundButton(PlayerIcon.Forward10, false, null) { seekBy(10_000) }
                }
            }
            Spacer(Modifier.weight(1f))
            // Options as small icons; the one you're on is named (with its setting) just above.
            var caption by remember { mutableStateOf<String?>(null) }
            Column(horizontalAlignment = Alignment.End) {
                Box(Modifier.height(18.dp)) {
                    caption?.let { Text(it, fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(0f, 1.5f), 6f))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable
                    fun opt(icon: PlayerIcon, label: String, value: String?, accent: Boolean = false, onClick: () -> Unit) {
                        Box(Modifier.onFocusChanged { if (it.hasFocus) caption = listOfNotNull(label, value).joinToString("  ·  ") else if (caption?.startsWith(label) == true) caption = null }) {
                            IconOption(icon, label, value, onClick, accent = accent)
                        }
                    }
                    opt(PlayerIcon.Audio, "Audio", audioNow ?: "Default", onClick = onAudio)
                    opt(PlayerIcon.Subtitles, "Subtitles", subsNow, onClick = onSubtitles)
                    opt(PlayerIcon.Sources, "Sources", null, onClick = onSources)
                    opt(PlayerIcon.Picture, "Picture", listOf("Fit", "Fill", "Stretch")[playback.pictureMode]) { playback.pictureMode = (playback.pictureMode + 1) % 3 }
                    opt(PlayerIcon.Boost, "Volume boost", if (playback.boost) "On" else "Off") { playback.toggleBoost(!playback.boost) }
                    opt(PlayerIcon.Info, "Stream info", null, onClick = onDetails)
                    if (next != null) opt(PlayerIcon.Next, "Next episode", next.episode?.let { e -> "S${next.season ?: 1} E$e" }, accent = true, onClick = onNext)
                }
            }
        }
    }
}
