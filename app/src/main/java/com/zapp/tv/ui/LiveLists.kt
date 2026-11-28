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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.tv.data.ChannelRow
import com.zapp.tv.data.FAVORITES
import com.zapp.tv.data.RECENT
import kotlinx.coroutines.delay

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** A list line's key: categories by id, with All channels (no id) as -1. */
private fun CategoryItem.key(): Long = id ?: -1L

/**
 * The category list: Live TV's left column, and the guide's category panel. Recently watched,
 * Favourites, All channels, your categories in your order, then Hidden categories (OK opens them).
 * Hold OK on a category to hide it or move it (Up and Down move it, OK puts it down).
 */
@Composable
fun CategoryList(
    vm: AppViewModel,
    categories: List<CategoryItem>,
    selectedId: Long?,
    playlistId: Long,
    state: LazyListState,
    selectedRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocus: (CategoryItem) -> Unit = {},
    onMoving: (Boolean) -> Unit = {},
    onPick: (CategoryItem) -> Unit,
) {
    val memory = rememberFocusMemory()
    // Hidden categories stay folded away unless you open them (or you're in one).
    var showHidden by remember { mutableStateOf(categories.any { it.hidden && it.id == selectedId }) }
    var menuFor by remember { mutableStateOf<CategoryItem?>(null) }
    var moving by remember { mutableStateOf<Long?>(null) }
    var refocus by remember { mutableStateOf<Long?>(null) }
    val shown = categories.filter { !it.hidden || showHidden }
    LaunchedEffect(moving) { onMoving(moving != null) }

    // While a category is being moved, the list keeps it in view.
    LaunchedEffect(shown, moving) {
        val m = moving ?: return@LaunchedEffect
        val i = shown.indexOfFirst { it.key() == m }
        val visible = state.layoutInfo.visibleItemsInfo
        if (i < 0 || visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        if (i <= first) state.scrollToItem((i - 1).coerceAtLeast(0))
        else if (i >= last) state.scrollToItem((i - (last - first) + 2).coerceAtLeast(0))
    }

    LazyColumn(
        state = state,
        modifier = modifier
            .restoreFocus(memory)
            .onPreviewKeyEvent { e ->
                val m = moving ?: return@onPreviewKeyEvent false
                val item = categories.firstOrNull { it.key() == m }
                val ref = item?.ref
                if (ref == null) {
                    moving = null
                    return@onPreviewKeyEvent false
                }
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (e.type == KeyEventType.KeyDown) vm.moveCategory(playlistId, ref, if (e.key == Key.DirectionUp) -1 else 1)
                        true
                    }
                    in OkKeys, Key.Back, Key.DirectionLeft, Key.DirectionRight -> {
                        if (e.type == KeyEventType.KeyUp) moving = null
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(shown, key = { it.key() }) { item ->
            val requester = remember { FocusRequester() }
            if (refocus == item.key()) {
                LaunchedEffect(Unit) {
                    // After the menu closes and the hidden category has left the list.
                    delay(220)
                    requester.tryRequest()
                    refocus = null
                }
            }
            CategoryItemRow(
                modifier = Modifier
                    .rememberFocus(memory, item.key())
                    .focusRequester(requester),
                item = item,
                selected = item.id == selectedId,
                moving = moving == item.key(),
                open = showHidden,
                focusRequester = if (item.id == selectedId) selectedRequester else null,
                onFocus = { onFocus(item) },
                onClick = {
                    when {
                        moving == item.key() -> moving = null
                        item.id == HIDDEN_CATEGORIES -> showHidden = !showHidden
                        else -> onPick(item)
                    }
                },
                onLongClick = if (item.ref != null) ({ menuFor = item }) else null,
            )
        }
    }

    menuFor?.let { item ->
        CategoryMenu(
            item = item,
            onHide = {
                val hide = !item.hidden
                if (hide) {
                    // Focus goes to the next category as this one leaves the list.
                    val i = shown.indexOfFirst { it.key() == item.key() }
                    refocus = (shown.getOrNull(i + 1) ?: shown.getOrNull(i - 1))?.key()
                }
                item.ref?.let { vm.setCategoryHidden(playlistId, it, item.name, hide) }
                menuFor = null
            },
            onMove = if (!item.hidden) ({
                moving = item.key()
                menuFor = null
            }) else null,
            onClose = { menuFor = null },
        )
    }
}

@Composable
private fun CategoryItemRow(
    modifier: Modifier,
    item: CategoryItem,
    selected: Boolean,
    moving: Boolean,
    open: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val header = item.id == HIDDEN_CATEGORIES
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (header) 10.dp else 0.dp),
        selected = false,
        focusRequester = focusRequester,
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(9.dp),
        focusScale = 1.03f,
        restColor = if (moving) ZappColors.GlassHigh else androidx.compose.ui.graphics.Color.Transparent,
    ) { focused ->
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The open category: a short bar in the theme's colours, like the sidebar.
            if (selected && !focused && !moving) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.verticalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                )
                Spacer(Modifier.width(9.dp))
            }
            if (moving) {
                Text("↕", color = if (focused) ZappColors.OnFocus else ZappColors.Brand2, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
            }
            if (item.id == FAVORITES) {
                StarGlyph(if (focused) ZappColors.OnFocus else ZappColors.Star, 11.dp)
                Spacer(Modifier.width(8.dp))
            }
            if (item.id == RECENT) {
                ClockGlyph(if (focused) ZappColors.OnFocus else ZappColors.Dim, 11.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = item.name,
                color = when {
                    focused -> ZappColors.OnFocus
                    selected -> ZappColors.Text
                    item.hidden || header -> ZappColors.Faint
                    else -> ZappColors.Dim
                },
                fontSize = if (header) TextSize.Small else TextSize.Body,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (moving) {
                Text("Up/Down, OK", color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint, fontSize = TextSize.Label)
            } else if (header) {
                Text(
                    formatCount(item.count) + if (open) "  ▴" else "  ▾",
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                    fontSize = TextSize.Small,
                )
            } else if (item.count >= 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatCount(item.count),
                    color = if (focused) ZappColors.OnFocusDim else ZappColors.Faint,
                    fontSize = TextSize.Small,
                )
            }
        }
    }
}

