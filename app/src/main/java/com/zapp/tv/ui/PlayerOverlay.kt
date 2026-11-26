@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.Programme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ZapUp = setOf(Key.DirectionUp, Key.ChannelDown)
private val ZapDown = setOf(Key.DirectionDown, Key.ChannelUp)
private val SelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private enum class PlayerPanel { None, Controls, Channels, Audio, Subtitles, Details, MiniGuide }

/**
 * Fullscreen player.
 * Up/Down: change channel. OK: controls (audio, subtitles, stream info...). Left: channel list.
 * Right: programme info. Hold OK or Menu: favourite. Back closes the open panel, then the player.
 */
@Composable
fun PlayerOverlay(
    vm: AppViewModel,
    playback: Playback,
    rows: LazyPagingItems<ChannelRow>,
    onClose: () -> Unit,
    onRestart: ((Channel, Programme) -> Unit)? = null,
) {
    val channel = playback.channel ?: return
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()

    var panel by remember { mutableStateOf(PlayerPanel.None) }
    var infoVisible by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    var idleTick by remember { mutableIntStateOf(0) }
    var zapJob by remember { mutableStateOf<Job?>(null) }
    var favTick by remember { mutableIntStateOf(0) }
    // The channel you were on before this one (for "Last channel").
    var previous by remember { mutableStateOf<Channel?>(null) }
    var lastSeen by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(channel.streamUrl) {
        if (lastSeen != null && lastSeen?.streamUrl != channel.streamUrl) previous = lastSeen
        lastSeen = channel
    }
    val press = remember { BooleanArray(2) } // [0] OK down, [1] long press fired

    DisposableEffect(Unit) {
        playback.fullscreen = true
        view.keepScreenOn = true
        onDispose {
            playback.fullscreen = false
            view.keepScreenOn = false
        }
    }

    BackHandler {
        panel = when (panel) {
            PlayerPanel.Audio, PlayerPanel.Subtitles, PlayerPanel.Details -> PlayerPanel.Controls
            PlayerPanel.Controls, PlayerPanel.Channels, PlayerPanel.MiniGuide -> PlayerPanel.None
            PlayerPanel.None -> {
                onClose()
                PlayerPanel.None
            }
        }
    }

    LaunchedEffect(panel) { if (panel == PlayerPanel.None) focus.tryRequest() }

    // Open the controls when a stream fails, so "Try again" is one press away.
    LaunchedEffect(playback.error) { if (playback.error != null) panel = PlayerPanel.Controls }

    // Controls tidy themselves away after a while without key presses.
    LaunchedEffect(idleTick, panel) {
        if (panel == PlayerPanel.Controls && playback.error == null) {
            delay(9_000)
            // Paused: the controls stay until you play again.
            while (!playback.player.playWhenReady) delay(500)
            panel = PlayerPanel.None
        }
    }

    LaunchedEffect(channel.streamUrl) {
        delay(10_000)
        vm.recordWatched(channel)
    }

    LaunchedEffect(infoTick) {
        infoVisible = true
        delay(5_000)
        infoVisible = false
    }

    val programmes by produceState(emptyList<Programme>(), channel.streamUrl, now / 60_000) {
        value = vm.nowNext(channel)
    }
    val favorite by produceState(false, channel.streamUrl, favTick) {
        value = vm.isFavorite(channel)
    }

    fun toggleFavorite(c: Channel) {
        vm.toggleFavorite(c) { favTick++ }
    }

    fun zap(forward: Boolean) {
        if (zapJob?.isActive == true) return
        zapJob = scope.launch {
            val current = playback.channel ?: return@launch
            val next = vm.neighbor(current, forward)
            if (next != null && next.streamUrl != current.streamUrl) {
                vm.lastChannelUrl = next.streamUrl
                playback.play(next)
                infoTick++
            }
            delay(300) // a held key doesn't hammer the provider
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                // Any key while a panel is open keeps the controls from hiding; channel keys always zap.
                if (it.type == KeyEventType.KeyDown) {
                    idleTick++
                    if (panel != PlayerPanel.None && (it.key == Key.ChannelUp || it.key == Key.ChannelDown)) {
                        zap(it.key == Key.ChannelUp)
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .onKeyEvent { e ->
                if (panel != PlayerPanel.None) return@onKeyEvent false
                val key = e.key
                when (e.type) {
                    KeyEventType.KeyDown -> when {
                        key in ZapUp -> {
                            zap(false)
                            true
                        }
                        key in ZapDown -> {
                            zap(true)
                            true
                        }
                        key in SelectKeys -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                press[0] = true
                                press[1] = false
                            } else if (press[0] && !press[1]) {
                                press[1] = true
                                toggleFavorite(channel)
                            }
                            true
                        }
                        key == Key.DirectionLeft -> {
                            panel = PlayerPanel.Channels
                            true
                        }
                        // Right: now and next on the channels around this one, without leaving it.
                        key == Key.DirectionRight -> {
                            panel = PlayerPanel.MiniGuide
                            true
                        }
                        key == Key.Menu -> {
                            panel = PlayerPanel.Controls
                            true
                        }
                        key == Key.MediaPlayPause || key == Key.MediaPlay || key == Key.MediaPause -> {
                            playback.togglePause()
                            infoTick++
                            true
                        }
                        key == Key.MediaRewind -> {
                            playback.seekLive(-10_000)
                            infoTick++
                            true
                        }
                        key == Key.MediaFastForward -> {
                            playback.seekLive(10_000)
                            infoTick++
                            true
                        }
                        else -> false
                    }
                    KeyEventType.KeyUp -> when {
                        key in SelectKeys -> {
                            val shortPress = press[0] && !press[1]
                            press[0] = false
                            press[1] = false
                            if (shortPress) panel = PlayerPanel.Controls
                            true
                        }
                        else -> false
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

        SubtitleLayer(playback.cues, vm.settings, raisedForPanel = panel == PlayerPanel.Controls || infoVisible)

        if (playback.buffering && playback.error == null) {
            ZappLoader(30.dp)
        }

        AnimatedVisibility(
            visible = infoVisible && panel == PlayerPanel.None,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            BottomScrim { InfoBar(channel, favorite, programmes, now, timeFormat, hints = true, playback = playback) }
        }

        AnimatedVisibility(
            visible = panel == PlayerPanel.Controls || panel == PlayerPanel.Audio || panel == PlayerPanel.Subtitles || panel == PlayerPanel.Details,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            BottomScrim {
                ControlsPanel(
                    playback = playback,
                    channel = channel,
                    favorite = favorite,
                    programmes = programmes,
                    now = now,
                    timeFormat = timeFormat,
                    focusable = panel == PlayerPanel.Controls,
                    onChannels = { panel = PlayerPanel.Channels },
                    onAudio = { panel = PlayerPanel.Audio },
                    onSubtitles = { panel = PlayerPanel.Subtitles },
                    onDetails = { panel = PlayerPanel.Details },
                    onFavorite = { toggleFavorite(channel) },
                    onNextChannel = { zap(true) },
                    onLastChannel = previous?.let { p ->
                        {
                            vm.lastChannelUrl = p.streamUrl
                            playback.play(p)
                            panel = PlayerPanel.None
                            infoTick++
                        }
                    },
                    onRestart = onRestart?.let { restart ->
                        programmes.firstOrNull { it.start <= now && it.stop > now }
                            ?.takeIf { vm.canCatchUp(channel, it.start, it.stop) }
                            ?.let { p -> { restart(channel, p) } }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = panel == PlayerPanel.MiniGuide,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
        ) {
            MiniGuide(vm, channel, now, timeFormat) { c ->
                if (c.streamUrl != channel.streamUrl) {
                    vm.lastChannelUrl = c.streamUrl
                    playback.play(c)
                    infoTick++
                }
                panel = PlayerPanel.None
            }
        }

        AnimatedVisibility(
            visible = panel == PlayerPanel.Channels,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = fadeIn() + slideInHorizontally { -it / 3 },
            exit = fadeOut() + slideOutHorizontally { -it / 3 },
        ) {
            ChannelListPanel(
                vm = vm,
                rows = rows,
                current = channel,
                now = now,
                onPick = { row ->
                    vm.lastChannelUrl = row.channel.streamUrl
                    playback.play(row.channel)
                    panel = PlayerPanel.None
                    infoTick++
                },
                onFavorite = { row -> toggleFavorite(row.channel) },
                onClose = { panel = PlayerPanel.None },
            )
        }

        AnimatedVisibility(
            visible = panel == PlayerPanel.Audio || panel == PlayerPanel.Subtitles || panel == PlayerPanel.Details,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
        ) {
            when (panel) {
                PlayerPanel.Audio -> TrackPanel(
                    title = "Audio",
                    choices = playback.choices(C.TRACK_TYPE_AUDIO),
                    offOption = null,
                    onPick = {
                        playback.select(it)
                        panel = PlayerPanel.Controls
                    },
                )
                PlayerPanel.Subtitles -> TrackPanel(
                    title = "Subtitles",
                    choices = playback.choices(C.TRACK_TYPE_TEXT),
                    offOption = !playback.subtitlesShowing,
                    onOff = {
                        playback.subtitlesOff()
                        panel = PlayerPanel.Controls
                    },
                    onPick = {
                        playback.select(it)
                        panel = PlayerPanel.Controls
                    },
                )
                else -> DetailsPanel(playback)
            }
        }
    }
}

@Composable
internal fun BottomScrim(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            // A soft fade from the bottom, no panel: the picture stays visible behind the controls.
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.82f))))
            .padding(start = 56.dp, end = 56.dp, top = 90.dp, bottom = 26.dp),
    ) {
        content()
    }
}

@Composable
private fun ControlsPanel(
    playback: Playback,
    channel: Channel,
    favorite: Boolean,
    programmes: List<Programme>,
    now: Long,
    timeFormat: java.text.DateFormat,
    focusable: Boolean,
    onChannels: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onDetails: () -> Unit,
    onFavorite: () -> Unit,
    onNextChannel: () -> Unit,
    onLastChannel: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusable) { if (focusable) first.tryRequest() }
    val audio = playback.choices(C.TRACK_TYPE_AUDIO)
    val subtitles = playback.choices(C.TRACK_TYPE_TEXT)
    val playing by produceState(playback.player.playWhenReady) {
        while (true) {
            value = playback.player.playWhenReady
            delay(300)
        }
    }

    Column {
        InfoBar(channel, favorite, programmes, now, timeFormat, hints = false, playback = playback)
        playback.error?.let {
            Spacer(Modifier.height(12.dp))
            ErrorBanner("$it Try again, or move to another channel.")
        }
        if (playback.timeshiftOn) LiveTimeshiftBar(playback, focusable) else SeekBar(playback, focusable)
        Spacer(Modifier.height(12.dp))
        var caption by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
            caption?.let {
                Text(it, fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 1.5f), 6f)))
            }
        }
        // Transport in the middle (always the same buttons, so nothing shifts as you press them);
        // channel choices on the left; tracks, favourite and info on the right.
        Box(Modifier.fillMaxWidth().focusGroup()) {
            @Composable
            fun opt(icon: PlayerIcon, label: String, value: String? = null, accent: Boolean = false, requester: FocusRequester? = null, onClick: () -> Unit) {
                Box(Modifier.onFocusChanged { if (it.hasFocus) caption = listOfNotNull(label, value).joinToString("  ·  ") else if (caption?.startsWith(label) == true) caption = null }) {
                    IconOption(icon, label, value, onClick, requester = requester, accent = accent)
                }
            }
            Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                opt(PlayerIcon.Channels, "Channels", onClick = onChannels)
                onLastChannel?.let { opt(PlayerIcon.LastChannel, "Last channel", onClick = it) }
                onRestart?.let { opt(PlayerIcon.Restart, "Watch from the start", "Catch-up", onClick = it) }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playback.error != null) {
                    opt(PlayerIcon.Restart, "Try again", requester = first) { playback.retry() }
                    opt(PlayerIcon.Next, "Next channel", onClick = onNextChannel)
                } else {
                    val live = playback.behindLive() <= 1_000
                    if (playback.timeshiftOn) opt(PlayerIcon.Back10, "Back 10 seconds") { playback.seekLive(-10_000) }
                    opt(if (playing) PlayerIcon.Pause else PlayerIcon.Play, if (playing) "Pause" else "Play", requester = first) { playback.togglePause() }
                    if (playback.timeshiftOn) {
                        opt(PlayerIcon.Forward10, if (live) "Forward 10 seconds (you're live)" else "Forward 10 seconds") { if (!live) playback.seekLive(10_000) }
                        opt(PlayerIcon.Next, if (live) "Live" else "Back to live", accent = !live) { if (!live) playback.goLive() }
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                opt(PlayerIcon.Audio, "Audio", audio.firstOrNull { it.selected }?.label ?: if (audio.size > 1) "${audio.size} tracks" else null, onClick = onAudio)
                opt(PlayerIcon.Subtitles, "Subtitles", when {
                    subtitles.isEmpty() -> "None"
                    playback.subtitlesShowing -> "On"
                    else -> "Off"
                }, onClick = onSubtitles)
                opt(if (favorite) PlayerIcon.FavouriteOn else PlayerIcon.Favourite, if (favorite) "Remove from favourites" else "Add to favourites", onClick = onFavorite)
                opt(PlayerIcon.Info, "Stream info", playback.techParts().joinToString(" · ").ifBlank { null }, onClick = onDetails)
            }
        }
    }
}

