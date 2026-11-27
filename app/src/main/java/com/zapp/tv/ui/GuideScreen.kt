@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.GuideInfo
import com.zapp.tv.data.Programme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale

private const val SLOT = 30 * 60_000L
private const val WINDOW = 4 * SLOT // two hours across the screen
private const val NEAR_EDGE = 10 * 60_000L
private val GuideRowHeight = 44.dp
private val ChannelColumn = 186.dp
private val GridGap = 8.dp

private fun floorSlot(t: Long) = t - Math.floorMod(t, SLOT)

private data class GuideFocus(val channelId: Long, val time: Long)

private data class GuideSelection(val row: ChannelRow, val programme: Programme?)

@Composable
fun GuideScreen(
    vm: AppViewModel,
    focusTick: Int,
    onCatchUp: (ChannelRow, Programme) -> Unit = { _, _ -> },
    onWatch: (ChannelRow) -> Unit,
) {
    val scope = vm.scope.collectAsState().value ?: return
    val categories by vm.categories.collectAsState()
    val rows = vm.guideChannels.collectAsLazyPagingItems()
    val liveOnly by vm.guideLiveOnly.collectAsState()
    val guideInfo by produceState<GuideInfo?>(null, scope.playlistId, vm.guideStatus) {
        value = vm.guideInfo(scope.playlistId)
    }
    val listState = rememberLazyListState()
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()

    val minStart = floorSlot(now) - 4 * SLOT
    val maxStart = floorSlot(now) + 44 * 2 * SLOT
    var windowStart by remember { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    var pending by remember { mutableStateOf<GuideFocus?>(null) }
    var selection by remember { mutableStateOf<GuideSelection?>(null) }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    // Left from the channel column: the categories slide in over the guide.
    var panelOpen by remember { mutableStateOf(false) }
    var panelReturn by remember { mutableIntStateOf(-1) }
    val panelState = rememberLazyListState()
    val panelSelected = remember { FocusRequester() }
    val channelCells = rememberFocusMemory()
    var menuFor by remember { mutableStateOf<ChannelRow?>(null) }
    val coroutines = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(scope.playlistId, scope.categoryId) { listState.scrollToItem(0) }

    var panelHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (!panelOpen) return@LaunchedEffect
        val i = categories.filter { !it.hidden }.indexOfFirst { it.id == scope.categoryId }
        if (i >= 0) panelState.scrollToItem((i - 3).coerceAtLeast(0))
        // Into the panel, on the category you're in (asked again if the list wasn't ready the first time).
        repeat(4) {
            delay(80)
            if (panelHasFocus) return@LaunchedEffect
            panelSelected.tryRequest()
        }
    }

    /** Closes the category panel and puts focus back in the guide ([row] -1: where it was). */
    fun closePanel(row: Int = panelReturn) {
        panelOpen = false
        coroutines.launch {
            delay(60)
            (channelCells.requester(row) ?: channelCells.requester(listState.firstVisibleItemIndex))?.tryRequest()
        }
    }

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        val url = vm.lastChannelUrl
        var index = if (url != null) vm.indexInScope(url) else -1
        if (index < 0) index = 0
        listState.scrollToItem((index - 2).coerceAtLeast(0))
        val row = withTimeoutOrNull(4_000) {
            snapshotFlow { if (rows.itemCount > index) rows.peek(index) else null }.first { it != null }
        } ?: return@LaunchedEffect
        val t = System.currentTimeMillis()
        if (t < windowStart || t >= windowStart + WINDOW) windowStart = floorSlot(t)
        pending = GuideFocus(row.channel.id, t)
    }

    val categoryName = categories.firstOrNull { it.id == scope.categoryId }?.name ?: "All channels"

    // The programme you're on, softly behind the page (only the top, above the grid).
    // A Box, so the backdrop sits behind the page instead of taking up room above it.
    Box(Modifier.fillMaxSize()) {
    SoftBackdrop(selection?.programme?.icon, strength = 0.35f)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 32.dp, bottom = 18.dp),
    ) {
        val gridWidth = maxWidth - ChannelColumn - GridGap
        val dpPerMs = gridWidth.value / WINDOW

        Column(Modifier.fillMaxSize()) {
            val sel = selection
            val selP = sel?.programme
            val okLabel = when {
                sel == null || selP == null -> "Watch"
                vm.canCatchUp(sel.row.channel, selP.start, selP.stop) -> "Replay"
                selP.start > now -> if (vm.hasReminder(sel.row.channel, selP)) "Cancel reminder" else "Remind me"
                else -> "Watch"
            }
            InfoPanel(selection, categoryName, now, timeFormat, okLabel)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(
                    options = listOf("all" to "All channels", "live" to "On now only"),
                    value = if (liveOnly) "live" else "all",
                    onChange = { if ((it == "live") != liveOnly) vm.toggleGuideLiveOnly() },
                )
                ZButton(
                    text = if (vm.guideStatus != null) "Updating…" else "Update guide",
                    onClick = { vm.refreshGuideFor(scope.playlistId) },
                    style = ButtonStyle.Quiet,
                )
                guideInfo?.let { info ->
                    if (info.updated > 0) {
                        Text(
                            "Updated ${timeFormat.format(info.updated)}",
                            color = ZappColors.Faint,
                            fontSize = TextSize.Small,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val info = guideInfo
            val message = when {
                info == null -> null
                !info.hasSource -> "This playlist has no TV guide link. Add one in Settings, Playlists, Guide link."
                info.programmes == 0 && vm.guideStatus != null -> "Downloading the TV guide…"
                info.programmes == 0 && info.updated == 0L -> "The TV guide hasn't downloaded yet. Press Update guide."
                info.programmes == 0 -> "The TV guide is empty: none of its channels match this playlist."
                liveOnly && rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading ->
                    "Nothing on air right now in this list. Switch to All channels."
                else -> null
            }
            if (message != null) {
                ErrorBanner(message)
                Spacer(Modifier.height(10.dp))
            }
            TimeRuler(windowStart, gridWidth, dpPerMs, now, timeFormat)
            Spacer(Modifier.height(6.dp))

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    // Keyed by position (like the channel list): a reloading or changing list never pulls
                    // the row under the remote off the screen.
                    items(count = rows.itemCount, key = { it }) { index ->
                        val row = rows[index]
                        if (row == null) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .height(GuideRowHeight),
                            )
                        } else {
                            GuideRow(
                                vm = vm,
                                row = row,
                                channelCellModifier = Modifier.rememberFocus(channelCells, index),
                                onChannelFocus = {},
                                onOpenCategories = {
                                    panelReturn = index
                                    panelOpen = true
                                },
                                onChannelMenu = { menuFor = row },
                                windowStart = windowStart,
                                minStart = minStart,
                                maxStart = maxStart,
                                gridWidth = gridWidth,
                                dpPerMs = dpPerMs,
                                now = now,
                                timeFormat = timeFormat,
                                pending = pending,
                                onPendingDone = { pending = null },
                                onShift = { start, focus ->
                                    windowStart = start.coerceIn(minStart, maxStart)
                                    pending = focus
                                },
                                onSelect = { selection = it },
                                onWatch = onWatch,
                                onCatchUp = onCatchUp,
                            )
                        }
                    }
                }

                // "Now" line across the grid.
                if (now in windowStart until windowStart + WINDOW) {
                    val x = ChannelColumn + GridGap + ((now - windowStart) * dpPerMs).dp
                    // A soft glow in the theme's colours, with a sharp line through it.
                    Box(
                        Modifier
                            .offset(x = x - 14.dp)
                            .width(28.dp)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, ZappColors.Brand1.copy(alpha = 0.22f), Color.Transparent))),
                    )
                    Box(
                        Modifier
                            .offset(x = x - 1.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Brush.verticalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                    )
                    Box(
                        Modifier
                            .offset(x = x - 4.dp)
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(ZappColors.Brand2),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ category panel
    androidx.compose.animation.AnimatedVisibility(
        visible = panelOpen,
        enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { -it / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
        exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { -it / 3 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.45f to Color(0xA0000000), 1f to Color(0x30000000))),
            )
            Column(
                Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .background(ZappColors.Panel.copy(alpha = 0.96f))
                    .padding(start = 18.dp, end = 14.dp, top = 18.dp, bottom = 16.dp)
                    .onPreviewKeyEvent { e ->
                        when (e.key) {
                            Key.Back -> {
                                if (e.type == KeyEventType.KeyUp) closePanel()
                                true
                            }
                            Key.DirectionRight -> {
                                if (e.type == KeyEventType.KeyDown) closePanel()
                                true
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged { panelHasFocus = it.hasFocus }
                    // Focus stays in the panel while it's open.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup(),
            ) {
                Text("Categories", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.height(4.dp))
                Text("OK to show it in the guide", color = ZappColors.Faint, fontSize = TextSize.Small, modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.height(10.dp))
                CategoryList(
                    vm = vm,
                    categories = categories,
                    selectedId = scope.categoryId,
                    playlistId = scope.playlistId,
                    state = panelState,
                    selectedRequester = panelSelected,
                    modifier = Modifier.weight(1f),
                    onPick = { item ->
                        if (item.count == 0) {
                            vm.showToast("No channels in ${item.name}")
                        } else {
                            vm.selectCategory(item.id)
                            panelOpen = false
                            coroutines.launch {
                                listState.scrollToItem(0)
                                delay(60)
                                channelCells.requester(0)?.tryRequest()
                            }
                        }
                    },
                )
            }
        }
    }

    menuFor?.let { row -> ChannelMenu(vm, row, onMove = null, moveHint = null) { menuFor = null } }
    }
}

@Composable
private fun InfoPanel(selection: GuideSelection?, categoryName: String, now: Long, timeFormat: java.text.DateFormat, okLabel: String) {
    val dayFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selection == null) {
            Column(Modifier.weight(1f)) {
                Text(categoryName, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Move onto a programme to see what it's about. OK on one that hasn't started sets a reminder.", color = ZappColors.Dim, fontSize = TextSize.Small)
            }
        } else {
            val art = selection.programme?.icon
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(84.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZappColors.Glass),
                )
            } else {
                LogoTile(url = selection.row.channel.logo, fallback = selection.row.channel.name, size = 52.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val p = selection.programme
                Text(
                    text = p?.title ?: selection.row.channel.name,
                    fontSize = TextSize.Title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p != null && p.start <= now && p.stop > now) {
                        LivePill()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (p != null) {
                            "${selection.row.channel.name}, ${timeFormat.format(p.start)} – ${timeFormat.format(p.stop)}"
                        } else {
                            "No guide information"
                        },
                        color = ZappColors.Dim,
                        fontSize = TextSize.Small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                p?.description?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(it, color = ZappColors.Faint, fontSize = TextSize.Small, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(dayFormat.format(now), color = ZappColors.Dim, fontSize = TextSize.Small)
            Spacer(Modifier.height(8.dp))
            KeyHint("OK", okLabel)
            Spacer(Modifier.height(6.dp))
            KeyHint("‹", "Categories")
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, gridWidth: Dp, dpPerMs: Float, now: Long, timeFormat: java.text.DateFormat) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp),
    ) {
        Box(Modifier.width(ChannelColumn + GridGap)) {
            val dayFormat = remember { SimpleDateFormat("EEE d", Locale.getDefault()) }
            Text(
                text = if (windowStart / 86_400_000L == now / 86_400_000L) "Today" else dayFormat.format(windowStart),
                color = ZappColors.Faint,
                fontSize = TextSize.Small,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Box(Modifier.width(gridWidth)) {
            for (k in 0 until 4) {
                val t = windowStart + k * SLOT
                Text(
                    text = timeFormat.format(t),
                    color = ZappColors.Dim,
                    fontSize = TextSize.Small,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.offset(x = (k * SLOT * dpPerMs).dp + 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel,
    row: ChannelRow,
    channelCellModifier: Modifier,
    onChannelFocus: () -> Unit,
    onOpenCategories: () -> Unit,
    onChannelMenu: () -> Unit,
    windowStart: Long,
    minStart: Long,
    maxStart: Long,
    gridWidth: Dp,
    dpPerMs: Float,
    now: Long,
    timeFormat: java.text.DateFormat,
    pending: GuideFocus?,
    onPendingDone: () -> Unit,
    onShift: (Long, GuideFocus) -> Unit,
    onSelect: (GuideSelection) -> Unit,
    onWatch: (ChannelRow) -> Unit,
    onCatchUp: (ChannelRow, Programme) -> Unit,
) {
    val channel = row.channel
    val epgId = channel.epgId
    val programmes by produceState(emptyList<Programme>(), channel.playlistId, epgId, windowStart) {
        value = if (epgId.isNullOrBlank()) {
            emptyList()
        } else {
            vm.programmes(channel.playlistId, epgId, windowStart - 8 * SLOT, windowStart + WINDOW + 12 * SLOT)
        }
    }
    val windowEnd = windowStart + WINDOW

    Row(
        Modifier
            .fillMaxWidth()
            .height(GuideRowHeight),
    ) {
        // Channel cell
        FocusSurface(
            onClick = { onWatch(row) },
            onLongClick = onChannelMenu,
            modifier = channelCellModifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .onPreviewKeyEvent { e ->
                    // Left from the channel column opens the categories (instead of the sidebar).
                    if (e.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) onOpenCategories()
                    true
                },
            onFocusChange = {
                if (it) {
                    onChannelFocus()
                    onSelect(GuideSelection(row, null))
                }
            },
            shape = RoundedCornerShape(10.dp),
            restColor = ZappColors.Glass,
        ) { focused ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    channel.number.toString(),
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                    fontSize = TextSize.Label,
                    modifier = Modifier.width(30.dp),
                    maxLines = 1,
                )
                LogoTile(channel.logo, channel.name, 28.dp, onLight = focused)
                Spacer(Modifier.width(8.dp))
                Text(
                    channel.name,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                    fontSize = TextSize.Small,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(GridGap))

        Box(
            Modifier
                .width(gridWidth)
                .fillMaxHeight(),
        ) {
            val visible = programmes.filter { it.stop > windowStart && it.start < windowEnd }
            if (visible.isEmpty()) {
                val requester = remember { FocusRequester() }
                if (pending != null && pending.channelId == channel.id) {
                    LaunchedEffect(pending) {
                        requester.tryRequest()
                        onPendingDone()
                    }
                }
                FocusSurface(
                    onClick = { onWatch(row) },
                    modifier = Modifier.fillMaxSize(),
                    focusRequester = requester,
                    onFocusChange = { if (it) onSelect(GuideSelection(row, null)) },
                    shape = RoundedCornerShape(10.dp),
                    focusScale = 1f,
                    restColor = Color(0x0AFFFFFF),
                ) { focused ->
                    Text(
                        text = if (epgId.isNullOrBlank()) "No guide for this channel" else "No information",
                        color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                        fontSize = TextSize.Small,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = 12.dp),
                    )
                }
            } else {
                visible.forEach { p ->
                    key(p.id) {
                        val i = programmes.indexOf(p)
                        val from = maxOf(p.start, windowStart)
                        val to = minOf(p.stop, windowEnd)
                        val x = ((from - windowStart) * dpPerMs).dp
                        val w = (((to - from) * dpPerMs).dp - 3.dp).coerceAtLeast(3.dp)
                        val requester = remember { FocusRequester() }
                        if (pending != null && pending.channelId == channel.id && pending.time >= p.start && pending.time < p.stop) {
                            LaunchedEffect(pending) {
                                requester.tryRequest()
                                onPendingDone()
                            }
                        }
                        ProgrammeCell(
                            programme = p,
                            now = now,
                            timeFormat = timeFormat,
                            clippedLeft = p.start < windowStart,
                            requester = requester,
                            modifier = Modifier
                                .offset(x = x)
                                .width(w)
                                .fillMaxHeight()
                                .onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionRight -> {
                                            val next = programmes.getOrNull(i + 1)
                                            when {
                                                next == null -> {
                                                    if (p.stop > windowEnd && windowStart + SLOT <= maxStart) {
                                                        onShift(windowStart + SLOT, GuideFocus(channel.id, p.start))
                                                    }
                                                    true
                                                }
                                                next.start >= windowEnd - NEAR_EDGE -> {
                                                    val target = maxOf(floorSlot(next.start) - SLOT, windowStart + SLOT)
                                                    if (target <= maxStart) onShift(target, GuideFocus(channel.id, next.start))
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        Key.DirectionLeft -> {
                                            val prev = programmes.getOrNull(i - 1)
                                            when {
                                                prev == null -> {
                                                    if (p.start < windowStart && windowStart - SLOT >= minStart) {
                                                        onShift(windowStart - SLOT, GuideFocus(channel.id, p.start))
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                prev.stop <= windowStart + NEAR_EDGE -> {
                                                    val target = minOf(floorSlot(prev.start), windowStart - SLOT).coerceAtLeast(minStart)
                                                    if (target < windowStart && prev.stop > target) {
                                                        onShift(target, GuideFocus(channel.id, prev.start))
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                else -> false
                                            }
                                        }
                                        else -> false
                                    }
                                },
                            onFocus = { onSelect(GuideSelection(row, p)) },
                            replay = vm.canCatchUp(channel, p.start, p.stop),
                            reminder = vm.hasReminder(channel, p),
                            // Past programmes on catch-up channels play from the start; ones still to come set
                            // a reminder; anything else tunes in.
                            onClick = {
                                when {
                                    vm.canCatchUp(channel, p.start, p.stop) -> onCatchUp(row, p)
                                    p.start > System.currentTimeMillis() -> vm.toggleReminder(channel, p)
                                    else -> onWatch(row)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: Programme,
    now: Long,
    timeFormat: java.text.DateFormat,
    clippedLeft: Boolean,
    requester: FocusRequester,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    replay: Boolean = false,
    reminder: Boolean = false,
) {
    val airing = programme.start <= now && programme.stop > now
    val past = programme.stop <= now
    val sport = remember(programme.title) { looksLikeSport(programme.title) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        focusRequester = requester,
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(9.dp),
        focusScale = 1f,
        // On now: a touch of the theme's colour; sport: a touch of green.
        restColor = when {
            sport -> if (airing) Color(0x3334D399) else Color(0x1A34D399)
            airing -> androidx.compose.ui.graphics.lerp(Color(0x24FFFFFF), ZappColors.Brand1.copy(alpha = 0.28f), 0.5f)
            else -> Color(0x10FFFFFF)
        },
    ) { focused ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = (if (clippedLeft) "‹ " else "") + programme.title,
                color = when {
                    focused -> ZappColors.OnFocus
                    past -> ZappColors.Faint
                    else -> ZappColors.Text
                },
                fontSize = TextSize.Small,
                fontWeight = if (airing || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reminder) {
                    BellGlyph(if (focused) ZappColors.OnFocus else ZappColors.Brand2, 10.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = timeFormat.format(programme.start) + if (replay) "  ·  ↺ Replay" else "",
                    color = if (focused) ZappColors.OnFocusDim else if (replay) ZappColors.Signal else ZappColors.Faint,
                    fontSize = TextSize.Label,
                    maxLines = 1,
                )
            }
        }
        if (airing && !focused) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progressOf(programme.start, programme.stop, now))
                    .height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
            )
        }
    }
}

private val SportWords = Regex(
    "\\b(v|vs)\\b|premier league|champions league|football|soccer|rugby|cricket|tennis|golf|formula 1|\\bf1\\b|grand prix|nfl|nba|mlb|nhl|boxing|ufc|darts|snooker|motogp|match of the day|sky sports|live:",
    RegexOption.IGNORE_CASE,
)

private fun looksLikeSport(title: String) = SportWords.containsMatchIn(title)
