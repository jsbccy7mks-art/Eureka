@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.FAVORITES
import com.zapp.tv.data.Programme
import com.zapp.tv.data.RECENT
import kotlinx.coroutines.delay

private data class HomeRow(val key: String, val title: String, val categoryId: Long?, val items: List<ChannelRow>)

private data class HomeFocus(val rowKey: String, val streamUrl: String)

@Composable
fun HomeScreen(
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    onWatch: (row: ChannelRow, categoryId: Long?) -> Unit,
    onOpenLive: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val recent by vm.homeRecent.collectAsState()
    val favorites by vm.homeFavorites.collectAsState()
    val onNow by vm.homeOnNow.collectAsState()
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()

    val rows = buildList {
        if (recent.isNotEmpty()) add(HomeRow("recent", "Continue watching", RECENT, recent))
        if (favorites.isNotEmpty()) add(HomeRow("favorites", "Favourites", FAVORITES, favorites))
        if (onNow.isNotEmpty()) add(HomeRow("onnow", "On now", null, onNow))
    }

    var hero by remember { mutableStateOf<ChannelRow?>(null) }
    var lastFocus by remember { mutableStateOf<HomeFocus?>(null) }
    var pending by remember { mutableStateOf<HomeFocus?>(null) }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    val emptyFocus = remember { FocusRequester() }
    val shown = hero ?: rows.firstOrNull()?.items?.firstOrNull()

    // Startup, or back from the player: return to the card you were on.
    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        val target = lastFocus ?: rows.firstOrNull()?.let { HomeFocus(it.key, it.items.first().channel.streamUrl) }
        if (target != null) pending = target else emptyFocus.tryRequest()
    }

    // Resting on a card previews it in the hero.
    LaunchedEffect(hero?.channel?.streamUrl, vm.settings.preview) {
        val row = hero ?: return@LaunchedEffect
        if (!vm.settings.preview) return@LaunchedEffect
        delay(900)
        playback.play(row.channel)
    }

    Column(Modifier.fillMaxSize()) {
        Hero(
            vm = vm,
            playback = playback,
            row = shown,
            now = now,
            timeFormat = timeFormat,
            empty = rows.isEmpty(),
            emptyFocus = emptyFocus,
            onOpenLive = onOpenLive,
            onOpenGuide = onOpenGuide,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 30.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                Column {
                    Text(
                        row.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
                    )
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 40.dp),
                    ) {
                        items(row.items, key = { it.channel.playlistId.toString() + "|" + it.channel.streamUrl }) { item ->
                            val requester = remember { FocusRequester() }
                            val p = pending
                            if (p != null && p.rowKey == row.key && p.streamUrl == item.channel.streamUrl) {
                                LaunchedEffect(p) {
                                    requester.tryRequest()
                                    pending = null
                                }
                            }
                            ChannelCard(
                                row = item,
                                now = now,
                                playing = playback.isOn(item.channel),
                                requester = requester,
                                onFocus = {
                                    hero = item
                                    lastFocus = HomeFocus(row.key, item.channel.streamUrl)
                                },
                                onClick = { onWatch(item, row.categoryId) },
                                onLongClick = { vm.toggleFavorite(item.channel) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    vm: AppViewModel,
    playback: Playback,
    row: ChannelRow?,
    now: Long,
    timeFormat: java.text.DateFormat,
    empty: Boolean,
    emptyFocus: FocusRequester,
    onOpenLive: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val programmes by produceState(emptyList<Programme>(), row?.channel?.streamUrl, now / 60_000) {
        value = row?.let { vm.nowNext(it.channel) } ?: emptyList()
    }
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }

    Box(
        Modifier
            .fillMaxWidth()
            .height(268.dp),
    ) {
        // Live picture on the right, fading into the page.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 36.dp)
                .width(430.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val showing = row != null && playback.isOn(row.channel)
            if (vm.settings.preview) PreviewVideo(playback, Modifier.fillMaxSize())
            if (!showing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(ZappColors.Brand1.copy(alpha = 0.45f), ZappColors.Brand2.copy(alpha = 0.2f), ZappColors.Panel),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row != null) LogoTile(row.channel.logo, row.channel.name, 84.dp) else LogoMark(56.dp)
                }
            } else if (playback.buffering && playback.error == null) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(0f to Color(0x990A0E1A), 0.35f to Color.Transparent)),
            )
        }

        Column(
            Modifier
                .padding(start = 40.dp, top = 34.dp)
                .widthIn(max = 380.dp),
        ) {
            if (empty || row == null) {
                ZappLogo(26.dp)
                Spacer(Modifier.height(22.dp))
                Text("Ready when you are", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Channels you watch and favourite will appear here. Hold OK on any channel to make it a favourite.",
                    color = ZappColors.Dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZButton("Live TV", onOpenLive, style = ButtonStyle.Primary, focusRequester = emptyFocus)
                    ZButton("TV guide", onOpenGuide)
                }
            } else {
                AnimatedContent(
                    targetState = row,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it.channel.playlistId.toString() + it.channel.streamUrl },
                    label = "hero",
                ) { r ->
                    Column {
                        LogoTile(r.channel.logo, r.channel.name, 46.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            current?.title ?: r.channel.name,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp,
                            lineHeight = 31.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (current != null) {
                                LivePill()
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (current != null) {
                                    "${r.channel.name}, ${timeFormat.format(current.start)} – ${timeFormat.format(current.stop)}"
                                } else {
                                    "${r.channel.name}. No guide information for this channel."
                                },
                                color = ZappColors.Dim,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current != null) {
                            Spacer(Modifier.height(10.dp))
                            ProgressLine(progressOf(current.start, current.stop, now), Modifier.width(220.dp))
                            current.description?.let {
                                Spacer(Modifier.height(10.dp))
                                Text(it, color = ZappColors.Faint, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    row: ChannelRow,
    now: Long,
    playing: Boolean,
    requester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.width(168.dp)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            focusRequester = requester,
            onFocusChange = { if (it) onFocus() },
            shape = RoundedCornerShape(14.dp),
            focusScale = 1.07f,
            restColor = ZappColors.Glass,
        ) { focused ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LogoTile(row.channel.logo, row.channel.name, 52.dp, onLight = focused)
            }
            if (row.isFavorite) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { StarGlyph(ZappColors.Star, 11.dp) }
            }
            if (playing) {
                Box(Modifier.align(Alignment.TopStart).padding(9.dp)) { LiveDot(7.dp) }
            }
            if (row.nowTitle != null) {
                ProgressLine(
                    progressOf(row.nowStart, row.nowStop, now),
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .fillMaxWidth(),
                    track = if (focused) Color(0xFFD5DBE8) else ZappColors.Line,
                    fill = if (focused) ZappColors.OnFocus else ZappColors.Signal,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(row.channel.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            row.nowTitle ?: "No guide information",
            color = if (row.nowTitle != null) ZappColors.Dim else ZappColors.Faint,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
