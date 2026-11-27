package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.addons.MetaPreview
import com.zapp.tv.data.TmdbItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** What Discover shows: films, series, or both together. */
private enum class DKind(val label: String) { Films("Films"), Series("Series"), Both("Both") }

/** A genre, with TMDB's id for films and for series (null: not a genre series have). */
private data class DGenre(val name: String, val movie: Int?, val tv: Int?)

private val GENRES = listOf(
    DGenre("Action", 28, 10759), DGenre("Adventure", 12, 10759), DGenre("Animation", 16, 16), DGenre("Comedy", 35, 35),
    DGenre("Crime", 80, 80), DGenre("Documentary", 99, 99), DGenre("Drama", 18, 18), DGenre("Family", 10751, 10751),
    DGenre("Fantasy", 14, 10765), DGenre("History", 36, 10768), DGenre("Horror", 27, null), DGenre("Mystery", 9648, 9648),
    DGenre("Romance", 10749, null), DGenre("Science fiction", 878, 10765), DGenre("Thriller", 53, null),
    DGenre("War", 10752, 10768), DGenre("Western", 37, 37),
)

/** UK streaming services, by TMDB provider id. */
private val SERVICES = listOf(
    8 to "Netflix", 9 to "Prime Video", 337 to "Disney+", 350 to "Apple TV+", 531 to "Paramount+",
    39 to "NOW", 38 to "BBC iPlayer", 41 to "ITVX", 103 to "Channel 4",
)
private val ALL_SERVICES = SERVICES.joinToString("|") { it.first.toString() }

private val DECADES = listOf(2020, 2010, 2000, 1990, 1980, 1970)
private val SORTS = listOf("Popular", "Top rated", "Newest")
private val LENGTHS = listOf("Under 90 minutes", "90 minutes to 2 hours", "Over 2 hours")

private data class DFilters(
    val genre: DGenre? = null,
    val decade: Int? = null,
    val service: Int? = null,
    val rating: Int? = null,
    val length: Int? = null,
    val sort: Int = 0,
) {
    val any: Boolean get() = genre != null || decade != null || service != null || rating != null || length != null || sort != 0
}

/** TMDB discover parameters for these filters, for films ("movie") or series ("tv"); null: no such titles. */
private fun DFilters.params(kind: String, kids: Boolean): Map<String, String>? {
    val tv = kind == "tv"
    val p = HashMap<String, String>()
    genre?.let { g -> p["with_genres"] = ((if (tv) g.tv else g.movie) ?: return null).toString() }
    val dateKey = if (tv) "first_air_date" else "primary_release_date"
    decade?.let { d ->
        if (d > 1970) p["$dateKey.gte"] = "$d-01-01"
        p["$dateKey.lte"] = "${d + 9}-12-31"
    }
    service?.let { s ->
        p["with_watch_providers"] = s.toString()
        p["watch_region"] = "GB"
        p["with_watch_monetization_types"] = "flatrate|free|ads"
    }
    rating?.let { r ->
        p["vote_average.gte"] = r.toString()
        p["vote_count.gte"] = if (tv) "100" else "300"
    }
    if (!tv) {
        when (length) {
            0 -> p["with_runtime.lte"] = "89"
            1 -> { p["with_runtime.gte"] = "90"; p["with_runtime.lte"] = "120" }
            2 -> p["with_runtime.gte"] = "121"
        }
    }
    when (sort) {
        1 -> {
            p["sort_by"] = "vote_average.desc"
            p["vote_count.gte"] = maxOf(p["vote_count.gte"]?.toIntOrNull() ?: 0, if (tv) 300 else 1000).toString()
        }
        2 -> {
            p["sort_by"] = "$dateKey.desc"
            p["$dateKey.lte"] = minOf(p["$dateKey.lte"] ?: "9999", java.time.LocalDate.now().toString())
            p.putIfAbsent("vote_count.gte", "20")
        }
        else -> {
            p["sort_by"] = "popularity.desc"
            p.putIfAbsent("vote_count.gte", "50")
        }
    }
    if (kids) kidsParams(tv, p)
    return p
}

