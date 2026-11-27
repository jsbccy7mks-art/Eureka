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

/** Saved films and series: All, Films or Series, sorted how you like (both remembered). */
@Composable
fun LibraryScreen(
    mvm: MediaViewModel,
    focusTick: Int,
    onOpenCollection: (Long) -> Unit = {},
    onRateAFew: () -> Unit = {},
    onOpen: (MetaPreview) -> Unit,
) {
    val library by mvm.library.collectAsState()
    val lastWatched by mvm.lastWatched.collectAsState()
    // Always opens on Films.
    var tab by remember { mutableStateOf("movie") }
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

    val loved = mvm.ratings.all().filter { it.rating == com.zapp.tv.data.Rating.Love }
    val shown = remember(library, tab, sort, lastWatched, mvm.libraryYears.toMap(), loved) {
        // Loved: everything you've loved, saved or not.
        val filtered = if (tab == "loved") {
            loved.map { com.zapp.tv.data.LibraryItem(metaId = it.metaId, type = it.type, name = it.name, poster = it.poster, addedAt = it.at) }
        } else {
            library.filter { tab == "all" || it.type == tab }
        }
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
            Text("Library", fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(
                if (library.isEmpty()) "Nothing saved yet" else "$films films  ·  $series series",
                color = ZappColors.Dim,
                fontSize = TextSize.Small,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            // Your month (and, from mid-December, your year) on Axon.
            val lastMonth = WrappedPeriod.lastMonth()
            ZButton("✦  Your ${lastMonth.title}", { WrappedState.open = lastMonth }, style = ButtonStyle.Quiet)
            val today = java.time.LocalDate.now()
            if (today.monthValue == 12 && today.dayOfMonth >= 15 || today.monthValue == 1) {
                val y = WrappedPeriod(if (today.monthValue == 1) today.year - 1 else today.year, null)
                Spacer(Modifier.width(8.dp))
                ZButton("✦  Your ${y.title}", { WrappedState.open = y }, style = ButtonStyle.Quiet)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(start = 40.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                listOf("movie" to "Films", "series" to "Series", "all" to "All", "loved" to "Loved"),
                tab,
            ) {
                tab = it
            }
            run {
                Spacer(Modifier.width(18.dp))
                Text("Sort", color = ZappColors.Faint, fontSize = TextSize.Small)
                Spacer(Modifier.width(8.dp))
                Segmented(listOf("added" to "Date added", "title" to "A–Z", "year" to "Year", "watched" to "Recently watched"), sort) {
                    sort = it
                    mvm.settings.librarySort = it
                }
            }
            Spacer(Modifier.weight(1f))
            ZButton("Rate films", onRateAFew, style = ButtonStyle.Quiet)
        }
        Spacer(Modifier.height(18.dp))
        if (shown.isEmpty()) {
            Text(
                if (library.isEmpty()) "Open a film or series and choose Add to library, or hold OK on any title."
                else if (tab == "loved") "Nothing loved yet. Hold OK on any title and choose the heart."
                else "No ${if (tab == "movie") "films" else "series"} saved yet.",
                color = ZappColors.Dim,
                fontSize = TextSize.Body,
                modifier = Modifier.padding(start = 40.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PosterWidth + 10.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp, top = 14.dp),
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

