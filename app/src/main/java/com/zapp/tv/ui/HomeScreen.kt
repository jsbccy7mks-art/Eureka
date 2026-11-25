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

/** What the big header at the top of Home is showing. */
private data class HeroItem(val meta: MetaPreview, val rowTitle: String, val progress: WatchProgress? = null)

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
    var hero by remember { mutableStateOf<HeroItem?>(null) }
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

    // The header follows focus after a short pause, so scrolling quickly along a row stays smooth.
    LaunchedEffect(focused) {
        if (hero == null) {
            hero = focused
        } else {
            delay(220)
            hero = focused
        }
    }
    val detail by produceState<com.zapp.tv.addons.MetaDetail?>(null, hero?.meta?.id) {
        value = null
        val h = hero ?: return@produceState
        delay(450)
        value = runCatching { mvm.meta(h.meta.type, h.meta.id) }.getOrNull()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val heroHeight = screenHeight * 0.47f
        Backdrop(hero?.meta?.let { it.background ?: detail?.preview?.background ?: it.poster })

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                if (rows.isEmpty()) {
                    EmptyHero(emptyFocus, onOpenAddons)
                } else {
                    HeroInfo(hero, detail)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
            PivotScrolling(26.dp) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = screenHeight * 0.5f),
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
            }
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

/** Artwork of the title you're on, filling the top right and fading into the page. */
@Composable
private fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(
            targetState = url,
            animationSpec = tween(650),
            label = "backdrop",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.8f),
        ) { u ->
            Box(Modifier.fillMaxSize()) {
                if (u != null) {
                    AsyncImage(
                        model = u,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Fade the picture into the page on the left and at the bottom.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.8f)
                .background(
                    Brush.horizontalGradient(
                        0f to ZappColors.Bg,
                        0.18f to ZappColors.Bg.copy(alpha = 0.82f),
                        0.5f to ZappColors.Bg.copy(alpha = 0.25f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.8f)
                .background(
                    Brush.verticalGradient(
                        0f to ZappColors.Bg.copy(alpha = 0.25f),
                        0.45f to Color.Transparent,
                        0.72f to ZappColors.Bg.copy(alpha = 0.7f),
                        1f to ZappColors.Bg,
                    ),
                ),
        )
    }
}

@Composable
private fun HeroInfo(item: HeroItem?, detail: com.zapp.tv.addons.MetaDetail?) {
    AnimatedContent(
        targetState = item,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
        contentKey = { it?.meta?.id + "|" + it?.progress?.videoId },
        label = "hero",
        modifier = Modifier.fillMaxSize(),
    ) { h ->
        Box(Modifier.fillMaxSize()) {
            if (h == null) return@Box
            val d = detail?.takeIf { it.preview.id == h.meta.id }
            val m = h.meta
            val logo = m.logo ?: d?.preview?.logo
            val description = m.description ?: d?.preview?.description
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = RowInset, bottom = 18.dp)
                    .widthIn(max = 460.dp),
            ) {
                Text(
                    (if (m.type == "series") "SERIES" else if (m.type == "movie") "FILM" else m.type.uppercase()) + "  ·  " + h.rowTitle,
                    color = ZappColors.Brand2,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = m.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier
                            .heightIn(max = 78.dp)
                            .widthIn(max = 330.dp),
                    )
                } else {
                    Text(
                        m.name,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        lineHeight = 34.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                MetaFacts(
                    m.releaseInfo ?: d?.preview?.releaseInfo,
                    m.imdbRating ?: d?.preview?.imdbRating,
                    d?.runtime,
                    m.genres.ifEmpty { d?.preview?.genres ?: emptyList() },
                )
                val p = h.progress
                if (p != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(
                            if (p.duration > 1) (p.position.toFloat() / p.duration).coerceIn(0f, 1f)
                            else ((p.percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                            Modifier.width(160.dp),
                            track = Color(0x33FFFFFF),
                            fill = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            listOfNotNull(p.episodeLabel, minutesLeft(p)).joinToString("  ·  "),
                            color = ZappColors.Text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(description, color = ZappColors.Dim, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val people = d?.cast.orEmpty().take(3)
                if (people.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("With " + people.joinToString(", "), color = ZappColors.Faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
private fun RowOptionsDialog(
    row: HomeRowSetting,
    progress: WatchProgress?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: ((HomeRowSetting) -> HomeRowSetting) -> Unit,
    onMove: (Int) -> Unit,
    onRemoveTitle: (WatchProgress) -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ZappColors.Panel)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(row.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("Changes apply straight away.", color = ZappColors.Faint, fontSize = 12.sp)
            }
            OptionLine("Card size") {
                Box(Modifier.focusRequester(first).focusGroup()) {
                    Segmented(
                        listOf("0" to "Small", "1" to "Medium", "2" to "Large"),
                        row.size.toString(),
                    ) { v -> onChange { it.copy(size = v.toInt()) } }
                }
            }
            if (row.id != CONTINUE_ROW) {
                OptionLine("Cards") {
                    Segmented(
                        listOf("poster" to "Posters", "landscape" to "Landscape"),
                        if (row.landscape) "landscape" else "poster",
                    ) { v -> onChange { it.copy(landscape = v == "landscape") } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canMoveUp) ZButton("Move up", { onMove(-1) })
                if (canMoveDown) ZButton("Move down", { onMove(1) })
                ZButton("Hide row", {
                    onChange { it.copy(on = false) }
                    onClose()
                }, style = ButtonStyle.Quiet)
            }
            if (progress != null) {
                ZButton("Remove ${progress.name} from this row", { onRemoveTitle(progress) }, style = ButtonStyle.Danger)
            }
            Text(
                "Hidden rows can be switched back on in Settings, Home layout.",
                color = ZappColors.Faint,
                fontSize = 11.sp,
            )
            ZButton("Done", onClose, style = ButtonStyle.Primary)
        }
    }
}

@Composable
private fun OptionLine(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ZappColors.Dim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        content()
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