/** Children's profiles: family titles only. */
private fun kidsParams(tv: Boolean, p: MutableMap<String, String>) {
    if (tv) {
        if (p["with_genres"] == null) p["with_genres"] = "10762|10751|16"
    } else {
        p["certification_country"] = "GB"
        p["certification.lte"] = "PG"
    }
}

/** One row on Discover: [load] gives its titles for films ("movie") or series ("tv"), null when it has none of that kind. */
private class DRow(val key: String, val title: String, val load: (suspend (kind: String, kids: Boolean) -> List<TmdbItem>?))

/** The page a "fresh every day" row starts from, so it changes daily but stays put through the day. */
private fun dayPage(pages: Int): Int = (java.time.LocalDate.now().toEpochDay() % pages).toInt() + 1

/**
 * Discover: films and series to find, from TMDB, in rows (trending, new on the UK services, top
 * rated, hidden gems, in cinemas, moods and more) with a big picture of whatever you're on. Filter
 * chips at the top (genre, decade, service, rating, length, order) turn it into a grid of
 * everything that matches. Your add-ons aren't part of it.
 */
@Composable
fun DiscoverScreen(mvm: MediaViewModel, focusTick: Int, onOpen: (MetaPreview) -> Unit, onSetUpTmdb: () -> Unit) {
    val first = remember { FocusRequester() }
    var handled by remember { mutableIntStateOf(focusTick) }
    LaunchedEffect(focusTick) {
        if (focusTick == handled) return@LaunchedEffect
        handled = focusTick
        delay(120)
        first.tryRequest()
    }
    if (!mvm.tmdb.enabled) {
        Column(Modifier.padding(start = RowInset, top = 40.dp, end = 40.dp)) {
            Text("Discover", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Discover finds films and series for you with TMDB: what's trending, new on Netflix, Prime and the rest, top rated, hidden gems and more. Add your free TMDB key to start.",
                color = ZappColors.Dim,
                fontSize = TextSize.Body,
                modifier = Modifier.width(560.dp),
            )
            Spacer(Modifier.height(18.dp))
            ZButton("Set up TMDB", onSetUpTmdb, style = ButtonStyle.Primary, focusRequester = first)
        }
        return
    }

    var kind by remember { mutableStateOf(DKind.entries.getOrElse(mvm.settings.discoverKind) { DKind.Films }) }
    var filters by remember { mutableStateOf(DFilters()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var hero by remember { mutableStateOf<HeroItem?>(null) }
    val kids = mvm.isKids

    ImmersiveLayout(
        mvm = mvm,
        focused = hero,
        topRight = { ClockAndAvatar(mvm) },
        placeholder = {
            Column(Modifier.align(Alignment.BottomStart).padding(start = RowInset, bottom = 24.dp)) {
                Text("Discover", fontSize = TextSize.Hero, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(6.dp))
                Text("Something new to watch, from everything out there", color = ZappColors.Dim, fontSize = TextSize.Body)
            }
        },
    ) {
        item(key = "controls") {
            DiscoverControls(
                kind = kind,
                filters = filters,
                firstFocus = first,
                onKind = {
                    kind = it
                    mvm.settings.discoverKind = it.ordinal
                    if (it == DKind.Series && filters.length != null) filters = filters.copy(length = null)
                },
                onPick = { picking = it },
                onClear = { filters = DFilters() },
            )
        }
        if (filters.any) {
            discoverGrid(mvm, kind, filters, kids, onFocus = { hero = it }, onOpen = onOpen)
        } else {
            discoverRows(mvm, kind, kids).forEach { row ->
                item(key = "row:${row.key}:${kind.name}") {
                    DiscoverRowView(mvm, row, kind, kids, onFocus = { hero = it }, onOpen = onOpen)
                }
            }
        }
    }

    picking?.let { which ->
        FilterDialog(which, kind, filters, onPick = { filters = it; picking = null }) { picking = null }
    }
}

/** The rows, in order. */
private fun discoverRows(mvm: MediaViewModel, kind: DKind, kids: Boolean): List<DRow> {
    val today = java.time.LocalDate.now().toString()
    fun disc(key: String, title: String, movie: Map<String, String>?, tv: Map<String, String>?, page: () -> Int = { 1 }) =
        DRow(key, title) { k, isKids ->
            val base = (if (k == "tv") tv else movie) ?: return@DRow null
            val p = HashMap(base).also { if (isKids) kidsParams(k == "tv", it) }
            mvm.tmdb.titles("discover/$k", p, k, page())
        }
    fun list(key: String, title: String, moviePath: String?, tvPath: String?, params: Map<String, String> = emptyMap()) =
        DRow(key, title) { k, _ ->
            val path = (if (k == "tv") tvPath else moviePath) ?: return@DRow null
            mvm.tmdb.titles(path, params, k)
        }
    val services = mapOf("with_watch_providers" to ALL_SERVICES, "watch_region" to "GB", "with_watch_monetization_types" to "flatrate|free|ads")
    val rows = ArrayList<DRow>()
    if (!kids) rows += list("trending", "Trending this week", "trending/movie/week", "trending/tv/week")
    rows += disc(
        "new", "New on your services",
        services + mapOf("sort_by" to "primary_release_date.desc", "primary_release_date.lte" to today, "vote_count.gte" to "25"),
        services + mapOf("sort_by" to "first_air_date.desc", "first_air_date.lte" to today, "vote_count.gte" to "15"),
    )
    if (kind != DKind.Series) rows += list("cinemas", "In cinemas now", "movie/now_playing", null, mapOf("region" to "GB"))
    if (kind == DKind.Series) rows += list("onair", "On this week", null, "tv/on_the_air")
    rows += disc("top", "Top rated", mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "4000"), mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "1000"), page = { dayPage(3) })
    rows += disc(
        "feelgood", "Feel-good",
        mapOf("with_genres" to "35|10751", "without_genres" to "27|53|80|10752", "vote_average.gte" to "6.8", "vote_count.gte" to "800", "sort_by" to "popularity.desc"),
        mapOf("with_genres" to "35|10751", "without_genres" to "80|10768", "vote_average.gte" to "7", "vote_count.gte" to "150", "sort_by" to "popularity.desc"),
        page = { dayPage(3) },
    )
    rows += disc(
        "gems", "Hidden gems",
        mapOf("vote_average.gte" to "7.3", "vote_count.gte" to "300", "vote_count.lte" to "2500", "sort_by" to "vote_average.desc"),
        mapOf("vote_average.gte" to "7.8", "vote_count.gte" to "120", "vote_count.lte" to "1200", "sort_by" to "vote_average.desc"),
        page = { dayPage(5) },
    )
    rows += disc(
        "edge", "Edge of your seat",
        mapOf("with_genres" to "53|80", "vote_average.gte" to "7", "vote_count.gte" to "1500", "sort_by" to "popularity.desc"),
        mapOf("with_genres" to "80|9648", "vote_average.gte" to "7.5", "vote_count.gte" to "300", "sort_by" to "popularity.desc"),
        page = { dayPage(3) },
    )
    rows += disc(
        "mind", "Mind-bending",
        mapOf("with_genres" to "878|9648", "vote_average.gte" to "7.2", "vote_count.gte" to "2000", "sort_by" to "popularity.desc"),
        mapOf("with_genres" to "10765", "vote_average.gte" to "7.5", "vote_count.gte" to "300", "sort_by" to "popularity.desc"),
        page = { dayPage(2) },
    )
    rows += disc(
        "cosy", "Cosy nights in",
        mapOf("with_genres" to "10749|10751|16", "without_genres" to "27|53|80", "vote_average.gte" to "7", "vote_count.gte" to "1000", "sort_by" to "popularity.desc"),
        mapOf("with_genres" to "10751|16|35", "without_genres" to "80|10759", "vote_average.gte" to "7.5", "vote_count.gte" to "150", "sort_by" to "popularity.desc"),
        page = { dayPage(3) },
    )
    rows += disc(
        "epic", "Epic adventures",
        mapOf("with_genres" to "12|14", "vote_average.gte" to "7", "vote_count.gte" to "3000", "sort_by" to "popularity.desc"),
        mapOf("with_genres" to "10759|10765", "vote_average.gte" to "7.8", "vote_count.gte" to "500", "sort_by" to "popularity.desc"),
        page = { dayPage(2) },
    )
    rows += DRow("true", "True stories") { k, isKids ->
        if (k == "tv") {
            mvm.tmdb.titles("discover/tv", HashMap(mapOf("with_genres" to "99", "vote_average.gte" to "7.5", "vote_count.gte" to "100", "sort_by" to "popularity.desc")).also { if (isKids) kidsParams(true, it) }, "tv")
        } else {
            val kw = mvm.tmdb.keywordId("based on true story") ?: return@DRow null
            mvm.tmdb.titles("discover/movie", HashMap(mapOf("with_keywords" to kw.toString(), "vote_count.gte" to "1000", "sort_by" to "popularity.desc")).also { if (isKids) kidsParams(false, it) }, "movie", dayPage(2))
        }
    }
    if (kind != DKind.Series && !kids) {
        rows += list("soon", "Coming soon to cinemas", "movie/upcoming", null, mapOf("region" to "GB"))
    }
    return rows
}

