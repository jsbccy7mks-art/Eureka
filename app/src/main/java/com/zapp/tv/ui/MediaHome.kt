@file:OptIn(ExperimentalComposeUiApi::class)

package com.zapp.tv.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.BoxScope
import kotlinx.coroutines.delay

/** What the big header at the top of Home, Movies and Series is showing. */
data class HeroItem(val meta: MetaPreview, val rowTitle: String, val progress: WatchProgress? = null)

/**
 * The shared look of Home, Movies and Series: artwork of whatever you're resting on across the top,
 * its details over it, and rows of cards below that glide into place as you move.
 * [header] replaces the details (for empty states); [placeholder] shows before anything is focused.
 */
@Composable
fun ImmersiveLayout(
    mvm: MediaViewModel,
    focused: HeroItem?,
    header: (@Composable BoxScope.() -> Unit)? = null,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
    /** Changing this scrolls back to the first row (e.g. when Continue watching appears). */
    scrollToTop: Any? = null,
    /** Bumping this scrolls back to the first row (Back from further down). */
    topTick: Int = 0,
    topRight: (@Composable () -> Unit)? = null,
    rows: LazyListScope.() -> Unit,
) {
    // Scores for the title you're resting on (MDBList, when connected).
    val heroRatings by produceState<Pair<String, List<com.zapp.tv.data.RatingBadge>>?>(null, focused?.meta?.id) {
        val m = focused?.meta ?: return@produceState
        if (!mvm.mdblist.enabled) return@produceState
        delay(400)
        value = m.id to mvm.ratings(m.id, m.type)
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(scrollToTop) { if (scrollToTop != null) listState.scrollToItem(0) }
    LaunchedEffect(topTick) { if (topTick > 0) listState.animateScrollToItem(0) }
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    // The header follows focus after a short pause, so scrolling quickly along a row stays smooth.
    LaunchedEffect(focused) {
        if (hero != null) delay(220)
        hero = focused
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
        val backdropUrl = hero?.meta?.let { it.background ?: detail?.preview?.background ?: it.poster }
        Backdrop(
            backdropUrl,
            trailer = detail?.takeIf { it.preview.id == hero?.meta?.id }?.trailerYoutube,
            trailerKey = hero?.meta?.id,
        )
        // The title's colour tints the glow behind the whole screen, sidebar included.
        val art = rememberArtColor(hero?.meta?.poster ?: backdropUrl)
        LaunchedEffect(art) { ArtTint.color = art }
        DisposableEffect(Unit) { onDispose { ArtTint.color = null } }

        // The rows run the full height of the screen but are hidden above the header line. The row
        // above the one you're on is therefore still laid out (just invisible), so Up always finds it
        // instead of jumping to the sidebar.
        PivotScrolling(heroHeight + 30.dp) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .fadeAbove(heroHeight)
                    // Down never leaves the rows; Up from the top row goes to Search and Settings.
                    .trapVertical(up = if (topRight != null) NavShortcuts.requester else null),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = heroHeight + 4.dp, bottom = screenHeight * 0.5f),
                content = rows,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(heroHeight),
        ) {
            when {
                header != null -> header()
                hero == null && placeholder != null -> placeholder()
                else -> HeroInfo(hero, detail, heroRatings)
            }
        }
        if (topRight != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 40.dp)) { topRight() }
        }
    }
}

/**
 * Colour stops fading from solid page colour at [start] to clear at [end] (fractions of the box),
 * following a smooth curve rather than a straight line.
 */
fun easedFade(start: Float, end: Float): Array<Pair<Float, Color>> {
    val steps = 12
    val out = ArrayList<Pair<Float, Color>>()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val pos = start + (end - start) * t
        val alpha = 1f - (t * t * (3f - 2f * t)) // smoothstep
        out.add(pos to ZappColors.Bg.copy(alpha = alpha))
    }
    if (start > end) out.reverse()
    return out.toTypedArray()
}

