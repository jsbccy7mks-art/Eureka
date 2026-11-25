package com.zapp.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
    var hero by remember(type) { mutableStateOf<MetaPreview?>(null) }
    val emptyFocus = remember { FocusRequester() }
    var handledTick by remember { mutableIntStateOf(focusTick) }
    val label = if (type == "movie") "Movies" else "Series"

    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        emptyFocus.tryRequest()
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

    Column(Modifier.fillMaxSize()) {
        MediaHero(hero, 270.dp, label, "Loading catalogues…")
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
        ) {
            items(list.orEmpty(), key = { it.key }) { ref ->
                CatalogRow(
                    mvm = mvm,
                    ref = ref,
                    title = if (list.orEmpty().count { it.def.name == ref.def.name } > 1) "${ref.def.name} from ${ref.addon.manifest.name}" else ref.def.name,
                    onFocusItem = { hero = it },
                    onOpen = onOpen,
                )
            }
        }
    }
}

/** Saved films and series, plus anything you're part-way through. */
@Composable
fun LibraryScreen(
    mvm: MediaViewModel,
    focusTick: Int,
    onOpen: (MetaPreview) -> Unit,
) {
    val library by mvm.library.collectAsState()
    var handledTick by remember { mutableIntStateOf(focusTick) }
    val first = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        if (focusTick == handledTick) return@LaunchedEffect
        handledTick = focusTick
        first.tryRequest()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Text("Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 40.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            if (library.isEmpty()) "Nothing saved yet. Open a film or series and choose Add to library." else "${library.size} saved",
            color = ZappColors.Dim,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 40.dp),
        )
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PosterWidth + 10.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(library, key = { it.metaId }) { item ->
                val meta = MetaPreview(item.metaId, item.type, item.name, item.poster, "poster", null, null, null, null, null, emptyList())
                Box(contentAlignment = Alignment.TopCenter) {
                    PosterCard(
                        meta = meta,
                        modifier = if (item == library.firstOrNull()) Modifier.focusRequesterSafe(first) else Modifier,
                        onFocus = {},
                        onClick = { onOpen(meta) },
                    )
                }
            }
        }
    }
}
