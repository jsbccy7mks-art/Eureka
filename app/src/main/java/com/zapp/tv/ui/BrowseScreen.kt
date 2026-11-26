package com.zapp.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.addons.CatalogRef
import com.zapp.tv.addons.MetaPreview

/** Movies or Series: a hero for the focused title, then a row per add-on catalogue. */
@Composable
fun BrowseScreen(
    mvm: MediaViewModel,
    type: String,
    focusTick: Int,
    onOpen: (MetaPreview) -> Unit,
    onOpenAddons: () -> Unit,
) {
    val addons by mvm.addons.collectAsState()
    val catalogs by produceState<List<CatalogRef>?>(null, addons, type) { value = mvm.catalogs(type) }
    var hero by remember(type) { mutableStateOf<HeroItem?>(null) }
    val emptyFocus = remember { FocusRequester() }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    val label = if (type == "movie") "Movies" else "Series"

    var last by remember(type) { mutableStateOf<Pair<String, String>?>(null) }
    var pending by remember(type) { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        val firstRow = catalogs?.firstOrNull()
        pending = last ?: firstRow?.let { it.key to "" }
        if (pending == null) emptyFocus.tryRequest()
    }

    val list = catalogs
    if (list != null && list.isEmpty()) {
        Column(Modifier.padding(start = 40.dp, top = 40.dp, end = 40.dp)) {
            Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (addons.isEmpty()) {
                    "Add a Stremio add-on to browse ${label.lowercase()}. Zapp doesn't include any; you choose your own."
                } else {
                    "None of your add-ons have ${label.lowercase()} catalogues. Add one that does, or switch one on."
                },
                color = ZappColors.Dim,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            ZButton("Manage add-ons", onOpenAddons, style = ButtonStyle.Primary, focusRequester = emptyFocus)
        }
        return
    }

    val layout = remember(list, mvm.settings.homeRowsJson) { mvm.homeLayout(list.orEmpty()) }
    var options by remember(type) { mutableStateOf<Pair<String, MetaPreview>?>(null) }
    var genre by remember(type) { mutableStateOf<String?>(null) }

    var topTick by remember(type) { mutableIntStateOf(0) }
    val firstKey = list?.firstOrNull()?.key
    androidx.activity.compose.BackHandler(enabled = last != null && last?.first != firstKey && firstKey != null) {
        topTick++
        pending = firstKey!! to ""
    }

    ImmersiveLayout(
        mvm = mvm,
        focused = hero,
        topRight = { ClockAndAvatar(mvm) },
        topTick = topTick,
        placeholder = {
            Column(Modifier.align(Alignment.BottomStart).padding(start = RowInset, bottom = 24.dp)) {
                Text(label, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(6.dp))
                Text(if (list == null) "Loading catalogues…" else "Every ${if (type == "movie") "film" else "series"} catalogue from your add-ons", color = ZappColors.Dim, fontSize = 13.sp)
            }
        },
    ) {
        // Genres: chips that open a row for the chosen genre right below them.
        val genreRef = list.orEmpty().firstOrNull { it.genres.isNotEmpty() }
        if (genreRef != null) {
            item(key = "genres") {
                GenreChips(genreRef.genres, genre) { g -> genre = if (g == genre) null else g }
            }
            genre?.let { g ->
                item(key = "genre:$g") {
                    CatalogRow(
                        mvm = mvm,
                        ref = genreRef.copy(genre = g),
                        title = g,
                        onFocusItem = { hero = HeroItem(it, g) },
                        onOpen = onOpen,
                        showTitles = false,
                    )
                }
            }
        }
        items(list.orEmpty(), key = { it.key }) { ref ->
            val title = if (list.orEmpty().count { it.def.name == ref.def.name } > 1) "${ref.def.name} from ${ref.addon.manifest.name}" else ref.def.name
            val row = layout.firstOrNull { it.id == ref.key }
            CatalogRow(
                mvm = mvm,
                ref = ref,
                title = title,
                onFocusItem = {
                    hero = HeroItem(it, title)
                    last = ref.key to it.id
                },
                onOpen = onOpen,
                pendingId = pending?.takeIf { it.first == ref.key }?.second,
                onPendingDone = { pending = null },
                size = row?.size ?: 1,
                landscape = row?.landscape ?: false,
                top10 = row?.top10 == true,
                showTitles = false,
                onLongPress = { m -> options = ref.key to m },
            )
        }
    }

    options?.let { (key, m) ->
        val row = layout.firstOrNull { it.id == key }
        if (row == null) {
            options = null
        } else {
            RowOptionsDialog(
                mvm = mvm,
                meta = m,
                row = row,
                progress = null,
                canMoveUp = false,
                canMoveDown = false,
                onChange = { change -> mvm.updateRow(layout, row.id, change) },
                onMove = {},
                onRemoveTitle = {},
                onClose = { options = null },
                canHide = false,
            )
        }
    }
}

