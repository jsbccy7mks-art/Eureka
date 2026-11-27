@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.zapp.tv.data.ChannelSort
import kotlinx.coroutines.launch
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
private val WideRowHeight = 56.dp

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
    val sort by vm.channelSort.collectAsState()
    val rows = vm.channels.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val categoryState = rememberLazyListState()
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()
    val coroutines = rememberCoroutineScope()

    var focusedRow by remember { mutableStateOf<ChannelRow?>(null) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusUrl by remember { mutableStateOf<String?>(null) }
    // After a move: the place the moved channel lands in (only that row takes focus).
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    var focusedCategory by remember { mutableStateOf<CategoryKey?>(null) }
    val categoryFocus = remember { FocusRequester() }
    val channelMemory = rememberFocusMemory()
    var handledTick by remember { mutableIntStateOf(focusTick) }
    // In the channel list, the categories slide away and the list takes their room (more of the guide).
    var wide by remember { mutableStateOf(vm.lastChannelUrl != null) }
    var categoryFocusWanted by remember { mutableStateOf(false) }
    var categoriesFocused by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<Int, ChannelRow>?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }

    fun showCategories() {
        moving = null
        wide = false
        categoryFocusWanted = true
    }

    /** Into the channel list: the channel you were on in it, or the first. */
    fun focusChannels() {
        val target = (channelMemory.last as? Int)?.let { channelMemory.requester(it) }
            ?: channelMemory.requester(listState.firstVisibleItemIndex)
        target?.tryRequest()
    }

    /** Opens a category: the channel list takes over, from its first channel (or where you were, if it's the same one). */
    fun openCategory(id: Long?) {
        val changed = id != scope.categoryId
        vm.selectCategory(id)
        coroutines.launch {
            if (changed) {
                listState.scrollToItem(0)
                channelMemory.last = 0
            }
            delay(30)
            focusChannels()
        }
    }

    // Moving through categories previews them after a short pause, so scrolling past never reloads.
    LaunchedEffect(focusedCategory) {
        val target = focusedCategory ?: return@LaunchedEffect
        delay(260)
        vm.selectCategory(target.id)
    }

    // A new list starts at its first channel.
    LaunchedEffect(scope.playlistId, scope.categoryId) {
        listState.scrollToItem(0)
        channelMemory.last = 0
    }

    // Back (or Left) from the channels: the categories come back, on the one you're in.
    LaunchedEffect(categoryFocusWanted, wide) {
        if (!categoryFocusWanted || wide) return@LaunchedEffect
        val shown = categories.filter { !it.hidden }
        val i = shown.indexOfFirst { it.id == scope.categoryId }
        if (i >= 0 && categoryState.layoutInfo.visibleItemsInfo.none { it.index == i }) categoryState.scrollToItem((i - 3).coerceAtLeast(0))
        // Asked again if the column wasn't ready the first time.
        repeat(4) {
            delay(40)
            if (categoriesFocused) return@repeat
            categoryFocus.tryRequest()
        }
        categoryFocusWanted = false
    }

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
            if (first != null) {
                pendingFocusUrl = first.channel.streamUrl
            } else {
                wide = false
                delay(40)
                categoryFocus.tryRequest()
            }
        }
    }

    /** Moves the channel being moved one place, and keeps focus (and the list) with it. */
    fun move(row: ChannelRow, by: Int) {
        coroutines.launch {
            if (!vm.moveChannel(row.channel, by)) return@launch
            val to = vm.indexInScope(row.channel.streamUrl)
            if (to < 0) return@launch
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isNotEmpty()) {
                val first = visible.first().index
                val last = visible.last().index
                if (to <= first) listState.scrollToItem((to - 1).coerceAtLeast(0))
                else if (to >= last) listState.scrollToItem((to - (last - first) + 2).coerceAtLeast(0))
            }
            pendingFocusIndex = to
            pendingFocusUrl = row.channel.streamUrl
        }
    }

    // What's on the channel you're resting on (now and the next few), shared by the backdrop and preview.
    val programmes by produceState(emptyList<Programme>(), focusedRow?.channel?.streamUrl, now / 60_000) {
        value = focusedRow?.let { vm.nowNext(it.channel) } ?: emptyList()
    }
    val currentArt = programmes.firstOrNull { it.start <= now && it.stop > now }?.icon
    val canMove = !scope.favorites && !scope.recent
    // A Box, so the backdrop sits behind the page instead of taking up room above it.
    Box(Modifier.fillMaxSize()) {
    SoftBackdrop(currentArt, strength = 0.45f)

    val title = categories.firstOrNull { it.id == scope.categoryId }?.name ?: "All channels"
    val currentPlaylist = playlists.firstOrNull { it.playlist.id == scope.playlistId }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 32.dp, bottom = 22.dp),
    ) {
        // ------------------------------------------------------------ categories
        androidx.compose.animation.AnimatedVisibility(
            visible = !wide,
            enter = androidx.compose.animation.expandHorizontally(tween(220), expandFrom = Alignment.Start) + fadeIn(tween(200, delayMillis = 40)),
            exit = androidx.compose.animation.shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start) + fadeOut(tween(120)),
        ) {
            Row {
                Column(
                    Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .onFocusChanged {
                            categoriesFocused = it.hasFocus
                            if (it.hasFocus) wide = false
                        }
                        .onPreviewKeyEvent { e ->
                            // Right on a category opens it straight away (not after the preview pause).
                            if (e.key != Key.DirectionRight || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val f = focusedCategory ?: return@onPreviewKeyEvent false
                            if (f.id == scope.categoryId) return@onPreviewKeyEvent false
                            openCategory(f.id)
                            true
                        },
                ) {
                    if (playlists.size > 1 && currentPlaylist != null) {
                        FocusSurface(
                            onClick = { vm.nextPlaylist() },
                            onFocusChange = { if (it) focusedCategory = null },
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
                                        fontSize = TextSize.Label,
                                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                                    )
                                    Text(
                                        currentPlaylist.playlist.name,
                                        fontSize = TextSize.Body,
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

                    CategoryList(
                        vm = vm,
                        categories = categories,
                        selectedId = scope.categoryId,
                        playlistId = scope.playlistId,
                        state = categoryState,
                        selectedRequester = categoryFocus,
                        modifier = Modifier.weight(1f),
                        onFocus = { if (it.id != HIDDEN_CATEGORIES) focusedCategory = CategoryKey(it.id) },
                        // OK opens the category: the list takes over the screen.
                        onPick = { item -> openCategory(item.id) },
                    )
                }
                Spacer(Modifier.width(18.dp))
            }
        }

        // ------------------------------------------------------------ channels
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = title,
                    fontSize = TextSize.Title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                if (rows.itemCount > 0) {
                    Text(formatCount(rows.itemCount), color = ZappColors.Faint, fontSize = TextSize.Body)
                }
                if (wide) {
                    Spacer(Modifier.weight(1f))
                    Text("‹ Categories", color = ZappColors.Faint, fontSize = TextSize.Small)
                }
            }

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .restoreFocus(channelMemory)
                        .onFocusChanged { if (it.hasFocus) wide = true }
                        .onPreviewKeyEvent { e ->
                            val m = moving
                            if (m != null) {
                                val row = focusedRow?.takeIf { it.channel.streamUrl == m }
                                when (e.key) {
                                    Key.DirectionUp, Key.DirectionDown -> {
                                        if (e.type == KeyEventType.KeyDown && row != null) move(row, if (e.key == Key.DirectionUp) -1 else 1)
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.DirectionLeft, Key.DirectionRight -> {
                                        if (e.type == KeyEventType.KeyUp) moving = null
                                        return@onPreviewKeyEvent true
                                    }
                                    else -> return@onPreviewKeyEvent false
                                }
                            }
                            when (e.key) {
                                // Left from the channels brings the categories back.
                                Key.DirectionLeft -> {
                                    if (e.type == KeyEventType.KeyDown) showCategories()
                                    true
                                }
                                // So does Back (and Back again goes on to the sidebar, as before).
                                Key.Back -> {
                                    if (e.type == KeyEventType.KeyUp) showCategories()
                                    true
                                }
                                else -> false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    // Keyed by position, and a row that is still loading is the same focusable element
                    // as a loaded one: when the list reloads (for example as the guide updates),
                    // the item under the remote is never removed from the screen.
                    items(count = rows.itemCount, key = { it }) { index ->
                        val row = rows[index]
                        val focusRequester = remember { FocusRequester() }
                        val wanted = pendingFocusIndex
                        if (row != null && row.channel.streamUrl == pendingFocusUrl && (wanted == null || wanted == index)) {
                            LaunchedEffect(Unit) {
                                focusRequester.tryRequest()
                                pendingFocusUrl = null
                                pendingFocusIndex = null
                            }
                        }
                        ChannelItem(
                            modifier = Modifier.rememberFocus(channelMemory, index),
                            row = row,
                            wide = wide,
                            moving = row != null && row.channel.streamUrl == moving,
                            playing = row != null && playback.isOn(row.channel),
                            now = now,
                            timeFormat = timeFormat,
                            focusRequester = focusRequester,
                            onFocus = {
                                focusedIndex = index
                                if (row != null) {
                                    focusedRow = row
                                    vm.lastChannelUrl = row.channel.streamUrl
                                }
                            },
                            onClick = { if (row != null) onWatch(row) },
                            onLongClick = { if (row != null) menuFor = index to row },
                        )
                    }
                }

                if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading) {
                    Text(
                        text = when (scope.categoryId) {
                            FAVORITES -> "No favourites yet. Hold OK on any channel and choose Add to favourites."
                            RECENT -> "Channels you watch will appear here."
                            else -> "No channels here."
                        },
                        color = ZappColors.Dim,
                        fontSize = TextSize.Body,
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

    menuFor?.let { (index, row) ->
        ChannelMenu(
            vm = vm,
            row = row,
            onMove = if (canMove && sort != ChannelSort.NAME) ({
                vm.useOwnOrder()
                moving = row.channel.streamUrl
            }) else null,
            moveHint = when {
                !canMove -> null
                sort == ChannelSort.NAME -> "Channels are sorted by name. To move them, choose Your own order in Settings, Live TV, Sort channels."
                else -> null
            },
            onHidden = {
                // The last row leaves the list: focus goes to the one above.
                if (index == rows.itemCount - 1 && index > 0) {
                    coroutines.launch {
                        delay(300)
                        channelMemory.requester(index - 1)?.tryRequest()
                    }
                }
            },
            onClose = { menuFor = null },
        )
    }
}

@Composable
private fun ChannelItem(
    modifier: Modifier,
    row: ChannelRow?,
    wide: Boolean,
    moving: Boolean,
    playing: Boolean,
    now: Long,
    timeFormat: java.text.DateFormat,
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
            .height(if (wide) WideRowHeight else RowHeight),
        focusRequester = focusRequester,
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(11.dp),
        focusScale = if (wide) 1.015f else 1.025f,
        restColor = if (moving) ZappColors.GlassHigh else Color.Transparent,
    ) { focused ->
        if (row == null) return@FocusSurface
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (moving) {
                Text("↕", color = if (focused) ZappColors.OnFocus else ZappColors.Brand2, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(34.dp))
            } else {
                Text(
                    text = row.channel.number.toString(),
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                    fontSize = TextSize.Small,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.width(34.dp),
                )
            }
            LogoTile(url = row.channel.logo, fallback = row.channel.name, size = 34.dp, onLight = focused)
            Spacer(Modifier.width(11.dp))
            if (wide) {
                WideChannelText(row, focused, moving, playing, now, timeFormat)
                return@Row
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelName(row, focused, playing, Modifier.weight(1f, fill = false))
                }
                val nowTitle = row.nowTitle
                if (nowTitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = nowTitle,
                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim,
                        fontSize = TextSize.Small,
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

/** The channel's name, with its picture quality, a star for favourites and a dot when it's playing. */
@Composable
private fun ChannelName(row: ChannelRow, focused: Boolean, playing: Boolean, modifier: Modifier) {
    Text(
        text = row.channel.name,
        color = if (focused) ZappColors.OnFocus else ZappColors.Text,
        fontSize = TextSize.Body,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
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

/**
 * The wide list (categories put away): the name on its own, then what's on now with how far in it
 * is and how long is left, and what's next with its start time.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.WideChannelText(
    row: ChannelRow,
    focused: Boolean,
    moving: Boolean,
    playing: Boolean,
    now: Long,
    timeFormat: java.text.DateFormat,
) {
    Row(Modifier.width(158.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelName(row, focused, playing, Modifier.weight(1f, fill = false))
    }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        val nowTitle = row.nowTitle
        if (moving) {
            Text("Up and Down to move, OK to put it here", color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = TextSize.Small, maxLines = 1)
            return@Column
        }
        if (nowTitle == null) {
            Text("No guide information", color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, fontSize = TextSize.Small, maxLines = 1)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nowTitle,
                color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                fontSize = TextSize.Small,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            row.nowStop?.let { stop ->
                Spacer(Modifier.width(8.dp))
                Text(
                    "${((stop - now) / 60_000).coerceAtLeast(1)} min left",
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                    fontSize = TextSize.Label,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ProgressLine(
            fraction = progressOf(row.nowStart, row.nowStop, now),
            modifier = Modifier.fillMaxWidth(),
            track = if (focused) Color(0xFFD5DBE8) else ZappColors.Line,
            fill = if (focused) ZappColors.OnFocus else ZappColors.Signal,
            thickness = 2.dp,
        )
        val next = row.nextTitle
        val nextStart = row.nextStart
        if (next != null && nextStart != null) {
            Spacer(Modifier.height(4.dp))
            Row {
                Text(timeFormat.format(nextStart), color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = TextSize.Label, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(next, color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, fontSize = TextSize.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Text(it, color = Color.White, fontSize = TextSize.Small, modifier = Modifier.padding(16.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (row == null) {
            Text("Pick a channel", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Rest on a channel to preview it here.", color = ZappColors.Dim, fontSize = TextSize.Small)
            return@Column
        }

        // Channel line: logo, name, quality, favourite.
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(url = row.channel.logo, fallback = row.channel.name, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                row.channel.name,
                fontSize = TextSize.Body,
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
            Text("No guide information for this channel.", color = ZappColors.Faint, fontSize = TextSize.Small)
        }
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LivePill()
                Spacer(Modifier.width(8.dp))
                val left = ((current.stop - now) / 60_000).coerceAtLeast(1)
                Text(
                    "${timeFormat.format(current.start)} – ${timeFormat.format(current.stop)}  ·  $left min left",
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(current.title, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            ProgressLine(progressOf(current.start, current.stop, now), Modifier.fillMaxWidth())
            current.description?.let {
                Spacer(Modifier.height(9.dp))
                Text(it, color = ZappColors.Dim, fontSize = TextSize.Small, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (later.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("LATER", color = ZappColors.Faint, fontSize = TextSize.Tag, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(6.dp))
            later.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(timeFormat.format(p.start), color = ZappColors.Faint, fontSize = TextSize.Small, modifier = Modifier.width(52.dp))
                    Text(p.title, color = ZappColors.Text, fontSize = TextSize.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text("OK to watch  ·  Hold OK for options", color = ZappColors.Faint, fontSize = TextSize.Small)
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
        fontSize = TextSize.Tag,
        fontWeight = FontWeight.SemiBold,
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
