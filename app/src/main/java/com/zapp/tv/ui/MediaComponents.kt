package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.addons.CatalogRef
import com.zapp.tv.addons.MetaPreview
import com.zapp.tv.data.WatchProgress

val PosterWidth = 122.dp

/** Card width for a Home row: size 0 small, 1 medium, 2 large. */
fun cardWidth(size: Int, landscape: Boolean): Dp = if (landscape) {
    when (size) { 0 -> 176.dp; 2 -> 256.dp; else -> 210.dp }
} else {
    when (size) { 0 -> 94.dp; 2 -> 140.dp; else -> 114.dp }
}

/**
 * Film or series card. Posters by default; [landscape] uses the wide background art with the
 * title (or logo) over it. Landscape-shaped add-on posters always get a wide card.
 */
@Composable
fun PosterCard(
    meta: MetaPreview,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = null,
    landscape: Boolean = false,
    showTitle: Boolean = true,
) {
    val wide = landscape || meta.posterShape == "landscape"
    val w = width ?: if (wide) 216.dp else PosterWidth
    Column(Modifier.width(w)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f),
            onFocusChange = { if (it) onFocus() },
            shape = RoundedCornerShape(10.dp),
            focusScale = 1f,
            restColor = ZappColors.Glass,
        ) { focused ->
            if (landscape && meta.posterShape != "landscape") {
                Artwork(meta.background ?: meta.poster, meta.name)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
                Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                    if (meta.logo != null) {
                        AsyncImage(
                            model = meta.logo,
                            contentDescription = meta.name,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier.heightIn(max = w * 0.18f).widthIn(max = w * 0.6f),
                        )
                    } else {
                        Text(meta.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
                    }
                }
            } else {
                Artwork(meta.poster, meta.name)
            }
            if (focused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.5.dp, Color.White, RoundedCornerShape(10.dp)),
                )
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(7.dp))
            Text(meta.name, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ZappColors.Dim)
        }
    }
}

/** Image that fills its box, with the title as a fallback while it loads (or if there is none). */
@Composable
fun Artwork(url: String?, title: String, contentScale: ContentScale = ContentScale.Crop) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            title,
            color = ZappColors.Faint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A film or episode you started, with how far you got. */
