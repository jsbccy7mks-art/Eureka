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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.sync.withPermit

private data class HomeFocus(val rowKey: String, val itemKey: String)


/** A long press on a card: options for its row (and for that title, in Continue watching). */
/** Set when Zapp has just opened (or the profile changed): Home then starts on Continue watching. */
/** Home has loaded enough to show (the opening animation ends then). */
object HomeReady {
    var ready = false
}

object StartFocus {
    var fresh = true
}

/** Little dots on the sidebar: new episodes of series in your library. */
object SideBadges {
    var newEpisodes by androidx.compose.runtime.mutableIntStateOf(0)
}

/** True while something full screen is on top of the page (Rate films, What's new…), so the page underneath never takes focus. */
object OverlayFocus {
    var open by androidx.compose.runtime.mutableStateOf(false)
}

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
    onOpenCollection: (Long) -> Unit = {},
    onPlayChannel: (com.zapp.tv.data.Channel) -> Unit = {},
    onRateAFew: () -> Unit = {},
) {
    val addons by mvm.addons.collectAsState()
    val continueItems by mvm.continueWatching.collectAsState()
    // Kids profiles: no add-on catalogues on Home, just family titles from TMDB (their sources still
    // come from the same add-ons as everyone's).
    val kids = mvm.isKids
    val catalogs by produceState(emptyList<CatalogRef>(), addons, kids) { value = if (kids) emptyList() else mvm.catalogs(null) }

    val layout = remember(catalogs, mvm.settings.homeRowsJson, kids) { if (kids) mvm.kidsLayout() else mvm.homeLayout(catalogs) }
    val kidsCert = if (kids) mvm.kidsCert else ""
    // The kids rows, each filled in as it arrives.
    val kidsRows by produceState(emptyMap<String, MediaViewModel.KidsRow>(), kids, kidsCert) {
        value = emptyMap()
        if (!kids || !mvm.tmdb.enabled) return@produceState
        // Top rows first: a few at a time, in order, so the first ones are ready soonest.
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            KIDS_ROWS.forEach { (id, _) ->
                launch {
                    for (wait in listOf(0L, 20_000L, 60_000L)) {
                        delay(wait)
                        val got = gate.withPermit { runCatching { mvm.kidsRow(id) }.getOrNull() }
                        if (got != null && got.items.isNotEmpty()) value = value + (id to got)
                        if ((got?.items?.size ?: 0) >= 3) break
                    }
                }
            }
        }
    }
    // See all, for a kids row: its id and name.
    var seeAll by remember { mutableStateOf<Pair<String, String>?>(null) }
    var gridTick by remember { mutableIntStateOf(0) }
    // Rows further down are got ready now, so they're already there when you scroll to them.
    LaunchedEffect(layout) { mvm.warmRows(catalogs.filter { c -> layout.any { it.id == c.key && it.on } }) }
    // Smart rows: what to suggest from what you've watched and saved.
    val libraryItems by mvm.library.collectAsState()
    val seed = continueItems.firstOrNull()?.asPreview() ?: libraryItems.firstOrNull()?.let { MetaPreview(it.metaId, it.type, it.name, it.poster, "poster", null, null, null, null, null, emptyList()) }
    // Smart rows that come back short (a bad moment online) are tried again a few times.
    val because by retrying<Pair<String, List<MetaPreview>>?>(null, seed?.id, enough = { it != null }) { mvm.becauseYouWatched(seed) }
    // Rows from your ratings.
    val forYou by retrying(emptyList<MetaPreview>(), mvm.tasteVersion, enough = { it.isNotEmpty() || mvm.ratings.all().isEmpty() }) { mvm.forYou() }
    val loved by retrying<Pair<String, List<MetaPreview>>?>(null, mvm.tasteVersion, enough = { it != null || mvm.ratings.all().none { r -> r.rating == com.zapp.tv.data.Rating.Love } }) { mvm.becauseYouLoved() }
    val gems by retrying(emptyList<MetaPreview>(), mvm.tasteVersion, enough = { it.size >= 3 }) { mvm.hiddenGems() }
    // The time of year's row (Christmas from mid-November), a fresh set each day.
    val season = remember { mvm.currentSeason() }
    val seasonPicks by retrying(emptyList<MetaPreview>(), mvm.tasteVersion, season?.key, enough = { it.size >= 3 || season == null }) { mvm.seasonalPicks() }
    // "For you" starts once there are a few ratings to go on; until then, the nudge to rate.
    val ratedCount = mvm.ratings.all().size
    val noRatings = ratedCount < 5
    val newEpisodes by retrying(emptyList<Pair<MetaPreview, String>>(), libraryItems.map { it.metaId }, enough = { true }) { mvm.newEpisodes(libraryItems) }
    LaunchedEffect(newEpisodes.size) { SideBadges.newEpisodes = newEpisodes.size }
    val libraryPreviews = libraryItems.map { MetaPreview(it.metaId, it.type, it.name, it.poster, "poster", null, null, null, null, null, emptyList()) }

    // Your teams: what's on now or in the next 24 hours.
    LaunchedEffect(Unit) { vm.refreshSports() }
    val sportsSoon = vm.fixtures.filter { val n = System.currentTimeMillis(); it.liveAt(n) || (it.start > n && it.start < n + 24 * 3_600_000L) }.take(10)
    val sportsScope = rememberCoroutineScope()
    val rows = layout.filter { r ->
        r.on && when (r.id) {
            CONTINUE_ROW -> continueItems.isNotEmpty()
            BECAUSE_ROW -> because != null
            FOR_YOU_ROW -> (forYou.isNotEmpty() && !noRatings) || (noRatings && mvm.tmdb.enabled)
            LOVED_ROW -> loved != null && (!kids || loved?.second.orEmpty().count { mvm.kidsOk(it) } >= 3)
            GEMS_ROW -> gems.size >= 3
            SEASON_ROW -> season != null && seasonPicks.size >= 3
            NEW_EPISODES_ROW -> newEpisodes.isNotEmpty()
            LIBRARY_ROW -> libraryPreviews.isNotEmpty()
            COLLECTIONS_ROW -> mvm.collections.isNotEmpty()
            SPORTS_ROW -> !mvm.isKids && sportsSoon.isNotEmpty()
            else -> when {
                r.id.startsWith("kids:") -> (kidsRows[r.id]?.items?.size ?: 0) >= 3
                r.id.startsWith("collections:") -> mvm.collectionShelf(r.id.removePrefix("collections:")) != null
                else -> catalogs.any { it.key == r.id }
            }
        }
    }

    var focused by remember { mutableStateOf<HeroItem?>(null) }
    // The opening animation (and the profile switching screen) waits for this: the first few rows
    // are filled and something on Home has focus.
    val settled = rows.size >= minOf(3, layout.count { it.on })
    LaunchedEffect(focused != null, settled) {
        if (focused != null && settled) {
            delay(500)
            HomeReady.ready = true
        }
    }
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
    LaunchedEffect(focusTick, firstRowId, OverlayFocus.open) {
        val tickChanged = focusTick != handledTick
        handledTick = focusTick
        // A See all grid is open: it looks after its own focus.
        if (seeAll != null) {
            if (tickChanged) gridTick++
            return@LaunchedEffect
        }
        val first = rows.firstOrNull()
        val start = when {
            first == null -> null
            first.id == CONTINUE_ROW -> HomeFocus(first.id, continueItems.firstOrNull()?.videoId.orEmpty())
            else -> HomeFocus(first.id, "")
        }
        val target = when {
            StartFocus.fresh && start != null -> start.also { StartFocus.fresh = false }
            tickChanged && lastFocus != null && moves > 1 -> lastFocus
            moves <= 1 -> start
            else -> null
        }
        if (OverlayFocus.open) return@LaunchedEffect
        if (target != null) pending = target else if (rows.isEmpty()) emptyFocus.tryRequest()
    }

    // Back from further down first returns to the top row; Back again goes to the sidebar.
    var topTick by remember { mutableIntStateOf(0) }
    val atTop = lastFocus == null || lastFocus?.rowKey == rows.firstOrNull()?.id
    androidx.activity.compose.BackHandler(enabled = !atTop && rows.isNotEmpty()) {
        val first = rows.firstOrNull() ?: return@BackHandler
        topTick++
        pending = if (first.id == CONTINUE_ROW) HomeFocus(first.id, continueItems.firstOrNull()?.videoId.orEmpty()) else HomeFocus(first.id, "")
    }

    ImmersiveLayout(
        mvm = mvm,
        focused = focused,
        header = when {
            rows.isNotEmpty() -> null
            kids -> ({ KidsEmptyHero(mvm.tmdb.enabled) })
            else -> ({ EmptyHero(emptyFocus, onOpenAddons) })
        },
        scrollToTop = if (moves <= 1) firstRowId else null,
        topTick = topTick,
        topRight = { ClockAndAvatar(mvm) },
    ) {
        items(rows, key = { it.id }) { row ->
            if (row.id == CONTINUE_ROW) {
                ContinueRow(
                    title = row.name ?: "Continue watching",
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
            } else if (row.id == SPORTS_ROW) {
                Column {
                    RowHeader(row.name ?: "Your teams", "live and the next 24 hours")
                    val now = System.currentTimeMillis()
                    val tf = rememberTimeFormat()
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(start = RowInset, end = RowInset),
                    ) {
                        items(sportsSoon, key = { it.id }) { e ->
                            Box(Modifier.onFocusChanged { if (it.hasFocus) { lastFocus = HomeFocus(row.id, e.id); moves++ } }) {
                                MatchCard(e, now, tf, live = e.liveAt(now)) {
                                    sportsScope.launch {
                                        val hits = vm.channelsFor(e)
                                        val h = hits.firstOrNull()
                                        when {
                                            h == null -> vm.showToast("Couldn't find ${e.title} in your TV guide")
                                            h.start > now + 15 * 60_000L -> vm.showToast("${e.title} starts at ${tf.format(h.start)} on ${h.channelName}")
                                            else -> vm.channelById(h.channelId)?.let(onPlayChannel)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (row.id == COLLECTIONS_ROW || row.id.startsWith("collections:")) {
                val shelf = mvm.collectionShelf(if (row.id == COLLECTIONS_ROW) "yours" else row.id.removePrefix("collections:"))
                val shelfItems = shelf?.items.orEmpty()
                val shelfTitle = row.name ?: shelf?.title ?: "Your collections"
                Column {
                    RowHeader(shelfTitle)
                    val w = cardWidth(row.size, true)
                    val extra = w + 24.dp
                    RowScrolling(RowInset + extra) {
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.extendLeft(extra).fadeLeftOf(extra + RowInset),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = RowInset + extra, end = RowInset),
                        ) {
                            items(shelfItems, key = { it.id }) { c ->
                                // Hold OK: the row's own settings, like every other row.
                                CollectionCard(c, w, onLongClick = { options = RowOptions(row.id) }, onFocus = {
                                    mvm.prefetchTheme(c.id, c.name)
                                    val line = c.note ?: if (c.yours > 0) "${c.yours} of yours in this collection" else null
                                    focused = HeroItem(MetaPreview("collection:${c.id}", "collection", c.name, c.poster, "poster", c.backdrop, null, line, null, null, emptyList()), shelfTitle)
                                    lastFocus = HomeFocus(row.id, c.id.toString())
                                    moves++
                                }) { onOpenCollection(c.id) }
                            }
                        }
                    }
                }
            } else if (row.id == FOR_YOU_ROW && (forYou.isEmpty() || noRatings)) {
                // No ratings yet: a nudge to rate a few, so "For you" can start.
                Column {
                    RowHeader(row.name ?: "For you")
                    Box(Modifier.padding(start = RowInset)) {
                        FocusSurface(
                            onClick = onRateAFew,
                            modifier = Modifier.width(cardWidth(1, true) * 1.6f).aspectRatio(WideAspect * 1.6f),
                            onFocusChange = { if (it) { lastFocus = HomeFocus(row.id, ""); moves++ } },
                            shape = RoundedCornerShape(12.dp),
                            focusScale = 1f,
                            restColor = ZappColors.Glass,
                            focusColor = ZappColors.Glass,
                            ring = true,
                            ringCorner = 12.dp,
                        ) {
                            Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                RatingGlyph(com.zapp.tv.data.Rating.Love, filled = true, color = Color(0xFFFF5F8F), size = 34.dp)
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(if (ratedCount == 0) "Rate a few films" else "Rate a few more", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (ratedCount == 0) "Like, love or dislike a few things you've watched and this row fills with picks for you."
                                        else "$ratedCount of 5 rated. A few more and this row fills with picks for you.",
                                        color = ZappColors.Dim, fontSize = TextSize.Small,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (row.id == SEASON_ROW && season != null) {
                val look = seasonLook(season.key)
                val title = (if (mvm.settings.seasonalTouches) look.mark + "  " else "") + (row.name ?: season.title)
                SeasonalRow(season.key, mvm.settings.seasonalTouches) {
                    MetaRow(
                        title = title,
                        items = seasonPicks,
                        badges = emptyMap(),
                        size = row.size,
                        landscape = row.landscape,
                        pendingId = pending?.takeIf { it.rowKey == row.id }?.itemKey,
                        onPendingDone = { pending = null },
                        onFocusItem = { meta ->
                            focused = HeroItem(meta, season.title)
                            lastFocus = HomeFocus(row.id, meta.id)
                            moves++
                        },
                        onOpen = onOpenTitle,
                        onLongPress = { m -> options = RowOptions(row.id, meta = m) },
                    )
                }
            } else if (row.id == FOR_YOU_ROW || row.id == LOVED_ROW || row.id == GEMS_ROW) {
                val title = row.name ?: when (row.id) {
                    FOR_YOU_ROW -> "For you"
                    LOVED_ROW -> "Because you loved ${loved?.first.orEmpty()}"
                    else -> "Hidden gems for you"
                }
                val items = when (row.id) {
                    FOR_YOU_ROW -> forYou
                    LOVED_ROW -> loved?.second.orEmpty().let { l -> if (kids) l.filter { mvm.kidsOk(it) } else l }
                    else -> gems
                }
                MetaRow(
                    title = title,
                    items = items,
                    badges = emptyMap(),
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
            } else if (row.id == BECAUSE_ROW || row.id == NEW_EPISODES_ROW || row.id == LIBRARY_ROW) {
                val title = row.name ?: when (row.id) {
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
                    badges = emptyMap(),
                    newLabels = if (row.id == NEW_EPISODES_ROW) newEpisodes.associate { it.first.id to "New · ${it.second}" } else null,
                    size = row.size,
                    // New episodes is always wide, like Continue watching.
                    landscape = row.landscape || row.id == NEW_EPISODES_ROW,
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
            } else if (row.id.startsWith("kids:")) {
                val kidsTitle = kidsRows[row.id]?.title ?: row.label
                MetaRow(
                    title = kidsTitle,
                    items = kidsRows[row.id]?.items.orEmpty(),
                    onSeeAll = if (row.id == "kids:because") null else ({ seeAll = row.id to kidsTitle }),
                    badges = emptyMap(),
                    size = 1,
                    landscape = false,
                    pendingId = pending?.takeIf { it.rowKey == row.id }?.itemKey,
                    onPendingDone = { pending = null },
                    onFocusItem = { meta ->
                        focused = HeroItem(meta, row.label)
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
                    title = row.label,
                    onFocusItem = { meta ->
                        focused = HeroItem(meta, row.label)
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

    // See all, for a kids row: everything in it, in a grid that keeps loading as you go down.
    seeAll?.let { (id, title) ->
        KidsGrid(mvm, id, title, gridTick, onOpen = onOpenTitle) {
            seeAll = null
            pending = HomeFocus(id, "")
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
                // Kids profiles' rows are fixed: just the title's own options.
                row = row.takeIf { !kids },
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

/** A kids profile's Home before its rows arrive (or without a TMDB key to find them). */
@Composable
private fun KidsEmptyHero(tmdb: Boolean) {
    Column(Modifier.padding(start = RowInset, top = 44.dp).widthIn(max = 460.dp)) {
        ZappLogo(26.dp)
        Spacer(Modifier.height(24.dp))
        Text(if (tmdb) "Finding films and series for you…" else "Nothing here yet", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        if (!tmdb) {
            Spacer(Modifier.height(8.dp))
            Text(
                "A kids Home is filled with family films and series from TMDB. Ask a grown-up to add a TMDB key (Settings, Accounts, on the main profile).",
                color = ZappColors.Dim,
                fontSize = TextSize.Body,
                lineHeight = 19.sp,
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
        Text("Nothing on Home yet", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Install an add-on with catalogues and they'll show up here. Films and series you start appear in Continue watching.",
            color = ZappColors.Dim,
            fontSize = TextSize.Body,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(20.dp))
        ZButton("Add-ons", onOpenAddons, style = ButtonStyle.Primary, focusRequester = focus)
    }
}

@Composable
private fun ContinueRow(
    title: String,
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowStart = rememberRowStart()
    Column {
        RowHeader(title)
        val extra = cardWidth(size, true) + 24.dp
        RowScrolling(RowInset + extra) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .extendLeft(extra)
                    .fadeLeftOf(extra + RowInset)
                    .backToStart(rowStart, listState)
                    .restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = RowInset + extra, end = RowInset),
            ) {
                itemsIndexed(items, key = { _, it -> it.videoId }) { index, p ->
                    val requester = if (index == 0) rowStart.first else remember { FocusRequester() }
                    if (pendingId == p.videoId || (pendingId == "" && index == 0)) {
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
                        onFocus = { rowStart.index = index; onFocusItem(p) },
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
        Text(row.channel.name, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            row.nowTitle ?: "No guide information",
            color = if (row.nowTitle != null) ZappColors.Dim else ZappColors.Faint,
            fontSize = TextSize.Small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row of titles Zapp put together itself (suggestions, new episodes, your library, Discover). */
@Composable
internal fun MetaRow(
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
    /** New episodes: cards like Continue watching, each with its "New · S2 E5". */
    newLabels: Map<String, String>? = null,
    /** A "See all" card at the end of the row. */
    onSeeAll: (() -> Unit)? = null,
) {
    val memory = rememberFocusMemory()
    val width = cardWidth(size, landscape)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowStart = rememberRowStart()
    Column {
        RowHeader(title)
        val extra = width + 24.dp
        RowScrolling(RowInset + extra) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .extendLeft(extra)
                    .fadeLeftOf(extra + RowInset)
                    .backToStart(rowStart, listState)
                    .restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(if (size == 0) 10.dp else 12.dp),
                contentPadding = PaddingValues(start = RowInset + extra, end = RowInset),
            ) {
                itemsIndexed(items, key = { _, m -> m.id }) { index, meta ->
                    val requester = if (index == 0) rowStart.first else remember { FocusRequester() }
                    if (pendingId == meta.id || (pendingId == "" && index == 0)) {
                        LaunchedEffect(Unit) {
                            requester.tryRequest()
                            onPendingDone()
                        }
                    }
                    if (newLabels != null) {
                        NewEpisodeCard(
                            meta = meta,
                            label = newLabels[meta.id].orEmpty(),
                            modifier = Modifier
                                .rememberFocus(memory, meta.id)
                                .focusRequesterSafe(requester),
                            onFocus = { rowStart.index = index; onFocusItem(meta) },
                            onClick = { onOpen(meta) },
                            onLongClick = { onLongPress(meta) },
                            width = width,
                        )
                    } else Box {
                        PosterCard(
                            meta = meta,
                            modifier = Modifier
                                .rememberFocus(memory, meta.id)
                                .focusRequesterSafe(requester),
                            onFocus = { rowStart.index = index; onFocusItem(meta) },
                            onClick = { onOpen(meta) },
                            onLongClick = { onLongPress(meta) },
                            width = width,
                            landscape = landscape,
                            showTitle = false,
                        )
                        // Minimal: a small line of text over a soft shade at the bottom of the card.
                        badges[meta.id]?.let { b ->
                            // Bottom right, like the time left on Continue watching (the logo is bottom left).
                            Text(
                                b,
                                fontSize = TextSize.Small,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 1f), 4f)),
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
                if (onSeeAll != null && items.size >= 3) {
                    item(key = "seeAll") { SeeAllCard(width, landscape, onSeeAll) }
                }
            }
        }
    }
}

/**
 * Loads a smart row, and if it comes back short (or fails), tries again after 15 seconds, then 45,
 * then 2 minutes, so a moment of trouble online doesn't leave the row missing until a restart.
 */
@Composable
private fun <T> retrying(initial: T, vararg keys: Any?, enough: (T) -> Boolean, load: suspend () -> T): androidx.compose.runtime.State<T> =
    produceState(initial, *keys) {
        for (wait in listOf(0L, 15_000L, 45_000L, 120_000L)) {
            delay(wait)
            val r = runCatching { load() }
            if (r.isFailure) continue
            val v = r.getOrThrow()
            value = v
            if (enough(v)) break
        }
    }

/** The last card of a row: everything in it. */
@Composable
private fun SeeAllCard(width: androidx.compose.ui.unit.Dp, landscape: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(width).aspectRatio(if (landscape) WideAspect else 2f / 3f),
        shape = RoundedCornerShape(10.dp),
        focusScale = 1f,
        restColor = ZappColors.Glass,
        focusColor = ZappColors.Glass,
        ring = true,
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("→", fontSize = 26.sp, color = ZappColors.Text)
            Spacer(Modifier.height(6.dp))
            Text("See all", fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, color = ZappColors.Text)
        }
    }
}

/**
 * A kids row in full: a grid of everything in it (up to the profile's age limit), loading more as
 * you go down. Back returns to Home; back from a title returns to the card you opened.
 */
@Composable
private fun KidsGrid(mvm: MediaViewModel, id: String, title: String, focusTick: Int, onOpen: (MetaPreview) -> Unit, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val items = remember(id) { androidx.compose.runtime.mutableStateListOf<MetaPreview>() }
    var page by remember(id) { mutableIntStateOf(1) }
    var loadedPage by remember(id) { mutableIntStateOf(0) }
    var finished by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id, page) {
        val got = runCatching { mvm.kidsRow(id, page).items }.getOrDefault(emptyList())
        val more = got.filter { m -> items.none { it.id == m.id } }
        if (got.isEmpty() || page >= 15) finished = true
        items.addAll(more)
        loadedPage = page
    }
    val first = remember { FocusRequester() }
    val cardFocus = remember(id) { HashMap<String, FocusRequester>() }
    var lastId by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty() && lastId == null) {
            delay(80)
            first.tryRequest()
        }
    }
    var handled by remember { mutableIntStateOf(focusTick) }
    LaunchedEffect(focusTick) {
        if (focusTick == handled) return@LaunchedEffect
        handled = focusTick
        delay(120)
        (lastId?.let { cardFocus[it] } ?: first).tryRequest()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Bg)
            .trapFocus(),
    ) {
        AmbientGlow(0.5f)
        Column(Modifier.fillMaxSize().padding(start = RowInset, top = 30.dp)) {
            Text(title, fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(14.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    if (finished) Text("Nothing here right now.", color = ZappColors.Dim, fontSize = TextSize.Body) else ZappLoader(36.dp)
                }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(PosterWidth + 10.dp),
                contentPadding = PaddingValues(end = RowInset, bottom = 40.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.foundation.lazy.grid.itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
                    // Near the end: the next page.
                    if (i >= items.size - 8 && !finished && page == loadedPage) {
                        LaunchedEffect(items.size) { if (page == loadedPage) page = loadedPage + 1 }
                    }
                    PosterCard(
                        meta = m,
                        modifier = Modifier
                            .focusRequester(cardFocus.getOrPut(m.id) { FocusRequester() })
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        onFocus = { lastId = m.id },
                        onClick = { onOpen(m) },
                    )
                }
            }
        }
    }
}
