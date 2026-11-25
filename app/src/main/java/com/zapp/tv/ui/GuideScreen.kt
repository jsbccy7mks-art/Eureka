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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.zapp.tv.data.ChannelRow
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
    onWatch: (ChannelRow) -> Unit,
) {
    val scope = vm.scope.collectAsState().value ?: return
    val categories by vm.categories.collectAsState()
    val rows = vm.channels.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val now = rememberNow()
    val timeFormat = rememberTimeFormat()

    val minStart = floorSlot(now) - 4 * SLOT
    val maxStart = floorSlot(now) + 44 * 2 * SLOT
    var windowStart by remember { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    var pending by remember { mutableStateOf<GuideFocus?>(null) }
    var selection by remember { mutableStateOf<GuideSelection?>(null) }
    var handledTick by remember { mutableIntStateOf(focusTick) }

    LaunchedEffect(scope.playlistId, scope.categoryId) { listState.scrollToItem(0) }

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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = 28.dp, end = 32.dp, bottom = 18.dp),
    ) {
        val gridWidth = maxWidth - ChannelColumn - GridGap
        val dpPerMs = gridWidth.value / WINDOW

        Column(Modifier.fillMaxSize()) {
            InfoPanel(selection, categoryName, now, timeFormat)
            Spacer(Modifier.height(12.dp))
            TimeRuler(windowStart, gridWidth, dpPerMs, now, timeFormat)
            Spacer(Modifier.height(6.dp))

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(count = rows.itemCount, key = rows.itemKey { it.channel.id }) { index ->
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
                            )
                        }
                    }
                }

                // "Now" line across the grid.
                if (now in windowStart until windowStart + WINDOW) {
                    Box(
                        Modifier
                            .offset(x = ChannelColumn + GridGap + ((now - windowStart) * dpPerMs).dp - 1.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(ZappColors.Signal.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(selection: GuideSelection?, categoryName: String, now: Long, timeFormat: java.text.DateFormat) {
    val dayFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(84.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selection == null) {
            Column(Modifier.weight(1f)) {
                Text(categoryName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Move onto a programme to see what it's about.", color = ZappColors.Dim, fontSize = 12.sp)
            }
        } else {
            LogoTile(url = selection.row.channel.logo, fallback = selection.row.channel.name, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val p = selection.programme
                Text(
                    text = p?.title ?: selection.row.channel.name,
                    fontSize = 18.sp,
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
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                p?.description?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(it, color = ZappColors.Faint, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(dayFormat.format(now), color = ZappColors.Dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            KeyHint("OK", "Watch")
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
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Box(Modifier.width(gridWidth)) {
            for (k in 0 until 4) {
                val t = windowStart + k * SLOT
                Text(
                    text = timeFormat.format(t),
                    color = ZappColors.Dim,
                    fontSize = 11.sp,
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
            modifier = Modifier
                .width(ChannelColumn)
                .fillMaxHeight(),
            onFocusChange = { if (it) onSelect(GuideSelection(row, null)) },
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
                    fontSize = 10.5.sp,
                    modifier = Modifier.width(30.dp),
                    maxLines = 1,
                )
                LogoTile(channel.logo, channel.name, 28.dp, onLight = focused)
                Spacer(Modifier.width(8.dp))
                Text(
                    channel.name,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                    fontSize = 12.sp,
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
                        fontSize = 11.5.sp,
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
                            onClick = { onWatch(row) },
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
) {
    val airing = programme.start <= now && programme.stop > now
    val past = programme.stop <= now
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        focusRequester = requester,
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(9.dp),
        focusScale = 1f,
        restColor = if (airing) Color(0x24FFFFFF) else Color(0x10FFFFFF),
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
                fontSize = 12.sp,
                fontWeight = if (airing || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeFormat.format(programme.start),
                color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (airing && !focused) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progressOf(programme.start, programme.stop, now))
                    .height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ZappColors.Signal),
            )
        }
    }
}
