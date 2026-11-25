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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.zapp.tv.data.Channel
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.Programme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ZapUp = setOf(Key.DirectionUp, Key.ChannelDown)
private val ZapDown = setOf(Key.DirectionDown, Key.ChannelUp)
private val SelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * Fullscreen player.
 * Up/Down: previous/next channel. OK or Left: channel list. Right: programme info.
 * Hold OK or Menu: favourite. Back: return to where you were.
 */
@Composable
fun PlayerOverlay(
    vm: AppViewModel,
    playback: Playback,
    rows: LazyPagingItems<ChannelRow>,
    onClose: () -> Unit,
) {
    val channel = playback.channel ?: return
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()

    var infoVisible by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    var listVisible by remember { mutableStateOf(false) }
    var zapJob by remember { mutableStateOf<Job?>(null) }
    var favTick by remember { mutableIntStateOf(0) }
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
        if (listVisible) listVisible = false else onClose()
    }

    LaunchedEffect(listVisible) { if (!listVisible) focus.tryRequest() }

    // A channel counts as watched after ten seconds, so quick zapping doesn't fill your history.
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
            .onKeyEvent { e ->
                if (listVisible) return@onKeyEvent false
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
                            listVisible = true
                            true
                        }
                        key == Key.DirectionRight -> {
                            if (infoVisible) infoVisible = false else infoTick++
                            true
                        }
                        key == Key.Menu -> {
                            toggleFavorite(channel)
                            true
                        }
                        key == Key.MediaPlayPause || key == Key.MediaPlay || key == Key.MediaPause -> {
                            playback.togglePause()
                            true
                        }
                        else -> false
                    }
                    KeyEventType.KeyUp -> when {
                        key in SelectKeys -> {
                            val shortPress = press[0] && !press[1]
                            press[0] = false
                            press[1] = false
                            if (shortPress) {
                                if (playback.error != null) playback.retry() else listVisible = true
                            }
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

        if (playback.buffering && playback.error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        }

        playback.error?.let { message ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZappColors.Panel)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Press OK to try again, or up and down for another channel.", color = ZappColors.Dim, fontSize = 12.sp)
            }
        }

        AnimatedVisibility(
            visible = infoVisible && !listVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            InfoBar(channel, favorite, programmes, now, timeFormat)
        }

        AnimatedVisibility(
            visible = listVisible,
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
                    listVisible = false
                    infoTick++
                },
                onFavorite = { row -> toggleFavorite(row.channel) },
                onClose = { listVisible = false },
            )
        }
    }
}

@Composable
private fun InfoBar(
    channel: Channel,
    favorite: Boolean,
    programmes: List<Programme>,
    now: Long,
    timeFormat: java.text.DateFormat,
) {
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    val next = programmes.firstOrNull { it.start > now }

    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9070A14), Color(0xF2070A14))))
            .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    LivePill()
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
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.Start) {
                KeyHint("OK", "Channels")
                KeyHint("Up / Down", "Change channel")
                KeyHint("Hold OK", "Favourite")
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
            .background(Brush.horizontalGradient(listOf(Color(0xF20A0E1A), Color(0xE60A0E1A), Color(0x000A0E1A))))
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
            items(count = rows.itemCount, key = rows.itemKey { it.channel.id }) { index ->
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
