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

    fun save() {
        val d = player.duration
        if (d > 0 && d != C.TIME_UNSET) mvm.saveProgress(currentInfo, player.currentPosition, d)
    }

    // Save before leaving: the player stops right after these, so the position must be read first.
    fun close() {
        save()
        onClose()
    }

    fun playNext(v: Video) {
        save()
        onPlayNext(v)
    }

    DisposableEffect(Unit) {
        playback.fullscreen = true
        view.keepScreenOn = true
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    save()
                    ended = true
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

        if (playback.buffering && playback.error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
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

    Column {
        Text(info.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        info.episodeLabel?.let { Text(it, color = ZappColors.Dim, fontSize = 13.sp, maxLines = 1) }
        playback.error?.let {
            Spacer(Modifier.height(10.dp))
            ErrorBanner("This source isn't playing. Try again, or choose another source.")
        }
        SeekBar(playback, active = focusable)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playback.error != null) {
                ZButton("Choose another source", onSources, style = ButtonStyle.Primary, focusRequester = first)
                ZButton("Try again", { playback.retry() })
            } else {
                ZButton(if (playing) "Pause" else "Play", { playback.togglePause() }, focusRequester = first)
                ZButton("Sources", onSources)
            }
            ZButton(if (audio.size > 1) "Audio (${audio.size})" else "Audio", onAudio)
            ZButton(
                when {
                    subtitles.isEmpty() -> "Subtitles"
                    playback.subtitlesShowing -> "Subtitles on"
                    else -> "Subtitles off"
                },
                onSubtitles,
            )
            ZButton("Stream info", onDetails)
            if (next != null) ZButton("Next episode", onNext)
        }
    }
}