@Composable
fun ContinueCard(
    progress: WatchProgress,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp = 220.dp,
) {
    Column(Modifier.width(width)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            onFocusChange = { if (it) onFocus() },
            shape = RoundedCornerShape(12.dp),
            focusScale = 1f,
            restColor = ZappColors.Glass,
        ) { focused ->
            Artwork(progress.background ?: progress.poster, progress.name)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))),
            )
            ProgressLine(
                if (progress.duration > 1) (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f)
                else ((progress.percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 9.dp)
                    .fillMaxWidth(),
                track = Color(0x66FFFFFF),
                fill = Color.White,
            )
            if (focused) {
                Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(12.dp)))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(progress.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(progress.episodeLabel, minutesLeft(progress)).joinToString(", "),
            color = ZappColors.Dim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun minutesLeft(p: WatchProgress): String {
    if (p.duration <= 1) return "${(p.percent ?: 0.0).toInt()}% watched"
    val left = ((p.duration - p.position) / 60_000).coerceAtLeast(1)
    return if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left"
}

/** Big artwork header for films and series, used on Home, Movies and Series. */
@Composable
fun MediaHero(meta: MetaPreview?, height: Dp, emptyTitle: String, emptyBody: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (meta?.background != null || meta?.poster != null) {
            AsyncImage(
                model = meta.background ?: meta.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.85f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to ZappColors.Bg, 0.45f to ZappColors.Bg.copy(alpha = 0.75f), 1f to Color.Transparent)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to ZappColors.Bg)),
        )
        Column(
            Modifier
                .padding(start = 40.dp, top = 30.dp)
                .widthIn(max = 420.dp),
        ) {
            if (meta == null) {
                Text(emptyTitle, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                Spacer(Modifier.height(8.dp))
                Text(emptyBody, color = ZappColors.Dim, fontSize = 13.sp, lineHeight = 19.sp)
            } else {
                if (meta.logo != null) {
                    AsyncImage(
                        model = meta.logo,
                        contentDescription = meta.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .heightIn(max = 76.dp)
                            .widthIn(max = 300.dp),
                    )
                } else {
                    Text(meta.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                MetaFacts(meta.releaseInfo, meta.imdbRating, null, meta.genres)
                meta.description?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = ZappColors.Dim, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A film or series title in poster lettering (Anton), large and tight. */
@Composable
fun MovieTitle(name: String, size: androidx.compose.ui.unit.TextUnit, maxLines: Int = 2, modifier: Modifier = Modifier) {
    Text(
        name,
        fontFamily = TitleFont,
        fontSize = size,
        lineHeight = size * 1.02f,
        letterSpacing = 0.4.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = Color.White,
        modifier = modifier,
    )
}

/** "81 min" or "81" becomes "1 h 21 min"; anything else is left as it is. */
fun prettyRuntime(runtime: String?): String? {
    val r = runtime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val mins = Regex("""^(\d+)\s*(min|mins|minutes|m)?$""", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.toIntOrNull() ?: return r
    return if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
}

/** Year, runtime, genres and rating on one quiet line. */
@Composable
fun FactsLine(year: String?, rating: String?, runtime: String?, genres: List<String>, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(year, prettyRuntime(runtime), genres.take(3).joinToString(", ").ifBlank { null })
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(parts.joinToString("   ·   "))
        if (rating != null) {
            if (parts.isNotEmpty()) append("   ·   ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = ZappColors.Star))
            append("★ ")
            pop()
            append(rating)
        }
    }
    Text(text, color = ZappColors.Dim, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Year, rating, runtime and genres as small tags. */
@Composable
fun MetaFacts(year: String?, rating: String?, runtime: String?, genres: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        year?.let { Tag(it) }
        rating?.let { Tag("IMDb $it", highlight = true) }
        runtime?.let { Tag(it) }
        genres.take(3).forEach { Tag(it) }
    }
}

@Composable
fun Tag(text: String, highlight: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) Color(0x33FFC94A) else ZappColors.GlassHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (highlight) ZappColors.Star else ZappColors.Text, maxLines = 1)
    }
}

/**
 * One add-on catalogue as a scrolling row. Loads when it comes on screen and fetches more
 * as you reach the end. Rows that fail or come back empty hide themselves.
 */
@Composable
fun CatalogRow(
    mvm: MediaViewModel,
    ref: CatalogRef,
    title: String,
    onFocusItem: (MetaPreview) -> Unit,
    onOpen: (MetaPreview) -> Unit,
    pendingId: String? = null,
    onPendingDone: () -> Unit = {},
    size: Int = 1,
    landscape: Boolean = false,
    showTitles: Boolean = true,
    onLongPress: ((MetaPreview) -> Unit)? = null,
    subtitle: String? = null,
) {
    val items = remember(ref.key) { mutableStateListOf<MetaPreview>() }
    var loading by remember(ref.key) { mutableStateOf(true) }
    var failed by remember(ref.key) { mutableStateOf(false) }
    var exhausted by remember(ref.key) { mutableStateOf(false) }
    var requestMore by remember(ref.key) { mutableStateOf(0) }
    val memory = rememberFocusMemory()
    val listState = rememberLazyListState()
    val width = cardWidth(size, landscape)

    LaunchedEffect(ref.key, requestMore) {
        if (exhausted) return@LaunchedEffect
        loading = true
        try {
            val page = mvm.page(ref, items.size)
            val fresh = page.filter { p -> items.none { it.id == p.id } }
            items.addAll(fresh)
            if (fresh.isEmpty() || !ref.def.pageable) exhausted = true
        } catch (e: Exception) {
            if (items.isEmpty()) failed = true
            exhausted = true
        } finally {
            loading = false
        }
    }

    if (failed || (!loading && items.isEmpty())) return

    Column {
        RowHeader(title, subtitle)
        PivotScrolling(RowInset) {
            LazyRow(
                state = listState,
                modifier = Modifier.restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(if (size == 0) 10.dp else 12.dp),
                contentPadding = PaddingValues(horizontal = RowInset),
            ) {
                if (items.isEmpty()) {
                    items(8) {
                        Box(
                            Modifier
                                .width(width)
                                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ZappColors.Glass),
                        )
                    }
                }
                itemsIndexed(items, key = { _, m -> m.id }) { index, meta ->
                    val requester = remember { FocusRequester() }
                    // An empty id means "the first card" (first start, nothing remembered yet).
                    if (pendingId == meta.id || (pendingId == "" && index == 0)) {
                        LaunchedEffect(Unit) {
                            requester.tryRequest()
                            onPendingDone()
                        }
                    }
                    PosterCard(
                        meta = meta,
                        modifier = Modifier
                            .rememberFocus(memory, meta.id)
                            .focusRequesterSafe(requester),
                        onFocus = {
                            onFocusItem(meta)
                            if (index >= items.size - 8 && !loading && !exhausted) requestMore++
                        },
                        onClick = { onOpen(meta) },
                        onLongClick = onLongPress?.let { f -> { f(meta) } },
                        width = width,
                        landscape = landscape,
                        showTitle = showTitles,
                    )
                }
            }
        }
    }
}

/** Space between the screen edge and the first card of a row. */
val RowInset = 48.dp

@Composable
fun RowHeader(title: String, subtitle: String? = null) {
    Row(
        Modifier.padding(start = RowInset, bottom = 9.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
        subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontSize = 11.sp, color = ZappColors.Faint)
        }
    }
}
