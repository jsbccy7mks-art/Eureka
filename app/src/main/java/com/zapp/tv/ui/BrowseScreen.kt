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
    var options by remember(type) { mutableStateOf<String?>(null) }

    ImmersiveLayout(
        mvm = mvm,
        focused = hero,
        placeholder = {
            Column(Modifier.align(Alignment.BottomStart).padding(start = RowInset, bottom = 24.dp)) {
                Text(label, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(6.dp))
                Text(if (list == null) "Loading catalogues…" else "Every ${if (type == "movie") "film" else "series"} catalogue from your add-ons", color = ZappColors.Dim, fontSize = 13.sp)
            }
        },
    ) {
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
                showTitles = false,
                onLongPress = { options = ref.key },
            )
        }
    }

    options?.let { key ->
        val row = layout.firstOrNull { it.id == key }
        if (row == null) {
            options = null
        } else {
            RowOptionsDialog(
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