/** Films and series titles into cards (with IMDb ids, so they open and show your marks). */
private suspend fun previews(mvm: MediaViewModel, items: List<TmdbItem>, kids: Boolean): List<MetaPreview> = coroutineScope {
    items.take(20).map { async { runCatching { mvm.tmdbToPreview(it) }.getOrNull() } }.awaitAll().filterNotNull()
        .let { list -> if (kids) list.filter { com.zapp.tv.data.ProfileStore.kidsSafe(it.genres) } else list }
        .distinctBy { it.id }
}

/** Both kinds in one list: films and series taking turns. */
private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
    val out = ArrayList<T>(a.size + b.size)
    for (i in 0 until maxOf(a.size, b.size)) {
        a.getOrNull(i)?.let { out += it }
        b.getOrNull(i)?.let { out += it }
    }
    return out
}

@Composable
private fun DiscoverRowView(mvm: MediaViewModel, row: DRow, kind: DKind, kids: Boolean, onFocus: (HeroItem) -> Unit, onOpen: (MetaPreview) -> Unit) {
    val items by produceState<List<MetaPreview>?>(null, row.key, kind) {
        for (wait in listOf(0L, 15_000L, 60_000L)) {
            delay(wait)
            val got = runCatching {
                coroutineScope {
                    val films = if (kind != DKind.Series) async { row.load("movie", kids) } else null
                    val series = if (kind != DKind.Films) async { row.load("tv", kids) } else null
                    interleave(films?.await().orEmpty(), series?.await().orEmpty())
                }
            }.getOrNull()
            if (got != null) {
                value = previews(mvm, got, kids)
                if (value.orEmpty().size >= 3) break
            }
        }
    }
    val list = items ?: return
    if (list.size < 3) return
    MetaRow(
        title = row.title,
        items = list,
        badges = emptyMap(),
        size = 1,
        landscape = false,
        pendingId = null,
        onPendingDone = {},
        onFocusItem = { m -> onFocus(HeroItem(m, row.title)) },
        onOpen = onOpen,
        onLongPress = {},
    )
}

