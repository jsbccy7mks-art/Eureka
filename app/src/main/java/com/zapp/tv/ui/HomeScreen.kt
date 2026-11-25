@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.addons.CatalogRef
import com.zapp.tv.addons.MetaPreview
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.WatchProgress
import kotlinx.coroutines.delay

private data class HomeFocus(val rowKey: String, val itemKey: String)


/** A long press on a card: options for its row (and for that title, in Continue watching). */
private data class RowOptions(val rowId: String, val progress: WatchProgress? = null)

private fun WatchProgress.asPreview() =
    MetaPreview(metaId, type, name, poster, "poster", background, null, null, null, null, emptyList())

/**
 * Home: a large header for whatever you're resting on, with your chosen add-on catalogues below
 * it. Continue watching always sits first. Hold OK on any card to resize, reshape, move or
 * hide its row.
 */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    playback: Playback,
    focusTick: Int,
    mvm: MediaViewModel,
    onOpenTitle: (MetaPreview) -> Unit,
    onResume: (WatchProgress) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val addons by mvm.addons.collectAsState()
    val continueItems by mvm.continueWatching.collectAsState()
    val catalogs by produceState(emptyList<CatalogRef>(), addons) { value = mvm.catalogs(null) }

    val layout = remember(catalogs, mvm.settings.homeRowsJson) { mvm.homeLayout(catalogs) }
    val rows = layout.filter { r -> r.on && (if (r.id == CONTINUE_ROW) continueItems.isNotEmpty() else catalogs.any { it.key == r.id }) }

    var focused by remember { mutableStateOf<HeroItem?>(null) }
    var lastFocus by remember { mutableStateOf<HomeFocus?>(null) }
    var pending by remember { mutableStateOf<HomeFocus?>(null) }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    var options by remember { mutableStateOf<RowOptions?>(null) }
    val emptyFocus = remember { FocusRequester() }

    // Home never plays live TV in the background.
    LaunchedEffect(Unit) { if (playback.channel != null) playback.stop() }

    // Startup, or back from a title or the player: return to the card you were on.
    LaunchedEffect(focusTick, rows.isNotEmpty()) {
        if (focusTick == handledTick && rows.isNotEmpty() && lastFocus != null) return@LaunchedEffect
        handledTick = focusTick
        val first = rows.firstOrNull()
        val target = lastFocus ?: when {
            first == null -> null
            first.id == CONTINUE_ROW -> HomeFocus(first.id, continueItems.first().videoId)
            else -> HomeFocus(first.id, "")
        }
        if (target != null) pending = target else emptyFocus.tryRequest()
    }

    ImmersiveLayout(
        mvm = mvm,
        focused = focused,
        header = if (rows.isEmpty()) ({ EmptyHero(emptyFocus, onOpenAddons) }) else null,
    ) {
        items(rows, key = { it.id }) { row ->
            if (row.id == CONTINUE_ROW) {
                ContinueRow(
                    items = continueItems,
                    size = row.size,
                    pendingId = pending?.takeIf { it.rowKey == row.id }?.itemKey,
                    onPendingDone = { pending = null },
                    onFocusItem = { p ->
                        focused = HeroItem(p.asPreview(), "Continue watching", p)
                        lastFocus = HomeFocus(row.id, p.videoId)
                    },
                    onClick = onResume,
                    onLongPress = { p -> options = RowOptions(row.id, p) },
                )
            } else {
                val ref = catalogs.first { it.key == row.id }
                val p = pending?.takeIf { it.rowKey == row.id }
                CatalogRow(
                    mvm = mvm,
                    ref = ref,
                    title = row.title,
                    onFocusItem = { meta ->
                        focused = HeroItem(meta, row.title)
                        lastFocus = HomeFocus(row.id, meta.id)
                    },
                    onOpen = onOpenTitle,
                    pendingId = p?.itemKey,
                    onPendingDone = { pending = null },
                    size = row.size,
                    landscape = row.landscape,
                    showTitles = false,
                    onLongPress = { options = RowOptions(row.id) },
                )
            }
        }
    }

    options?.let { o ->
        val row = layout.firstOrNull { it.id == o.rowId }
        if (row == null) {
            options = null
        } else {
            RowOptionsDialog(
                row = row,
                progress = o.progress,
                canMoveUp = row.id != CONTINUE_ROW && layout.indexOf(row) > 1,
                canMoveDown = row.id != CONTINUE_ROW && layout.indexOf(row) < layout.size - 1,
                onChange = { change -> mvm.updateRow(layout, row.id, change) },
                onMove = { by -> mvm.moveRow(layout, row.id, by) },
                onRemoveTitle = { p ->
                    mvm.hideFromContinue(p.metaId)
                    vm.showToast("Removed ${p.name} from Continue watching")
                    options = null
                },
                onClose = { options = null },
            )
        }
    }
}

@Composable
private fun EmptyHero(focus: FocusRequester, onOpenAddons: () -> Unit) {
    Column(
        Modifier
            .padding(start = RowInset, top = 44.dp)
            .widthIn(max = 440.dp),
    ) {
        ZappLogo(26.dp)
        Spacer(Modifier.height(24.dp))
        Text("Nothing on Home yet", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Install an add-on with catalogues and they'll show up here. Films and series you start appear in Continue watching.",
            color = ZappColors.Dim,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(20.dp))
        ZButton("Add-ons", onOpenAddons, style = ButtonStyle.Primary, focusRequester = focus)
    }
}

@Composable
private fun ContinueRow(
    items: List<WatchProgress>,
    size: Int,
    pendingId: String?,
    onPendingDone: () -> Unit,
    onFocusItem: (WatchProgress) -> Unit,
    onClick: (WatchProgress) -> Unit,
    onLongPress: (WatchProgress) -> Unit,
) {
    val memory = rememberFocusMemory()
    Column {
        RowHeader("Continue watching")
        PivotScrolling(RowInset) {
            LazyRow(
                modifier = Modifier.restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = RowInset),
            ) {
                items(items, key = { it.videoId }) { p ->
                    val requester = remember { FocusRequester() }
                    if (pendingId == p.videoId) {
                        LaunchedEffect(Unit) {
                            requester.tryRequest()
                            onPendingDone()
                        }
                    }
                    ContinueCard(
                        progress = p,
                        modifier = Modifier
                            .rememberFocus(memory, p.videoId)
                            .focusRequesterSafe(requester),
                        onFocus = { onFocusItem(p) },
                        onClick = { onClick(p) },
                        onLongClick = { onLongPress(p) },
                        width = cardWidth(size, true),
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelCard(
    row: ChannelRow,
    modifier: Modifier = Modifier,
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
            modifier = modifier
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