/** Saved films and series: All, Films or Series, sorted how you like (both remembered). */
@Composable
fun LibraryScreen(
    mvm: MediaViewModel,
    focusTick: Int,
    onOpenCollection: (Long) -> Unit = {},
    onOpen: (MetaPreview) -> Unit,
) {
    val library by mvm.library.collectAsState()
    val lastWatched by mvm.lastWatched.collectAsState()
    var tab by remember { mutableStateOf(mvm.settings.libraryTab) }
    var sort by remember { mutableStateOf(mvm.settings.librarySort) }
    var menuFor by remember { mutableStateOf<MetaPreview?>(null) }
    menuFor?.let { m -> TitleMenu(mvm, m) { menuFor = null } }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    val first = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        first.tryRequest()
    }

    val shown = remember(library, tab, sort, lastWatched, mvm.libraryYears.toMap()) {
        val filtered = library.filter { tab == "all" || it.type == tab }
        when (sort) {
            "title" -> filtered.sortedBy { it.name.lowercase().removePrefix("the ") }
            "year" -> filtered.sortedByDescending { mvm.libraryYears[it.metaId] ?: 0 }
            "watched" -> filtered.sortedByDescending { lastWatched[it.metaId] ?: 0L }
            else -> filtered.sortedByDescending { it.addedAt }
        }
    }
    val films = library.count { it.type == "movie" }
    val series = library.count { it.type == "series" }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Row(Modifier.padding(start = 40.dp, end = 40.dp), verticalAlignment = Alignment.Bottom) {
            Text("Library", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                if (library.isEmpty()) "Nothing saved yet" else "$films films  ·  $series series",
                color = ZappColors.Dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(start = 40.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            val showCollections = mvm.settings.libraryCollections
            Segmented(
                listOf("all" to "All", "movie" to "Films", "series" to "Series") + if (showCollections) listOf("collections" to "Collections") else emptyList(),
                tab,
            ) {
                tab = it
                mvm.settings.libraryTab = it
            }
            if (tab != "collections") {
                Spacer(Modifier.width(18.dp))
                Text("Sort", color = ZappColors.Faint, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Segmented(listOf("added" to "Date added", "title" to "A–Z", "year" to "Year", "watched" to "Recently watched"), sort) {
                    sort = it
                    mvm.settings.librarySort = it
                }
            }
            Spacer(Modifier.weight(1f))
            // Library settings: Collections on or off.
            ZButton(if (showCollections) "Hide collections" else "Show collections", {
                mvm.settings.libraryCollections = !showCollections
                if (showCollections && tab == "collections") {
                    tab = "all"
                    mvm.settings.libraryTab = "all"
                }
            }, style = ButtonStyle.Quiet)
        }
        Spacer(Modifier.height(18.dp))
        if (tab == "collections" && mvm.settings.libraryCollections) {
            val list = mvm.collections
            if (list.isEmpty()) {
                Text(
                    if (mvm.tmdb.enabled) "Franchises of the films you save or watch appear here (Harry Potter, Bond…)." else "Collections need your TMDB key (Settings, Accounts and sync).",
                    color = ZappColors.Dim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 40.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(cardWidth(1, true) + 12.dp),
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(list, key = { it.id }) { c ->
                    CollectionCard(
                        c,
                        cardWidth(1, true),
                        modifier = if (c == list.firstOrNull()) Modifier.focusRequesterSafe(first) else Modifier,
                    ) { onOpenCollection(c.id) }
                }
            }
            return@Column
        }
        if (shown.isEmpty()) {
            Text(
                if (library.isEmpty()) "Open a film or series and choose Add to library, or hold OK on any title."
                else "No ${if (tab == "movie") "films" else "series"} saved yet.",
                color = ZappColors.Dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 40.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PosterWidth + 10.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(shown, key = { it.metaId }) { item ->
                val meta = MetaPreview(item.metaId, item.type, item.name, item.poster, "poster", null, null, null, null, null, emptyList())
                Box(contentAlignment = Alignment.TopCenter) {
                    PosterCard(
                        meta = meta,
                        modifier = if (item == shown.firstOrNull()) Modifier.focusRequesterSafe(first) else Modifier,
                        onFocus = {},
                        onClick = { onOpen(meta) },
                        onLongClick = { menuFor = meta },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreChips(genres: List<String>, selected: String?, onPick: (String) -> Unit) {
    Column {
        RowHeader("Genres")
        MinimalScrolling {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = RowInset),
            ) {
                items(genres, key = { it }) { g ->
                    val chosen = g == selected
                    FocusSurface(
                        onClick = { onPick(g) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        focusScale = 1.04f,
                        restColor = if (chosen) ZappColors.GlassHigh else ZappColors.Glass,
                    ) { focused ->
                        Text(
                            g,
                            color = when {
                                focused -> ZappColors.OnFocus
                                chosen -> ZappColors.Text
                                else -> ZappColors.Dim
                            },
                            fontSize = 12.5.sp,
                            fontWeight = if (chosen || focused) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