/** Filtered: everything that matches, as a grid that keeps loading as you go down. */
private fun androidx.compose.foundation.lazy.LazyListScope.discoverGrid(
    mvm: MediaViewModel,
    kind: DKind,
    filters: DFilters,
    kids: Boolean,
    onFocus: (HeroItem) -> Unit,
    onOpen: (MetaPreview) -> Unit,
) {
    item(key = "grid:$kind:$filters") {
        DiscoverGrid(mvm, kind, filters, kids, onFocus, onOpen)
    }
}

@Composable
private fun DiscoverGrid(mvm: MediaViewModel, kind: DKind, filters: DFilters, kids: Boolean, onFocus: (HeroItem) -> Unit, onOpen: (MetaPreview) -> Unit) {
    val results = remember(kind, filters) { mutableStateListOf<MetaPreview>() }
    var page by remember(kind, filters) { mutableIntStateOf(1) }
    var loading by remember(kind, filters) { mutableStateOf(true) }
    var finished by remember(kind, filters) { mutableStateOf(false) }
    LaunchedEffect(kind, filters, page) {
        loading = true
        val got = runCatching {
            coroutineScope {
                val films = if (kind != DKind.Series) filters.params("movie", kids)?.let { p -> async { mvm.tmdb.titles("discover/movie", p, "movie", page) } } else null
                val series = if (kind != DKind.Films) filters.params("tv", kids)?.let { p -> async { mvm.tmdb.titles("discover/tv", p, "tv", page) } } else null
                interleave(films?.await().orEmpty(), series?.await().orEmpty())
            }
        }.getOrDefault(emptyList())
        if (got.isEmpty() || page >= 10) finished = true
        val more = previews(mvm, got, kids).filter { m -> results.none { it.id == m.id } }
        results.addAll(more)
        loading = false
    }
    val perRow = 6
    val title = describe(kind, filters)
    Column {
        RowHeader(title, if (results.isEmpty() && !loading) "Nothing matches. Try fewer filters." else null)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            results.chunked(perRow).forEachIndexed { r, chunk ->
                Row(Modifier.padding(start = RowInset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    chunk.forEach { m ->
                        PosterCard(
                            meta = m,
                            onFocus = {
                                onFocus(HeroItem(m, title))
                                // Nearly at the end: the next page.
                                if (!loading && !finished && r >= results.size / perRow - 1) page++
                            },
                            onClick = { onOpen(m) },
                            width = cardWidth(1, false),
                            showTitle = false,
                        )
                    }
                }
            }
        }
        if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ZappLoader(28.dp) }
    }
}