/** Artwork of the title you're on, filling the top right and fading into the page. */
@Composable
private fun Backdrop(url: String?, trailer: String? = null, trailerKey: Any? = null) {
    Box(Modifier.fillMaxSize()) {
        // The picture itself fades out (rather than being covered with page colour), so whatever is
        // behind it, the glow included, shows through evenly right up to the sidebar.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                // A whole 16:9 picture sitting higher up, so the rows cover only its faded edge
                // and the picture (and trailer) isn't cut off behind the cards.
                .fillMaxHeight(0.66f)
                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                .clipToBounds()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(*easedMask(start = 0f, end = 0.5f)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(*easedMask(start = 1f, end = 0.62f)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) {
            Crossfade(
                targetState = url,
                animationSpec = tween(650),
                label = "backdrop",
                modifier = Modifier.fillMaxSize(),
            ) { u ->
                Box(Modifier.fillMaxSize()) {
                    if (u != null) {
                        AsyncImage(
                            model = u,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize().drift(),
                        )
                    }
                }
            }
            DelayedTrailer(trailer, trailerKey, Modifier.fillMaxSize())
        }
    }
}

/** Mask stops: clear at [start], fully shown at [end], on the same smooth curve as [easedFade]. */
fun easedMask(start: Float, end: Float): Array<Pair<Float, Color>> =
    easedFade(start, end).map { (pos, c) -> pos to Color.Black.copy(alpha = 1f - c.alpha) }.toTypedArray()

@Composable
private fun HeroInfo(item: HeroItem?, detail: com.zapp.tv.addons.MetaDetail?, ratings: Pair<String, List<com.zapp.tv.data.RatingBadge>>? = null) {
    // Fixed slots (label, title, facts, two lines of text, one line of cast), so moving between
    // titles only changes what's shown, never where it sits.
    // A fixed-size block sitting low in the header, just above the rows: same place for every title.
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 64.dp, bottom = 20.dp)
            .width(380.dp),
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
            contentKey = { it?.meta?.id + "|" + it?.progress?.videoId },
            label = "hero",
        ) { h ->
            Column(Modifier.fillMaxWidth()) {
                if (h == null) return@Column
                val d = detail?.takeIf { it.preview.id == h.meta.id }
                val m = h.meta
                val logo = m.logo ?: d?.preview?.logo
                val description = m.description ?: d?.preview?.description
                Text(
                    (if (m.type == "series") "SERIES" else if (m.type == "movie") "FILM" else m.type.uppercase()) + "  ·  " + h.rowTitle.uppercase(),
                    color = ZappColors.Faint,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    if (logo != null) {
                        AsyncImage(
                            model = logo,
                            contentDescription = m.name,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier.height(56.dp).widthIn(max = 300.dp),
                        )
                    } else {
                        // One line, smaller for long titles, so it always sits neatly in its slot.
                        MovieTitle(
                            m.name,
                            when {
                                m.name.length > 30 -> 20.sp
                                m.name.length > 20 -> 24.sp
                                else -> 30.sp
                            },
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val p = h.progress
                if (p != null) {
                    Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(
                            if (p.duration > 1) (p.position.toFloat() / p.duration).coerceIn(0f, 1f)
                            else ((p.percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                            Modifier.width(110.dp),
                            track = Color(0x33FFFFFF),
                            fill = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(listOfNotNull(p.episodeLabel, minutesLeft(p)).joinToString("  ·  "), color = ZappColors.Dim, fontSize = 11.5.sp, maxLines = 1)
                    }
                } else {
                    Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        FactsLine(
                            m.releaseInfo ?: d?.preview?.releaseInfo,
                            m.imdbRating ?: d?.preview?.imdbRating ?: ratings?.takeIf { it.first == m.id }?.second?.firstOrNull { it.source == "IMDb" }?.value,
                            d?.runtime,
                            m.genres.ifEmpty { d?.preview?.genres ?: emptyList() },
                            size = 11.5.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Home shows IMDb only; every other score is on the details page.
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    description.orEmpty(),
                    color = ZappColors.Dim,
                    fontSize = 12.sp,
                    lineHeight = 17.5.sp,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}

/**
 * Hold OK on a title: library, watched, (Continue watching) remove, and the row's own settings,
 * which open in place. [row] null: no row settings (Library, Search and so on).
 */
@Composable
fun RowOptionsDialog(
    mvm: MediaViewModel,
    meta: MetaPreview?,
    row: HomeRowSetting?,
    progress: WatchProgress?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: ((HomeRowSetting) -> HomeRowSetting) -> Unit,
    onMove: (Int) -> Unit,
    onRemoveTitle: (WatchProgress) -> Unit,
    onClose: () -> Unit,
    canHide: Boolean = true,
) {
    val first = remember { FocusRequester() }
    var rowOpen by remember { mutableStateOf(meta == null) }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    val inLibrary by (meta?.let { mvm.inLibrary(it.id) } ?: kotlinx.coroutines.flow.flowOf(false)).collectAsState(false)
    val watched = meta?.id in WatchedMarks.ids
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ZappColors.Panel)
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp)) {
                Text(meta?.name ?: row?.title.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(progress?.episodeLabel?.substringBefore(","), row?.title?.takeIf { meta != null }).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(sub, color = ZappColors.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (meta != null) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rate", color = ZappColors.Dim, fontSize = 13.sp, modifier = Modifier.width(54.dp))
                    RatingButtons(mvm.ratingOf(meta.id), size = 38.dp) { r -> mvm.rate(meta, r) }
                }
                Spacer(Modifier.height(4.dp))
            }
            var firstUsed = false
            fun firstOnce(): FocusRequester? = if (!firstUsed) { firstUsed = true; first } else null
            if (meta != null) {
                MenuItem(if (inLibrary) "✓" else "+", if (inLibrary) "Remove from library" else "Add to library", firstOnce()) {
                    mvm.toggleLibrary(meta, inLibrary)
                    onClose()
                }
                val episode = progress?.takeIf { it.type == "series" }
                if (episode != null) {
                    MenuItem("◉", "Mark ${episode.episodeLabel?.substringBefore(",") ?: "episode"} as watched", firstOnce()) {
                        mvm.markContinueWatched(episode)
                        onClose()
                    }
                } else {
                    MenuItem(if (watched) "○" else "◉", if (watched) "Mark as unwatched" else "Mark as watched", firstOnce()) {
                        if (progress != null) mvm.markContinueWatched(progress) else mvm.setWatched(meta, !watched)
                        onClose()
                    }
                }
                if (progress != null) {
                    MenuItem("✕", "Remove from Continue watching", firstOnce()) { onRemoveTitle(progress) }
                }
            }
            if (row != null) {
                MenuItem("☰", "Row settings", firstOnce(), trailing = if (rowOpen) "▴" else "▾") { rowOpen = !rowOpen }
                androidx.compose.animation.AnimatedVisibility(
                    visible = rowOpen,
                    enter = androidx.compose.animation.expandVertically() + fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
                ) {
                    Column(
                        Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OptionLine("Card size") {
                            Box(Modifier.focusGroup()) {
                                Segmented(
                                    listOf("0" to "Small", "1" to "Medium", "2" to "Large"),
                                    row.size.toString(),
                                ) { v -> onChange { it.copy(size = v.toInt()) } }
                            }
                        }
                        if (row.id != CONTINUE_ROW && row.id != NEW_EPISODES_ROW) {
                            OptionLine("Cards") {
                                Segmented(
                                    listOf("poster" to "Posters", "landscape" to "Landscape"),
                                    if (row.landscape) "landscape" else "poster",
                                ) { v -> onChange { it.copy(landscape = v == "landscape") } }
                            }
                            OptionLine("Ranked") {
                                Segmented(
                                    listOf("off" to "Off", "on" to "Top 10"),
                                    if (row.top10 == true) "on" else "off",
                                ) { v -> onChange { it.copy(top10 = v == "on") } }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canMoveUp) ZButton("Move up", { onMove(-1) })
                            if (canMoveDown) ZButton("Move down", { onMove(1) })
                            if (canHide) ZButton("Hide row", {
                                onChange { it.copy(on = false) }
                                onClose()
                            }, style = ButtonStyle.Quiet)
                        }
                        if (canHide) {
                            Text("Hidden rows can be switched back on in Settings, Home layout.", color = ZappColors.Faint, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The long-press menu for a title outside Home's rows (Library, Search, details pages). */
@Composable
fun TitleMenu(mvm: MediaViewModel, meta: MetaPreview, onClose: () -> Unit) =
    RowOptionsDialog(mvm, meta, null, null, false, false, {}, {}, {}, onClose, canHide = false)

/** One line of a long-press menu: a small symbol, the action, and optionally something on the right. */
@Composable
fun MenuItem(symbol: String, label: String, requester: FocusRequester?, trailing: String? = null, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = requester,
        shape = RoundedCornerShape(12.dp),
        focusScale = 1f,
    ) { focused ->
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, fontSize = 14.sp, color = if (focused) ZappColors.OnFocus else ZappColors.Dim, modifier = Modifier.width(28.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (focused) ZappColors.OnFocus else ZappColors.Text, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, fontSize = 12.sp, color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint) }
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

/**
 * A soft picture behind a whole screen (Live TV, the guide): top right, faded out towards the
 * left and bottom, dimmed so text stays easy to read. It also tints the app's glow.
 */
@Composable
fun SoftBackdrop(url: String?, strength: Float = 0.55f) {
    val art = rememberArtColor(url)
    LaunchedEffect(art) { ArtTint.color = art }
    DisposableEffect(Unit) { onDispose { ArtTint.color = null } }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.75f)
                .fillMaxHeight(0.85f)
                .clipToBounds()
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    alpha = strength
                }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(*easedMask(start = 0f, end = 0.7f)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(*easedMask(start = 1f, end = 0.45f)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) {
            Crossfade(targetState = url, animationSpec = tween(700), label = "softBackdrop", modifier = Modifier.fillMaxSize()) { u ->
                if (u != null) {
                    AsyncImage(
                        model = u,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().drift(),
                    )
                }
            }
        }
    }
}
