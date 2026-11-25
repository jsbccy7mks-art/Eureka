package com.zapp.tv.ui

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

private enum class VodPanel { None, Controls, Audio, Subtitles, Details }

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
    onPlayNext: (Video) -> Unit,
    onClose: () -> Unit,
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
    val currentInfo by rememberUpdatedState(info)
    val next = remember(request.videoId) { request.video?.let { mvm.nextEpisode(request.videos, it.id) } }
    // Until the first picture appears, the Zapp starting screen stays up (it carries on from the
    // sources screen, so starting a film looks like one smooth step).
    var started by remember(info.videoId) { mutableStateOf(false) }
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

    DisposableEffect(Unit) {
        playback.fullscreen = true
        view.keepScreenOn = true
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                started = true
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
                if (isPlaying) {
                    mvm.scrobble("start", currentInfo, percentNow(), player.duration.coerceAtLeast(0))
                } else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    mvm.scrobble("pause", currentInfo, percentNow(), player.duration.coerceAtLeast(0))
                }
            }
        }
        player.addListener(listener)
        onDispose {
            save()
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

    BackHandler {
        panel = when (panel) {
            VodPanel.Audio, VodPanel.Subtitles, VodPanel.Details -> VodPanel.Controls
            VodPanel.Controls -> VodPanel.None
            VodPanel.None -> {
                close()
                VodPanel.None
            }
        }
    }

    LaunchedEffect(panel) { if (panel == VodPanel.None) focus.tryRequest() }
    LaunchedEffect(playback.error) { if (playback.error != null) panel = VodPanel.Controls }
    LaunchedEffect(barTick) {
        barVisible = true
        delay(4_000)
        barVisible = false
    }
    LaunchedEffect(idleTick, panel) {
        if (panel == VodPanel.Controls && playback.error == null && player.isPlaying) {
            delay(8_000)
            panel = VodPanel.None
        }
    }

    // Next episode: count down after the episode ends, if autoplay is on.
    var countdown by remember(info.videoId) { mutableIntStateOf(-1) }
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        if (next == null) {
            close()
            return@LaunchedEffect
        }
        if (!mvm.settings.autoNextEpisode) {
            panel = VodPanel.Controls
            return@LaunchedEffect
        }
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
                if (panel != VodPanel.None || e.type != KeyEventType.KeyDown) return@onKeyEvent false
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
        FullscreenVideo(
            playback = playback,
            modifier = Modifier.aspectRatio(playback.aspect, matchHeightConstraintsFirst = playback.aspect < 16f / 9f + 0.01f),
        )
        SubtitleLayer(playback.cues, vm.settings, raisedForPanel = panel == VodPanel.Controls)

        if (started && playback.buffering && playback.error == null) {
            ZappLoader(36.dp)
        }

        // Quick progress strip when seeking with the controls hidden.
        AnimatedVisibility(
            visible = barVisible && panel == VodPanel.None,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BottomScrim {
                Column {
                    Text(info.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    info.episodeLabel?.let { Text(it, color = ZappColors.Dim, fontSize = 13.sp, maxLines = 1) }
                    SeekBar(playback, active = false)
                }
            }
        }

        AnimatedVisibility(
            visible = panel != VodPanel.None,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            VodTopBar(playback, info)
        }

        AnimatedVisibility(
            visible = panel != VodPanel.None,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            BottomScrim {
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
                    onNext = { next?.let { playNext(it) } },
                )
            }
        }

        AnimatedVisibility(
            visible = panel == VodPanel.Audio || panel == VodPanel.Subtitles || panel == VodPanel.Details,
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
                )
                else -> DetailsPanel(playback)
            }
        }

        AnimatedVisibility(
            visible = !started && playback.error == null,
            enter = fadeIn(),
            exit = fadeOut(androidx.compose.animation.core.tween(450)),
        ) {
            StartingScreen(info.background ?: info.poster, info.title, info.episodeLabel)
        }

        if (countdown > 0 && next != null) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZappColors.Panel)
                    .padding(18.dp),
            ) {
                Text("Next episode in $countdown", color = ZappColors.Dim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(episodeLabel(next), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(280.dp))
                Spacer(Modifier.height(12.dp))
                val playNow = remember { FocusRequester() }
                LaunchedEffect(Unit) { playNow.tryRequest() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZButton("Play now", { playNext(next) }, style = ButtonStyle.Primary, focusRequester = playNow)
                    ZButton("Stop", { close() })
                }
            }
        }
    }
}

@Composable
private fun VodTopBar(playback: Playback, info: MediaInfo) {
    val now = rememberNow(15_000)
    val timeFormat = rememberTimeFormat()
    val remaining by produceState(0L) {
        while (true) {
            val d = playback.player.duration
            value = if (d > 0 && d != C.TIME_UNSET) (d - playback.player.currentPosition).coerceAtLeast(0) else 0
            delay(1_000)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ZappColors.Bg.copy(alpha = 0.90f), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 30.dp, bottom = 60.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(info.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.episodeLabel?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = ZappColors.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(timeFormat.format(now), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (remaining > 0) {
                Text("Ends at ${timeFormat.format(now + remaining)}", color = ZappColors.Dim, fontSize = 12.sp)
            }
        }
    }
}

private enum class PlayerIcon { Play, Pause, Back10, Forward10 }

@Composable
private fun PlayerGlyph(icon: PlayerIcon, color: Color, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
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

@Composable
private fun RoundButton(icon: PlayerIcon, big: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    val s = if (big) 60.dp else 48.dp
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(s),
        focusRequester = requester,
        shape = CircleShape,
        focusScale = 1.08f,
        restColor = if (big) Color(0x33FFFFFF) else Color(0x1FFFFFFF),
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerGlyph(icon, if (focused) ZappColors.OnFocus else Color.White, if (big) 28.dp else 26.dp)
        }
    }
}

/** A labelled option in the player, showing its current value ("Audio" / "English, 5.1"). */
@Composable
private fun ChipButton(label: String, value: String?, onClick: () -> Unit, requester: FocusRequester? = null, accent: Boolean = false) {
    FocusSurface(
        onClick = onClick,
        focusRequester = requester,
        shape = RoundedCornerShape(14.dp),
        focusScale = 1.04f,
        restColor = if (accent) ZappColors.Brand1.copy(alpha = 0.45f) else Color(0x1FFFFFFF),
    ) { focused ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (value != null) 8.dp else 14.dp).widthIn(max = 220.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else Color.White, maxLines = 1)
            value?.let {
                Text(it, fontSize = 11.sp, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        SeekBar(playback, active = focusable)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            if (playback.error != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChipButton("Choose another source", null, onSources, requester = first, accent = true)
                    ChipButton("Try again", null, { playback.retry() })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(PlayerIcon.Back10, false, null) { seekBy(-10_000) }
                    RoundButton(if (playing) PlayerIcon.Pause else PlayerIcon.Play, true, first) { playback.togglePause() }
                    RoundButton(PlayerIcon.Forward10, false, null) { seekBy(10_000) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChipButton("Audio", audioNow ?: "Default", onAudio)
                ChipButton("Subtitles", subsNow, onSubtitles)
                ChipButton("Sources", null, onSources)
                ChipButton("Info", null, onDetails)
                if (next != null) ChipButton("Next episode", next.episode?.let { e -> "S${next.season ?: 1} E$e" }, onNext, accent = true)
            }
        }
    }
}