/** Hold OK on a category: hide it (or show it again), or move it. */
@Composable
private fun CategoryMenu(item: CategoryItem, onHide: () -> Unit, onMove: (() -> Unit)?, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(420.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp)) {
                Text(item.name, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text("${formatCount(item.count)} channels" + if (item.hidden) "  ·  Hidden" else "", color = ZappColors.Faint, fontSize = TextSize.Small)
            }
            Spacer(Modifier.height(8.dp))
            MenuItem(if (item.hidden) "+" else "✕", if (item.hidden) "Show category" else "Hide category", first, onClick = onHide)
            onMove?.let { MenuItem("↕", "Move up or down", null, onClick = it) }
            Spacer(Modifier.height(6.dp))
            Text(
                if (item.hidden) "Showing it puts it back in the list, All channels, the guide, search and zapping."
                else "A hidden category is left out of All channels, the guide, search and zapping. Open it again from Hidden categories at the bottom of the list, or Settings, Live TV.",
                color = ZappColors.Faint,
                fontSize = TextSize.Small,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/**
 * Hold OK on a channel: favourite, hide, or move it in your own order. [onMove] null: moving
 * isn't possible here; [moveHint] says why when it's worth saying.
 */
@Composable
fun ChannelMenu(vm: AppViewModel, row: ChannelRow, onMove: (() -> Unit)?, moveHint: String?, onHidden: () -> Unit = {}, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        first.tryRequest()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(420.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoTile(url = row.channel.logo, fallback = row.channel.name, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.channel.name, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.nowTitle?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, color = ZappColors.Faint, fontSize = TextSize.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            MenuItem("★", if (row.isFavorite) "Remove from favourites" else "Add to favourites", first) {
                vm.toggleFavorite(row.channel)
                onClose()
            }
            MenuItem("✕", "Hide channel", null) {
                vm.setChannelHidden(row.channel, true)
                onHidden()
                onClose()
            }
            onMove?.let {
                MenuItem("↕", "Move up or down", null) {
                    it()
                    onClose()
                }
            }
            moveHint?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = ZappColors.Faint, fontSize = TextSize.Small, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}

/**
 * A reminder's programme is starting: what it is and where, with Watch now and Not now. It goes
 * away by itself after a minute.
 */
@Composable
fun ReminderPopup(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val timeFormat = rememberTimeFormat()
    LaunchedEffect(reminder) {
        delay(80)
        first.tryRequest()
        delay(60_000)
        onDismiss()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(440.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BellGlyph(ZappColors.Brand2, 14.dp)
                Spacer(Modifier.width(8.dp))
                Text("STARTING NOW", color = ZappColors.Faint, fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(reminder.title, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${reminder.channelName}  ·  ${timeFormat.format(reminder.start)} – ${timeFormat.format(reminder.stop)}",
                color = ZappColors.Dim,
                fontSize = TextSize.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZButton("Watch now", onWatch, style = ButtonStyle.Primary, focusRequester = first)
                ZButton("Not now", onDismiss, style = ButtonStyle.Quiet)
            }
        }
    }
}