/** Progress and seeking, shown only when the stream can actually be moved through. */
@Composable
internal fun SeekBar(
    playback: Playback,
    active: Boolean,
    markers: List<com.zapp.tv.data.Segment> = emptyList(),
    /** The name of the part at a moment ("Intro", "Credits"), shown with the preview. */
    chapterAt: (Long) -> String? = { null },
    thumbs: SeekThumbs? = null,
) {
    val player = playback.player
    var barFocused by remember { mutableStateOf(false) }
    // While you seek: quicker updates, and a moment's memory that you're seeking.
    var seekingUntil by remember { mutableLongStateOf(0L) }
    val state by produceState(Triple(0L, 0L, false), active) {
        var last = player.currentPosition
        while (true) {
            val p = player.currentPosition
            if (kotlin.math.abs(p - last) > 3_000) seekingUntil = System.currentTimeMillis() + 1_600
            last = p
            value = Triple(p, player.duration, player.isCurrentMediaItemSeekable)
            delay(if (barFocused || System.currentTimeMillis() < seekingUntil) 150 else 500)
        }
    }
    val (position, duration, seekable) = state
    if (!seekable || duration <= 0 || duration == C.TIME_UNSET) return

    val seeking = barFocused || System.currentTimeMillis() < seekingUntil
    LaunchedEffect(position / 10_000, seeking) { if (seeking) thumbs?.request(position) }
    val picture = thumbs?.shown?.takeIf { seeking && kotlin.math.abs(it.first - position) < 40_000 }

    Spacer(Modifier.height(2.dp))
    val thick by androidx.compose.animation.core.animateDpAsState(if (barFocused) 6.dp else 3.dp, androidx.compose.animation.core.tween(160), label = "bar")
    val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        // While seeking: a picture from that moment, with the time and part, above the playhead.
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (thumbs != null && seeking) 128.dp else if (seeking) 24.dp else 6.dp)) {
            if (seeking) {
                val boxW = if (picture != null) 176.dp else 70.dp
                val x = (maxWidth - boxW) * fraction
                Column(
                    Modifier.align(Alignment.BottomStart).offset(x = x).width(boxW),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (picture != null) {
                        androidx.compose.foundation.Image(
                            picture.second,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .width(176.dp)
                                .height(99.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        listOfNotNull(formatClock(position), chapterAt(position)).joinToString("  ·  "),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        FocusSurface(
            onClick = { playback.togglePause() },
            onFocusChange = { barFocused = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = if (e.nativeKeyEvent.repeatCount > 4) 60_000L else 10_000L
                    when (e.key) {
                        Key.DirectionRight -> {
                            player.seekTo((player.currentPosition + step).coerceAtMost(player.duration))
                            true
                        }
                        Key.DirectionLeft -> {
                            player.seekTo((player.currentPosition - step).coerceAtLeast(0))
                            true
                        }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(50),
            focusScale = 1f,
            restColor = Color.Transparent,
            focusColor = Color.Transparent,
        ) { focused ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(thick + 10.dp)) {
                    val h = thick.toPx()
                    val y = size.height / 2f
                    val w = size.width
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2f)
                    drawRoundRect(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, y - h / 2f), androidx.compose.ui.geometry.Size(w, h), r)
                    // Intro, recap and credits marked on the bar.
                    for (m in markers) {
                        val a = (m.startMs.toFloat() / duration).coerceIn(0f, 1f) * w
                        val b = (m.endMs.toFloat() / duration).coerceIn(0f, 1f) * w
                        drawRect(Color.White.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(a, y - h / 2f), androidx.compose.ui.geometry.Size((b - a).coerceAtLeast(2f), h))
                    }
                    drawRoundRect(
                        Brush.horizontalGradient(barColors(), startX = 0f, endX = w),
                        androidx.compose.ui.geometry.Offset(0f, y - h / 2f),
                        androidx.compose.ui.geometry.Size(w * fraction, h),
                        r,
                    )
                    if (focused) drawCircle(Color.White, radius = h * 1.25f, center = androidx.compose.ui.geometry.Offset(w * fraction, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatClock(position), color = Color.White.copy(alpha = 0.75f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("-" + formatClock(duration - position), color = Color.White.copy(alpha = 0.55f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format(java.util.Locale.UK, "%d:%02d:%02d", h, m, sec)
    else String.format(java.util.Locale.UK, "%d:%02d", m, sec)
}

@Composable
internal fun SidePanel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(ZappColors.Bg.copy(alpha = 0.00f), ZappColors.Bg.copy(alpha = 0.90f), ZappColors.Bg.copy(alpha = 0.95f))))
            .padding(start = 36.dp, end = 32.dp, top = 32.dp, bottom = 24.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun TrackPanel(
    title: String,
    choices: List<TrackChoice>,
    offOption: Boolean?,
    onPick: (TrackChoice) -> Unit,
    onOff: () -> Unit = {},
    onStyle: (() -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.tryRequest() }
    SidePanel(title) {
        onStyle?.let {
            TrackRow("Timing and look", "Sync, font, bold, style, size", false, null, it)
            Spacer(Modifier.height(8.dp))
        }
        if (choices.isEmpty()) {
            Text(
                if (offOption != null) "This channel has no subtitles." else "This channel has one audio track.",
                color = ZappColors.Dim,
                fontSize = 13.sp,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (offOption != null) {
                item(key = "off") {
                    TrackRow("Off", "", offOption, if (offOption || choices.none { it.selected }) firstFocus else null, onOff)
                }
            }
            items(choices.size) { i ->
                val c = choices[i]
                val takeFocus = c.selected || (offOption == null && i == 0 && choices.none { it.selected })
                TrackRow(c.label, c.detail, c.selected, if (takeFocus) firstFocus else null) { onPick(c) }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, detail: String, selected: Boolean, requester: FocusRequester?, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = requester,
        selected = selected,
        shape = RoundedCornerShape(10.dp),
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotEmpty()) {
                    Text(detail, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = 11.5.sp)
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (focused) ZappColors.OnFocus else ZappColors.Signal),
                )
            }
        }
    }
}

@Composable
internal fun DetailsPanel(playback: Playback) {
    val rows by produceState(playback.details()) {
        while (true) {
            value = playback.details()
            delay(1_000)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequest() }
    SidePanel("Stream info") {
        // A focusable holder so Back and the remote keep working while this panel is open.
        Box(
            Modifier
                .focusRequester(focus)
                .focusable(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (label, value) ->
                    Row {
                        Text(label, color = ZappColors.Dim, fontSize = 12.5.sp, modifier = Modifier.width(120.dp))
                        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Subtitles drawn by the app, so they follow the size and style chosen in Settings. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun SubtitleLayer(cues: List<Cue>, settings: com.zapp.tv.data.Settings, raisedForPanel: Boolean) {
    if (cues.isEmpty()) return
    val size = when (settings.subtitleSize) {
        0 -> 17.sp
        2 -> 27.sp
        3 -> 33.sp
        else -> 22.sp
    }
    val bottom = when {
        raisedForPanel -> 190.dp
        settings.subtitleRaised -> 96.dp
        else -> 40.dp
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 72.dp, bottom = bottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            cues.forEach { cue ->
                val bitmap = cue.bitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(if (cue.size > 0f && cue.size <= 1f) cue.size else 0.7f),
                    )
                }
                cue.text?.toString()?.takeIf { it.isNotBlank() }?.let { text -> SubtitleText(text, size, settings.subtitleStyle) }
            }
        }
    }
}

@Composable
fun SubtitleText(text: String, size: androidx.compose.ui.unit.TextUnit, style: Int) {
    val shadow = Shadow(Color.Black, Offset(0f, 2f), 8f)
    val modifier = if (style == 1) {
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    } else {
        Modifier
    }
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = Inter,
            fontSize = size,
            lineHeight = size * 1.25f,
            fontWeight = FontWeight.Medium,
            color = if (style == 2) Color(0xFFFFE14D) else Color.White,
            textAlign = TextAlign.Center,
            shadow = if (style == 1) null else shadow,
        ),
    )
}

@Composable
private fun InfoBar(
    channel: Channel,
    favorite: Boolean,
    programmes: List<Programme>,
    now: Long,
    timeFormat: java.text.DateFormat,
    hints: Boolean,
    playback: Playback? = null,
) {
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    // What's actually playing (refreshed every few seconds), and a quality badge from it.
    val tech by produceState(emptyList<String>(), channel.streamUrl) {
        while (playback != null) {
            value = playback.techParts()
            delay(2_000)
        }
    }
    val badge = playback?.videoHeight?.takeIf { it > 0 }?.let { heightBadge(it) } ?: qualityBadge(channel.name)
    val next = programmes.firstOrNull { it.start > now }

    Box(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = current?.icon
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(72.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ZappColors.Glass),
                )
                Spacer(Modifier.width(14.dp))
            }
            LogoTile(channel.logo, channel.name, 64.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.number.toString(),
                        color = ZappColors.Dim,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        channel.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (favorite) {
                        Spacer(Modifier.width(8.dp))
                        StarGlyph(ZappColors.Star, 13.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    // Live, or how far behind after pausing or rewinding.
                    val behindMs = if (playback?.timeshiftOn == true) playback.behindLive() else 0L
                    if (behindMs >= 1_000) {
                        Text("−" + formatClock(behindMs), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp))
                    } else {
                        LivePill()
                    }
                    badge?.let {
                        Spacer(Modifier.width(6.dp))
                        QualityPill(it, false)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${timeFormat.format(current.start)} – ${timeFormat.format(current.stop)}",
                            color = ZappColors.Dim,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    ProgressLine(progressOf(current.start, current.stop, now), Modifier.width(420.dp))
                } else {
                    Text("No guide information", color = ZappColors.Dim, fontSize = 13.sp)
                }
                tech.filter { it !in setOf("4K", "1080p", "720p", "SD") }.takeIf { it.isNotEmpty() }?.let { t ->
                    Spacer(Modifier.height(6.dp))
                    Text(t.joinToString("  ·  "), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                }
                if (next != null) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Next at ${timeFormat.format(next.start)}: ${next.title}",
                        color = ZappColors.Faint,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hints) {
                Spacer(Modifier.width(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
                    KeyHint("OK", "Controls")
                    KeyHint("Left", "Channels")
                    KeyHint("Up / Down", "Change channel")
                }
            }
        }
    }
}

@Composable
private fun ChannelListPanel(
    vm: AppViewModel,
    rows: LazyPagingItems<ChannelRow>,
    current: Channel,
    now: Long,
    onPick: (ChannelRow) -> Unit,
    onFavorite: (ChannelRow) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val index = vm.indexInScope(current.streamUrl)
        if (index >= 0) listState.scrollToItem((index - 3).coerceAtLeast(0))
        pendingUrl = if (index >= 0) current.streamUrl else rows.peek(0)?.channel?.streamUrl
    }

    Column(
        Modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(ZappColors.Bg.copy(alpha = 0.95f), ZappColors.Bg.copy(alpha = 0.90f), ZappColors.Bg.copy(alpha = 0.00f))))
            .padding(start = 28.dp, end = 24.dp, top = 28.dp, bottom = 20.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        Text("Channels", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(count = rows.itemCount, key = { it }) { index ->
                val row = rows[index]
                if (row == null) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                    )
                } else {
                    val requester = remember { FocusRequester() }
                    if (row.channel.streamUrl == pendingUrl) {
                        LaunchedEffect(Unit) {
                            requester.tryRequest()
                            pendingUrl = null
                        }
                    }
                    val isCurrent = row.channel.streamUrl == current.streamUrl
                    FocusSurface(
                        onClick = { onPick(row) },
                        onLongClick = { onFavorite(row) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        focusRequester = requester,
                        selected = isCurrent,
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1.02f,
                    ) { focused ->
                        Row(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.channel.number.toString(),
                                color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                                fontSize = 11.sp,
                                modifier = Modifier.width(32.dp),
                                maxLines = 1,
                            )
                            LogoTile(row.channel.logo, row.channel.name, 30.dp, onLight = focused)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.channel.name,
                                    color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                row.nowTitle?.let {
                                    Text(
                                        it,
                                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (row.isFavorite) {
                                Spacer(Modifier.width(6.dp))
                                StarGlyph(ZappColors.Star, 10.dp)
                            }
                            if (isCurrent) {
                                Spacer(Modifier.width(7.dp))
                                LiveDot()
                            }
                        }
                        if (row.nowTitle != null && !focused) {
                            ProgressLine(
                                progressOf(row.nowStart, row.nowStop, now),
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 84.dp, bottom = 4.dp)
                                    .width(70.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KeyHint("OK", "Watch")
            KeyHint("Back", "Close")
        }
    }
}

/** Font for film and series subtitles (Settings: 0 Default, 1 Sans, 2 Serif, 3 Condensed, 4 Typewriter). */
fun subtitleFont(i: Int): androidx.compose.ui.text.font.FontFamily = when (i) {
    1 -> androidx.compose.ui.text.font.FontFamily.SansSerif
    2 -> androidx.compose.ui.text.font.FontFamily.Serif
    3 -> androidx.compose.ui.text.font.FontFamily(
        androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)),
    )
    4 -> androidx.compose.ui.text.font.FontFamily.Monospace
    else -> Inter
}

val SubtitleFontNames = listOf("Default", "Sans", "Serif", "Condensed", "Typewriter")
val SubtitleStyleNames = listOf("Drop shadow", "Outline", "Outline and shadow", "Dark box", "Plain")
val SubtitleSizeNames = listOf("Small", "Medium", "Large", "Extra large")

fun vodSubtitleSize(i: Int) = when (i) {
    0 -> 18.sp
    2 -> 28.sp
    3 -> 34.sp
    else -> 23.sp
}

/** Film and series subtitles, in your chosen font, weight and style. */
@Composable
fun VodSubtitleText(text: String, size: androidx.compose.ui.unit.TextUnit, font: Int, bold: Boolean, style: Int) {
    val base = TextStyle(
        fontFamily = subtitleFont(font),
        fontSize = size,
        lineHeight = size * 1.25f,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 2f), 8f)
    val box = style == 3
    Box(
        if (box) Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 3.dp) else Modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (style == 1 || style == 2) {
            // Outline: the same text drawn as a black stroke underneath.
            Text(
                text,
                style = base.copy(
                    color = Color.Black,
                    drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = size.value * 0.22f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                    shadow = if (style == 2) shadow else null,
                ),
            )
        }
        Text(text, style = base.copy(color = Color.White, shadow = if (style == 0) shadow else null))
    }
}

/**
 * Subtitles for films and series: your own look, and moved by the timing offset when there is one.
 */
@Composable
internal fun VodSubtitleLayer(playback: Playback, settings: com.zapp.tv.data.Settings, raisedForPanel: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember { (context.applicationContext as com.zapp.tv.ZappApp).sharedHttp }
    // With an offset, the text is worked out from the playback position several times a second.
    val shifted by produceState<List<String>?>(null, playback.subtitleOffsetMs, playback.media?.videoId) {
        while (playback.subtitleOffsetMs != 0L) {
            value = playback.subtitleTextNow(http, scope)
            delay(100)
        }
        value = null
    }
    val texts = shifted ?: playback.cues.mapNotNull { it.text?.toString()?.takeIf { t -> t.isNotBlank() } }
    val bitmaps = if (shifted == null) playback.cues.mapNotNull { c -> c.bitmap?.let { it to c.size } } else emptyList()
    if (texts.isEmpty() && bitmaps.isEmpty()) return
    val bottom = when {
        raisedForPanel -> 200.dp
        settings.vodSubRaised -> 96.dp
        else -> 40.dp
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 72.dp, end = 72.dp, bottom = bottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            bitmaps.forEach { (b, w) ->
                Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(if (w > 0f && w <= 1f) w else 0.7f))
            }
            texts.forEach { VodSubtitleText(it, vodSubtitleSize(settings.vodSubSize), settings.vodSubFont, settings.vodSubBold, settings.vodSubStyle) }
        }
    }
}


/** Films and series: subtitle timing and look, changed live over the picture. Left/Right adjust. */
@Composable
internal fun SubtitleStylePanel(playback: Playback, settings: com.zapp.tv.data.Settings) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequest() }
    SidePanel("Subtitles: timing and look") {
        @Composable
        fun row(label: String, value: String, requester: FocusRequester? = null, change: (Int) -> Unit) {
            FocusSurface(
                onClick = { change(1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { change(-1); true }
                            Key.DirectionRight -> { change(1); true }
                            else -> false
                        }
                    },
                focusRequester = requester,
                shape = RoundedCornerShape(10.dp),
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (focused) "‹  $value  ›" else value, color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = 13.sp)
                }
            }
        }
        val o = playback.subtitleOffsetMs
        row(
            "Sync",
            when {
                o == 0L -> "In time"
                o > 0 -> "+%.1f s later".format(java.util.Locale.UK, o / 1000f)
                else -> "%.1f s earlier".format(java.util.Locale.UK, -o / 1000f)
            },
            first,
        ) { d -> playback.setSubtitleOffset(o + d * 100L) }
        row("Font", SubtitleFontNames[settings.vodSubFont.coerceIn(0, 4)]) { d -> settings.vodSubFont = (settings.vodSubFont + d).mod(SubtitleFontNames.size) }
        row("Bold", if (settings.vodSubBold) "On" else "Off") { settings.vodSubBold = !settings.vodSubBold }
        row("Style", SubtitleStyleNames[settings.vodSubStyle.coerceIn(0, 4)]) { d -> settings.vodSubStyle = (settings.vodSubStyle + d).mod(SubtitleStyleNames.size) }
        row("Size", SubtitleSizeNames[settings.vodSubSize.coerceIn(0, 3)]) { d -> settings.vodSubSize = (settings.vodSubSize + d).mod(SubtitleSizeNames.size) }
        row("Position", if (settings.vodSubRaised) "Raised" else "Bottom") { settings.vodSubRaised = !settings.vodSubRaised }
        Spacer(Modifier.height(10.dp))
        Text(
            "Sync is kept for this title; the look is for all films and series (live TV keeps its own). " +
                "Subtitles inside the video can only move later; add-on subtitles can move either way.",
            color = ZappColors.Faint,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            VodSubtitleText("This is how they look.", vodSubtitleSize(settings.vodSubSize) * 0.75f, settings.vodSubFont, settings.vodSubBold, settings.vodSubStyle)
        }
    }
}

/**
 * Live TV with rewind: the last five minutes as a bar, with where you are on it. Left and Right
 * move back and forward (hold for bigger steps); OK pauses; it says "Live" or how far behind.
 */
@Composable
internal fun LiveTimeshiftBar(playback: Playback, active: Boolean) {
    val state by produceState(Triple(0L, 0L, 0L), active) {
        while (true) {
            val now = System.currentTimeMillis()
            value = Triple(playback.liveOldest(), playback.liveWatching(), now)
            delay(500)
        }
    }
    val (oldest, watching, now) = state
    val span = (now - oldest).coerceAtLeast(1)
    val fraction = ((watching - oldest).toFloat() / span).coerceIn(0f, 1f)
    val behind = playback.behindLive()
    var barFocused by remember { mutableStateOf(false) }
    val thick by androidx.compose.animation.core.animateDpAsState(if (barFocused) 6.dp else 3.dp, androidx.compose.animation.core.tween(160), label = "lbar")
    Spacer(Modifier.height(14.dp))
    Column {
        FocusSurface(
            onClick = { playback.togglePause() },
            onFocusChange = { barFocused = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = if (e.nativeKeyEvent.repeatCount > 4) 30_000L else 10_000L
                    when (e.key) {
                        Key.DirectionRight -> { playback.seekLive(step); true }
                        Key.DirectionLeft -> { playback.seekLive(-step); true }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(50),
            focusScale = 1f,
            restColor = Color.Transparent,
            focusColor = Color.Transparent,
        ) { focused ->
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(thick + 10.dp).align(Alignment.Center)) {
                val h = thick.toPx()
                val y = size.height / 2f
                val w = size.width
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, y - h / 2f), androidx.compose.ui.geometry.Size(w, h), r)
                drawRoundRect(Brush.horizontalGradient(barColors(), startX = 0f, endX = w), androidx.compose.ui.geometry.Offset(0f, y - h / 2f), androidx.compose.ui.geometry.Size(w * fraction, h), r)
                if (focused) drawCircle(Color.White, radius = h * 1.25f, center = androidx.compose.ui.geometry.Offset(w * fraction, y))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("−" + formatClock(now - oldest), color = Color.White.copy(alpha = 0.55f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                if (behind < 1_000) "LIVE" else "−" + formatClock(behind),
                color = if (behind < 1_000) Color(0xFFFF5A5F) else Color.White.copy(alpha = 0.8f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}


/**
 * Now and next on the channels either side of this one, over the picture. Up and Down move,
 * OK switches, Back (or Left) closes. It tidies itself away after a while.
 */
@Composable
private fun MiniGuide(vm: AppViewModel, channel: Channel, now: Long, timeFormat: java.text.DateFormat, onPick: (Channel) -> Unit) {
    val rows by produceState<List<Pair<Channel, List<Programme>>>?>(null, channel.streamUrl) {
        val before = ArrayList<Channel>()
        var c: Channel? = channel
        repeat(3) { c = c?.let { vm.neighbor(it, false) }; c?.takeIf { x -> x.streamUrl != channel.streamUrl && before.none { it.streamUrl == x.streamUrl } }?.let { before.add(0, it) } }
        val after = ArrayList<Channel>()
        c = channel
        repeat(3) { c = c?.let { vm.neighbor(it, true) }; c?.takeIf { x -> x.streamUrl != channel.streamUrl && after.none { it.streamUrl == x.streamUrl } && before.none { it.streamUrl == x.streamUrl } }?.let { after.add(it) } }
        value = (before + channel + after).map { it to runCatching { vm.nowNext(it) }.getOrDefault(emptyList()) }
    }
    val currentFocus = remember { FocusRequester() }
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(rows) {
        if (rows != null) {
            delay(80)
            currentFocus.tryRequest()
        }
    }
    LaunchedEffect(poke) {
        delay(10_000)
        onPick(channel) // nothing chosen: close
    }
    Column(
        Modifier
            .padding(end = 40.dp)
            .width(460.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ZappColors.Panel.copy(alpha = 0.92f))
            .padding(10.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) poke++
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onPick(channel); true } else false
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("NOW AND NEXT", color = ZappColors.Brand2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
        val list = rows
        if (list == null) {
            ZappLoader(24.dp, Modifier.align(Alignment.CenterHorizontally).padding(20.dp))
            return@Column
        }
        list.forEach { (c, progs) ->
            val cur = progs.firstOrNull { it.start <= now && it.stop > now }
            val nxt = progs.firstOrNull { it.start > now }
            val here = c.streamUrl == channel.streamUrl
            FocusSurface(
                onClick = { onPick(c) },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = if (here) currentFocus else null,
                shape = RoundedCornerShape(12.dp),
                focusScale = 1f,
                restColor = if (here) ZappColors.Glass else Color.Transparent,
            ) { focused ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoTile(c.logo, c.name, 40.dp, onLight = focused)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur?.title ?: c.name, color = if (focused) ZappColors.OnFocus else ZappColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(c.name.takeIf { cur != null }, nxt?.let { "Next " + timeFormat.format(it.start) + "  " + it.title }).joinToString("  ·  "),
                            color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (cur != null) {
                            Spacer(Modifier.height(5.dp))
                            ProgressLine(((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.fillMaxWidth(), track = if (focused) Color(0x33000000) else ZappColors.Line)
                        }
                    }
                }
            }
        }
    }
}