/** "Comedy films from the 1990s on Netflix" and so on, for the grid's heading. */
private fun describe(kind: DKind, f: DFilters): String {
    val what = when (kind) {
        DKind.Films -> "films"
        DKind.Series -> "series"
        DKind.Both -> "films and series"
    }
    val parts = ArrayList<String>()
    parts += listOfNotNull(
        when (f.sort) { 1 -> "Top rated"; 2 -> "Newest"; else -> null },
        f.genre?.name?.lowercase(),
    ).joinToString(" ").ifBlank { "" } + " " + what
    f.rating?.let { parts += "rated $it+" }
    f.decade?.let { parts += if (it == 1970) "from before 1980" else "from the ${it}s" }
    f.length?.let { parts += LENGTHS[it].lowercase() }
    f.service?.let { s -> SERVICES.firstOrNull { it.first == s }?.let { parts += "on ${it.second}" } }
    return parts.joinToString(", ").trim().replaceFirstChar { it.uppercase() }
}

/** Films · Series · Both, then the filter chips. */
@Composable
private fun DiscoverControls(
    kind: DKind,
    filters: DFilters,
    firstFocus: FocusRequester,
    onKind: (DKind) -> Unit,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    MinimalScrolling {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = RowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(DKind.entries, key = { "k:" + it.name }) { k ->
                Chip(k.label, chosen = k == kind, requester = if (k == kind) firstFocus else null) { onKind(k) }
            }
            item(key = "gap") { Spacer(Modifier.width(14.dp)) }
            item(key = "genre") { Chip(filters.genre?.name ?: "Genre", chosen = filters.genre != null) { onPick("genre") } }
            item(key = "decade") { Chip(filters.decade?.let { if (it == 1970) "Before 1980" else "${it}s" } ?: "Decade", chosen = filters.decade != null) { onPick("decade") } }
            item(key = "service") { Chip(filters.service?.let { s -> SERVICES.firstOrNull { it.first == s }?.second } ?: "Service", chosen = filters.service != null) { onPick("service") } }
            item(key = "rating") { Chip(filters.rating?.let { "Rated $it+" } ?: "Rating", chosen = filters.rating != null) { onPick("rating") } }
            if (kind == DKind.Films) {
                item(key = "length") { Chip(filters.length?.let { LENGTHS[it] } ?: "Length", chosen = filters.length != null) { onPick("length") } }
            }
            item(key = "sort") { Chip(if (filters.sort == 0) "Sort" else SORTS[filters.sort], chosen = filters.sort != 0) { onPick("sort") } }
            if (filters.any) {
                item(key = "clear") { Chip("✕  Clear", chosen = false, quiet = true, onClick = onClear) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Chip(label: String, chosen: Boolean, requester: FocusRequester? = null, quiet: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        focusRequester = requester,
        shape = RoundedCornerShape(50),
        focusScale = 1.04f,
        restColor = if (chosen) ZappColors.GlassHigh else if (quiet) androidx.compose.ui.graphics.Color.Transparent else ZappColors.Glass,
    ) { focused ->
        Text(
            label,
            color = when {
                focused -> ZappColors.OnFocus
                chosen -> ZappColors.Text
                else -> ZappColors.Dim
            },
            fontSize = TextSize.Small,
            fontWeight = if (chosen || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The choices for one filter, in a small pop-up. */
@Composable
private fun FilterDialog(which: String, kind: DKind, f: DFilters, onPick: (DFilters) -> Unit, onClose: () -> Unit) {
    val (title, options) = when (which) {
        "genre" -> "Genre" to listOf<Pair<String, DFilters>>("Any genre" to f.copy(genre = null)) +
            GENRES.filter { g -> (kind != DKind.Series || g.tv != null) && (kind != DKind.Films || g.movie != null) }.map { g -> g.name to f.copy(genre = g) }
        "decade" -> "Decade" to listOf("Any time" to f.copy(decade = null)) + DECADES.map { d -> (if (d == 1970) "Before 1980" else "${d}s") to f.copy(decade = d) }
        "service" -> "Where to watch" to listOf("Anywhere" to f.copy(service = null)) + SERVICES.map { (id, name) -> name to f.copy(service = id) }
        "rating" -> "Rating" to listOf("Any rating" to f.copy(rating = null)) + listOf(6, 7, 8).map { r -> "$r and over" to f.copy(rating = r) }
        "length" -> "Length" to listOf("Any length" to f.copy(length = null)) + LENGTHS.mapIndexed { i, l -> l to f.copy(length = i) }
        else -> "Sort by" to SORTS.mapIndexed { i, s -> s to f.copy(sort = i) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(360.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(horizontal = 12.dp, vertical = 18.dp),
        ) {
            Text(title, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(options.size) { i ->
                    val (label, value) = options[i]
                    val chosen = value == f
                    FocusSurface(
                        onClick = { onPick(value) },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (chosen || (i == 0 && options.none { it.second == f })) first else null,
                        selected = chosen,
                        shape = Shapes.Row,
                        focusScale = 1f,
                    ) { focused ->
                        Text(
                            label,
                            color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                            fontSize = TextSize.Body,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
