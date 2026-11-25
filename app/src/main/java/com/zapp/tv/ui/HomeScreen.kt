@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.foundation.lazy.itemsIndexed
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
private data class RowOptions(val rowId: String, val progress: WatchProgress? = null, val meta: MetaPreview? = null)

fun WatchProgress.asPreview() =
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
    // Smart rows: what to suggest from what you've watched and saved.
    val libraryItems by mvm.library.collectAsState()
    val seed = continueItems.firstOrNull()?.asPreview() ?: libraryItems.firstOrNull()?.let { MetaPreview(it.metaId, it.type, it.name, it.poster, "poster", null, null, null, null, null, emptyList()) }
    val because by produceState<Pair<String, List<MetaPreview>>?>(null, seed?.id) { value = runCatching { mvm.becauseYouWatched(seed) }.getOrNull() }
    val newEpisodes by produceState(emptyList<Pair<MetaPreview, String>>(), libraryItems.map { it.metaId }) { value = runCatching { mvm.newEpisodes(libraryItems) }.getOrDefault(emptyList()) }
    val libraryPreviews = libraryItems.map { MetaPreview(it.metaId, it.type, it.name, it.poster, "poster", null, null, null, null, null, emptyList()) }

    val rows = layout.filter { r ->
        r.on && when (r.id) {
            CONTINUE_ROW -> continueItems.isNotEmpty()
            BECAUSE_ROW -> because != null
            NEW_EPISODES_ROW -> newEpisodes.isNotEmpty()
            LIBRARY_ROW -> libraryPreviews.isNotEmpty()
            else -> catalogs.any { it.key == r.id }
        }
    }

    var focused by remember { mutableStateOf<HeroItem?>(null) }
    var lastFocus by remember { mutableStateOf<HomeFocus?>(null) }
    var pending by remember { mutableStateOf<HomeFocus?>(null) }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    var options by remember { mutableStateOf<RowOptions?>(null) }
    val emptyFocus = remember { FocusRequester() }

    // Home never plays live TV in the background.
    LaunchedEffect(Unit) { if (playback.channel != null) playback.stop() }

    // Where focus goes:
    // - first start, and whenever Continue watching shows up before you've moved: its first card,
    //   so the row is always in view at the top (it may load after the catalogues);
    // - back from a title or the player: the card you were on.
    var moves by remember { mutableIntStateOf(0) }
    val firstRowId = rows.firstOrNull()?.id
    LaunchedEffect(focusTick, firstRowId) {
        val tickChanged = focusTick != handledTick
        handledTick = focusTick
        val first = rows.firstOrNull()
        val start = when {
            first == null -> null
            first.id == CONTINUE_ROW -> HomeFocus(first.id, continueItems.first().videoId)
            else -> HomeFocus(first.id, "")
        }
        val target = when {
            tickChanged && lastFocus != null && moves > 1 -> lastFocus
            moves <= 1 -> start
            else -> null
        }
        if (target != null) pending = target else if (rows.isEmpty()) emptyFocus.tryRequest()
    }

    // Back from further down first returns to the top row; Back again goes to the sidebar.
    var topTick by remember { mutableIntStateOf(0) }
    val atTop = lastFocus == null || lastFocus?.rowKey == rows.firstOrNull()?.id
    androidx.activity.compose.BackHandler(enabled = !atTop && rows.isNotEmpty()) {
        val first = rows.first()
        topTick++
        pending = if (first.id == CONTINUE_ROW) HomeFocus(first.id, continueItems.first().videoId) else HomeFocus(first.id, "")
    }

    ImmersiveLayout(
        mvm = mvm,
        focused = focused,
        header = if (rows.isEmpty()) ({ EmptyHero(emptyFocus, onOpenAddons) }) else null,
        scrollToTop = if (moves <= 1) firstRowId else null,
        topTick = topTick,
        topRight = { ClockAndAvatar(mvm) },
    ) {
        items(rows, key = { it.id }) { row ->
            if (row.id == CONTINUE_ROW) {
                ContinueRow(
                    art = mvm.showArt,
                    items = continueItems,
                    size = row.size,
                    pendingId = pending?.takeIf { it.rowKey == row.id }?.itemKey,
                    onPendingDone = { pending = null },
                    onFocusItem = { p ->
                        val a = mvm.showArt[p.metaId]
                        focused = HeroItem(p.asPreview().copy(name = a?.name?.takeIf { p.name.startsWith("tt") || p.name.isBlank() } ?: p.name, logo = a?.logo, background = a?.background ?: p.background), "Continue watching", p)
                        lastFocus = HomeFocus(row.id, p.videoId)
                        moves++
                    },
                    onClick = onResume,
                    onLongPress = { p ->
                        val a = mvm.showArt[p.metaId]
                        options = RowOptions(row.id, p, p.asPreview().copy(name = a?.name?.takeIf { p.name.startsWith("tt") || p.name.isBlank() } ?: p.name))
                    },
                )
            } else if (row.id == BECAUSE_ROW || row.id == NEW_EPISODES_ROW || row.id == LIBRARY_ROW) {
                val title = when (row.id) {
                    BECAUSE_ROW -> "Because you watched ${because?.first.orEmpty()}"
                    NEW_EPISODES_ROW -> "New episodes"
                    else -> "Your library"
                }
                val items = when (row.id) {
                    BECAUSE_ROW -> because?.second.orEmpty()
                    NEW_EPISODES_ROW -> newEpisodes.map { it.first }
                    else -> libraryPreviews
                }
                MetaRow(
                    title = title,
                    items = items,
                    badges = if (row.id == NEW_EPISODES_ROW) newEpisodes.associate { it.first.id to "New · ${it.second}" } else emptyMap(),
                    size = row.size,
                    landscape = row.landscape,
                    pendingId = pending?.takeIf { it.rowKey == row.id }?.itemKey,
                    onPendingDone = { pending = null },
                    onFocusItem = { meta ->
                        focused = HeroItem(meta, title)
                        lastFocus = HomeFocus(row.id, meta.id)
                        moves++
                    },
                    onOpen = onOpenTitle,
                    onLongPress = { m -> options = RowOptions(row.id, meta = m) },
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
                        moves++
                    },
                    onOpen = onOpenTitle,
                    pendingId = p?.itemKey,
                    onPendingDone = { pending = null },
                    size = row.size,
                    landscape = row.landscape,
                    top10 = row.top10 == true,
                    showTitles = false,
                    onLongPress = { m -> options = RowOptions(row.id, meta = m) },
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
                mvm = mvm,
                meta = o.meta,
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
    art: Map<String, MediaViewModel.ShowArt>,
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
        val extra = cardWidth(size, true) + 24.dp
        PivotScrolling(RowInset + extra) {
            LazyRow(
                modifier = Modifier
                    .extendLeft(extra)
                    .fadeLeftOf(extra + RowInset)
                    .restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = RowInset + extra, end = RowInset),
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
                        art = art[p.metaId],
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

/** A row of titles Zapp put together itself (suggestions, new episodes, your library). */
@Composable
private fun MetaRow(
    title: String,
    items: List<MetaPreview>,
    badges: Map<String, String>,
    size: Int,
    landscape: Boolean,
    pendingId: String?,
    onPendingDone: () -> Unit,
    onFocusItem: (MetaPreview) -> Unit,
    onOpen: (MetaPreview) -> Unit,
    onLongPress: (MetaPreview) -> Unit,
) {
    val memory = rememberFocusMemory()
    val width = cardWidth(size, landscape)
    Column {
        RowHeader(title)
        val extra = width + 24.dp
        PivotScrolling(RowInset + extra) {
            LazyRow(
                modifier = Modifier
                    .extendLeft(extra)
                    .fadeLeftOf(extra + RowInset)
                    .restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(if (size == 0) 10.dp else 12.dp),
                contentPadding = PaddingValues(start = RowInset + extra, end = RowInset),
            ) {
                itemsIndexed(items, key = { _, m -> m.id }) { index, meta ->
                    val requester = remember { FocusRequester() }
                    if (pendingId == meta.id || (pendingId == "" && index == 0)) {
                        LaunchedEffect(Unit) {
                            requester.tryRequest()
                            onPendingDone()
                        }
                    }
                    Box {
                        PosterCard(
                            meta = meta,
                            modifier = Modifier
                                .rememberFocus(memory, meta.id)
                                .focusRequesterSafe(requester),
                            onFocus = { onFocusItem(meta) },
                            onClick = { onOpen(meta) },
                            onLongClick = { onLongPress(meta) },
                            width = width,
                            landscape = landscape,
                            showTitle = false,
                        )
                        // Minimal: a small line of text over a soft shade at the bottom of the card.
                        badges[meta.id]?.let { b ->
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000))),
                            )
                            Text(
                                b,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
