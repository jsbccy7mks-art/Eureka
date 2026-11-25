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
    rows: LazyListScope.() -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(scrollToTop) { if (scrollToTop != null) listState.scrollToItem(0) }
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Bg),
    ) {
        val screenHeight = maxHeight
        val heroHeight = screenHeight * 0.47f
        Backdrop(hero?.meta?.let { it.background ?: detail?.preview?.background ?: it.poster })
        AmbientGlow(0.8f)

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                when {
                    header != null -> header()
                    hero == null && placeholder != null -> placeholder()
                    else -> HeroInfo(hero, detail)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PivotScrolling(26.dp) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = screenHeight * 0.5f),
                        content = rows,
                    )
                }
            }
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
private fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(
            targetState = url,
            animationSpec = tween(650),
            label = "backdrop",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.8f)
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
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.8f)
                .background(
                    // Eased fade with many stops, so there's no visible edge or banding.
                    Brush.horizontalGradient(*easedFade(start = 0f, end = 0.62f)),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.8f)
                .background(
                    Brush.verticalGradient(*easedFade(start = 1f, end = 0.5f)),
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
            val description = m.description ?: d?.preview?.description
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = RowInset, bottom = 18.dp)
                    .widthIn(max = 460.dp),
            ) {
                Text(
                    (if (m.type == "series") "SERIES" else if (m.type == "movie") "FILM" else m.type.uppercase()) + "  ·  " + h.rowTitle.uppercase(),
                    color = ZappColors.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                MovieTitle(m.name, 40.sp)
                Spacer(Modifier.height(10.dp))
                FactsLine(
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
fun RowOptionsDialog(
    row: HomeRowSetting,
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
                if (canHide) ZButton("Hide row", {
                    onChange { it.copy(on = false) }
                    onClose()
                }, style = ButtonStyle.Quiet)
            }
            if (progress != null) {
                ZButton("Remove ${progress.name} from this row", { onRemoveTitle(progress) }, style = ButtonStyle.Danger)
            }
            if (canHide) {
                Text(
                    "Hidden rows can be switched back on in Settings, Home layout.",
                    color = ZappColors.Faint,
                    fontSize = 11.sp,
                )
            }
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
