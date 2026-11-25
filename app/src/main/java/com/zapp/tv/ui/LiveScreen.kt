@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.FAVORITES
import com.zapp.tv.data.RECENT
import com.zapp.tv.data.Programme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private data class CategoryKey(val id: Long?)

private val RowHeight = 50.dp

@Composable
fun LiveScreen(
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    onWatch: (ChannelRow) -> Unit,
) {
    val scope = vm.scope.collectAsState().value ?: return
    val categories by vm.categories.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val rows = vm.channels.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()

    var focusedRow by remember { mutableStateOf<ChannelRow?>(null) }
    var pendingFocusUrl by remember { mutableStateOf<String?>(null) }
    var focusedCategory by remember { mutableStateOf<CategoryKey?>(null) }
    val categoryFocus = remember { FocusRequester() }
    val categoryMemory = rememberFocusMemory()
    val channelMemory = rememberFocusMemory()
    var handledTick by remember { mutableIntStateOf(focusTick) }

    // Moving through categories previews them after a short pause, so scrolling past never reloads.
    LaunchedEffect(focusedCategory) {
        val target = focusedCategory ?: return@LaunchedEffect
        delay(260)
        vm.selectCategory(target.id)
    }

    LaunchedEffect(scope.playlistId, scope.categoryId) { listState.scrollToItem(0) }

    // Resting on a channel plays it in the preview window.
    LaunchedEffect(focusedRow?.channel?.streamUrl, vm.settings.preview) {
        val row = focusedRow ?: return@LaunchedEffect
        if (!vm.settings.preview) return@LaunchedEffect
        delay(650)
        playback.play(row.channel)
    }

    // Startup, or back from the player: land on the channel you were on.
    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        val url = vm.lastChannelUrl
        val index = if (url != null) vm.indexInScope(url) else -1
        if (url != null && index >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!visible) listState.scrollToItem((index - 3).coerceAtLeast(0))
            pendingFocusUrl = url
        } else {
            val count = withTimeoutOrNull(4_000) { snapshotFlow { rows.itemCount }.first { it > 0 } }
            val first = if (count != null) rows.peek(0) else null
            if (first != null) pendingFocusUrl = first.channel.streamUrl else categoryFocus.tryRequest()
        }
    }

    // What's on the channel you're resting on (now and the next few), shared by the backdrop and preview.
    val programmes by produceState(emptyList<Programme>(), focusedRow?.channel?.streamUrl, now / 60_000) {
        value = focusedRow?.let { vm.nowNext(it.channel) } ?: emptyList()
    }
    val currentArt = programmes.firstOrNull { it.start <= now && it.stop > now }?.icon
    SoftBackdrop(currentArt, strength = 0.45f)

    val title = categories.firstOrNull { it.id == scope.categoryId }?.name ?: "All channels"
    val currentPlaylist = playlists.firstOrNull { it.playlist.id == scope.playlistId }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 32.dp, bottom = 22.dp),
    ) {
        // ------------------------------------------------------------ categories
        Column(
            Modifier
                .width(200.dp)
                .fillMaxHeight(),
        ) {
            if (playlists.size > 1 && currentPlaylist != null) {
                FocusSurface(
                    onClick = { vm.nextPlaylist() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    restColor = ZappColors.Glass,
                ) { focused ->
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Playlist",
                                fontSize = 10.sp,
                                color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                            )
                            Text(
                                currentPlaylist.playlist.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ChevronGlyph(if (focused) ZappColors.OnFocus else ZappColors.Dim, 12.dp, down = false)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .restoreFocus(categoryMemory),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(categories, key = { it.id ?: -1L }) { category ->
                    CategoryItemRow(
                        modifier = Modifier.rememberFocus(categoryMemory, category.id ?: -1L),
                        item = category,
                        selected = category.id == scope.categoryId,
                        focusRequester = if (category.id == scope.categoryId) categoryFocus else null,
                        onFocus = { focusedCategory = CategoryKey(category.id) },
                        onClick = { vm.selectCategory(category.id) },
                    )
                }
            }
        }

        Spacer(Modifier.width(18.dp))

        // ------------------------------------------------------------ channels
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                if (rows.itemCount > 0) {
                    Text(formatCount(rows.itemCount), color = ZappColors.Faint, fontSize = 13.sp)
                }
            }

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .restoreFocus(channelMemory),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    // Keyed by position, and a row that is still loading is the same focusable element
                    // as a loaded one: when the list reloads (for example as the guide updates),
                    // the item under the remote is never removed from the screen.
                    items(count = rows.itemCount, key = { it }) { index ->
                        val row = rows[index]
                        val focusRequester = remember { FocusRequester() }
                        if (row != null && row.channel.streamUrl == pendingFocusUrl) {
                            LaunchedEffect(Unit) {
                                focusRequester.tryRequest()
                                pendingFocusUrl = null
                            }
                        }
                        ChannelItem(
                            modifier = Modifier.rememberFocus(channelMemory, index),
                            row = row,
                            playing = row != null && playback.isOn(row.channel),
                            now = now,
                            focusRequester = focusRequester,
                            onFocus = {
                                if (row != null) {
                                    focusedRow = row
                                    vm.lastChannelUrl = row.channel.streamUrl
                                }
                            },
                            onClick = { if (row != null) onWatch(row) },
                            onLongClick = { if (row != null) vm.toggleFavorite(row.channel) },
                        )
                    }
                }

                if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading) {
                    Text(
                        text = when (scope.categoryId) {
                            FAVORITES -> "No favourites yet. Hold OK on any channel to add it."
                            RECENT -> "Channels you watch will appear here."
                            else -> "No channels here."
                        },
                        color = ZappColors.Dim,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(20.dp))

        // ------------------------------------------------------------ preview
        PreviewPane(
            vm = vm,
            playback = playback,
            row = focusedRow,
            programmes = programmes,
            now = now,
            timeFormat = timeFormat,
            modifier = Modifier
                .width(330.dp)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun CategoryItemRow(
    modifier: Modifier,
    item: CategoryItem,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        selected = false,
        focusRequester = focusRequester,
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(9.dp),
        focusScale = 1.03f,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The open category: a short bar in the theme's colours, like the sidebar.
            if (selected && !focused) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.verticalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                )
                Spacer(Modifier.width(9.dp))
            }
            if (item.id == FAVORITES) {
                StarGlyph(if (focused) ZappColors.OnFocus else ZappColors.Star, 11.dp)
                Spacer(Modifier.width(8.dp))
            }
            if (item.id == RECENT) {
                ClockGlyph(if (focused) ZappColors.OnFocus else ZappColors.Dim, 11.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = item.name,
                color = when {
                    focused -> ZappColors.OnFocus
                    selected -> ZappColors.Text
                    else -> ZappColors.Dim
                },
                fontSize = 13.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.count >= 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatCount(item.count),
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ChannelItem(
    modifier: Modifier,
    row: ChannelRow?,
    playing: Boolean,
    now: Long,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight),
        focusRequester = focusRequester,
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(11.dp),
        focusScale = 1.025f,
    ) { focused ->
        if (row == null) return@FocusSurface
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.channel.number.toString(),
                color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.width(34.dp),
            )
            LogoTile(url = row.channel.logo, fallback = row.channel.name, size = 34.dp, onLight = focused)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.channel.name,
                        color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    qualityBadge(row.channel.name)?.let { q ->
                        Spacer(Modifier.width(6.dp))
                        QualityPill(q, focused)
                    }
                    if (row.isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        StarGlyph(ZappColors.Star, 10.dp)
                    }
                    if (playing) {
                        Spacer(Modifier.width(7.dp))
                        LiveDot()
                    }
                }
                val nowTitle = row.nowTitle
                if (nowTitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = nowTitle,
                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(
                        fraction = progressOf(row.nowStart, row.nowStop, now),
                        modifier = Modifier.width(90.dp),
                        track = if (focused) Color(0xFFD5DBE8) else ZappColors.Line,
                        fill = if (focused) ZappColors.OnFocus else ZappColors.Signal,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(
    vm: AppViewModel,
    playback: Playback,
    row: ChannelRow?,
    programmes: List<Programme>,
    now: Long,
    timeFormat: java.text.DateFormat,
    modifier: Modifier = Modifier,
) {
    val showingVideo = row != null && playback.isOn(row.channel)
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    val later = programmes.filter { it.start > now }.take(3)
    val art = current?.icon

    Column(modifier) {
        // The picture: live preview when it's playing, otherwise the programme's artwork or the logo.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (vm.settings.preview) PreviewVideo(playback, Modifier.fillMaxSize())
            if (!showingVideo) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(ZappColors.Brand1.copy(alpha = 0.25f), ZappColors.Panel, ZappColors.Brand2.copy(alpha = 0.15f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        art != null -> AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        row != null -> LogoTile(url = row.channel.logo, fallback = row.channel.name, size = 64.dp)
                        else -> LogoMark(40.dp)
                    }
                }
            } else {
                if (playback.buffering && playback.error == null) ZappLoader(22.dp)
                playback.error?.let {
                    Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (row == null) {
            Text("Pick a channel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Rest on a channel to preview it here.", color = ZappColors.Dim, fontSize = 12.sp)
            return@Column
        }

        // Channel line: logo, name, quality, favourite.
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(url = row.channel.logo, fallback = row.channel.name, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                row.channel.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZappColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val realBadge = if (showingVideo && playback.videoHeight > 0) heightBadge(playback.videoHeight) else null
            (realBadge ?: qualityBadge(row.channel.name))?.let { q ->
                Spacer(Modifier.width(8.dp))
                QualityPill(q, false)
            }
            if (row.isFavorite) {
                Spacer(Modifier.width(7.dp))
                StarGlyph(ZappColors.Star, 11.dp)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (current == null && later.isEmpty()) {
            Text("No guide information for this channel.", color = ZappColors.Faint, fontSize = 12.sp)
        }
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LivePill()
                Spacer(Modifier.width(8.dp))
                val left = ((current.stop - now) / 60_000).coerceAtLeast(1)
                Text(
                    "${timeFormat.format(current.start)} – ${timeFormat.format(current.stop)}  ·  $left min left",
                    color = ZappColors.Dim,
                    fontSize = 11.5.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(current.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            ProgressLine(progressOf(current.start, current.stop, now), Modifier.fillMaxWidth())
            current.description?.let {
                Spacer(Modifier.height(9.dp))
                Text(it, color = ZappColors.Dim, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (later.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("LATER", color = ZappColors.Faint, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(6.dp))
            later.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(timeFormat.format(p.start), color = ZappColors.Faint, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    Text(p.title, color = ZappColors.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("OK to watch  ·  Hold OK to favourite", color = ZappColors.Faint, fontSize = 11.sp)
    }
}

/** UHD, FHD, HD or SD from a channel's name ("Sky Sports UHD", "BBC One FHD", "ITV 4K"). */
fun qualityBadge(name: String): String? {
    val n = " " + name.uppercase().replace(Regex("[^A-Z0-9]+"), " ") + " "
    return when {
        " UHD " in n || " 4K " in n || " 2160P " in n -> "UHD"
        " FHD " in n || " 1080P " in n || " FULLHD " in n || " FULL HD " in n -> "FHD"
        " HD " in n || " 720P " in n || " HEVC " in n -> "HD"
        " SD " in n || " 576P " in n || " 480P " in n -> "SD"
        else -> null
    }
}

fun heightBadge(h: Int): String = when {
    h >= 2000 -> "UHD"
    h >= 1000 -> "FHD"
    h >= 700 -> "HD"
    else -> "SD"
}

@Composable
fun QualityPill(text: String, onLight: Boolean) {
    Text(
        text,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = if (onLight) ZappColors.OnFocus else if (text == "UHD") Color.White else ZappColors.Dim,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (text == "UHD" && !onLight) Modifier.background(Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2)))
                else Modifier.border(1.dp, if (onLight) Color(0x55000000) else Color(0x44FFFFFF), RoundedCornerShape(4.dp)),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
