package com.zapp.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
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

/** Every wide card (landscape rows and Continue watching) has this shape. */
const val WideAspect = 1.9f

/** Titles marked or finished as watched (for the tick on cards), kept up to date by the app. */
object WatchedMarks {
    var ids by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
}

/** Started but not finished: how far (0–1), and for series the episode that's next. */
data class ProgressMark(val fraction: Float, val next: String? = null)

/** Titles you're part-way through (for the ring on cards), kept up to date by the app. */
object ProgressMarks {
    var marks by androidx.compose.runtime.mutableStateOf<Map<String, ProgressMark>>(emptyMap())
}

/**
 * The marks on a card. Top right, side by side in one small capsule (so they never overlap): a
 * heart when loved and a tick when watched. Started but not finished: a slim bar in the theme's
 * colours along the bottom, with the next episode just above it for series.
 */
@Composable
fun BoxScope.CardMarks(id: String?) {
    if (id == null) return
    val loved = id in LovedMarks.ids
    val watched = id in WatchedMarks.ids
    val progress = if (watched) null else ProgressMarks.marks[id]
    if (progress != null) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(40.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000)))),
        )
        progress.next?.let {
            Text(
                it,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 7.dp, bottom = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        val b1 = ZappColors.Brand1
        val b2 = ZappColors.Brand2
        androidx.compose.foundation.Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 7.dp, end = 7.dp, bottom = 6.dp)
                .fillMaxWidth()
                .height(3.dp),
        ) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.28f), cornerRadius = r)
            val w = size.width * progress.fraction.coerceIn(0.04f, 1f)
            drawRoundRect(
                Brush.horizontalGradient(listOf(b1, b2), startX = 0f, endX = size.width),
                size = androidx.compose.ui.geometry.Size(w, size.height),
                cornerRadius = r,
            )
        }
    }
    if (loved || watched) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x8C0A0A12))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loved) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    RatingGlyph(com.zapp.tv.data.Rating.Love, filled = true, color = Color(0xFFFF5F8F), size = 12.dp)
                }
            }
            if (watched) WatchedTick(size = 14.dp)
        }
    }
}

/** Like the tick, but a ring filling up in the theme's colours to show how far you got. */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val d = this.size.width
        drawCircle(Color(0x40000000), radius = d / 2f, center = center.copy(y = center.y + d * 0.04f))
        drawCircle(Color.White, radius = d / 2f)
        val stroke = d * 0.14f
        val inset = d * 0.2f
        val arcSize = androidx.compose.ui.geometry.Size(d - inset * 2, d - inset * 2)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(Color(0x22000000), 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(
            Brush.linearGradient(listOf(b1, b2), start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(d, d)),
            -90f,
            360f * fraction.coerceIn(0.06f, 1f),
            false,
            topLeft,
            arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** A tiny white circle with a thin tick in the theme's colours. */
@Composable
fun WatchedTick(modifier: Modifier = Modifier, size: Dp = 15.dp) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val d = this.size.width
        drawCircle(Color(0x40000000), radius = d / 2f, center = center.copy(y = center.y + d * 0.04f))
        drawCircle(Color.White, radius = d / 2f)
        val tick = androidx.compose.ui.graphics.Path().apply {
            moveTo(d * 0.29f, d * 0.52f)
            lineTo(d * 0.44f, d * 0.66f)
            lineTo(d * 0.72f, d * 0.36f)
        }
        drawPath(
            tick,
            Brush.linearGradient(listOf(ZappColors.Brand1, ZappColors.Brand2), start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(d, d)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = d * 0.1f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** Card width for a Home row: size 0 small, 1 medium, 2 large. */
fun cardWidth(size: Int, landscape: Boolean): Dp = if (landscape) {
    when (size) { 0 -> 176.dp; 2 -> 248.dp; else -> 208.dp }
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
    /** Ranked landscape cards: a big number set into the left of the picture. */
    rank: Int? = null,
) {
    val wide = landscape || meta.posterShape == "landscape"
    val w = width ?: if (wide) 208.dp else PosterWidth
    Column(Modifier.width(w)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) WideAspect else 2f / 3f),
            onFocusChange = { if (it) onFocus() },
            shape = RoundedCornerShape(10.dp),
            focusScale = 1f,
            restColor = ZappColors.Glass,
            focusColor = ZappColors.Glass,
            ring = true,
        ) { focused ->
            if (landscape && meta.posterShape != "landscape") {
                Artwork(meta.background ?: meta.poster, meta.name)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
                // Ranked cards keep the title tiny in the bottom right, out of the number's way.
                Box(Modifier.align(if (rank != null) Alignment.BottomEnd else Alignment.BottomStart).padding(if (rank != null) 8.dp else 10.dp)) {
                    if (meta.logo != null) {
                        AsyncImage(
                            model = meta.logo,
                            contentDescription = meta.name,
                            contentScale = ContentScale.Fit,
                            alignment = if (rank != null) Alignment.BottomEnd else Alignment.BottomStart,
                            modifier = if (rank != null) Modifier.heightIn(max = w * 0.09f).widthIn(max = w * 0.3f) else Modifier.heightIn(max = w * 0.18f).widthIn(max = w * 0.6f),
                        )
                    } else {
                        Text(
                            meta.name,
                            fontSize = if (rank != null) 9.5.sp else 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (rank != null) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            modifier = if (rank != null) Modifier.widthIn(max = w * 0.4f) else Modifier,
                        )
                    }
                }
            } else {
                Artwork(meta.poster, meta.name, fallback = fallbackPoster(meta.id))
            }
            CardMarks(meta.id)
            if (rank != null) {
                // Minimal: a small number on the left, with just a soft drop shadow.
                Text(
                    rank.toString(),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = TitleFont,
                        fontSize = TextSize.Page,
                        lineHeight = 26.sp,
                        color = Color.White,
                        shadow = androidx.compose.ui.graphics.Shadow(Color(0xB3000000), androidx.compose.ui.geometry.Offset(0f, 2f), 6f),
                    ),
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                )
            }
        }
        if (showTitle || FocusStyle.cardTitles) {
            Spacer(Modifier.height(7.dp))
            Text(meta.name, fontSize = TextSize.Small, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ZappColors.Dim)
        }
    }
}

/** A poster that always exists for IMDb titles (Cinemeta's image server), used when one is missing or broken. */
fun fallbackPoster(id: String): String? = id.substringBefore(':').takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }

/** Image that fills its box, with the title as a fallback while it loads (or if there is none). */
@Composable
fun Artwork(url: String?, title: String, contentScale: ContentScale = ContentScale.Crop, fallback: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            title,
            color = ZappColors.Faint,
            fontSize = TextSize.Small,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp),
        )
        val src = url?.takeIf { it.isNotBlank() } ?: fallback
        if (!src.isNullOrBlank()) {
            // If the picture won't load, try the fallback (a poster that always exists for IMDb titles).
            var failed by remember(src) { mutableStateOf(false) }
            // No fade-in on cards (they appear as you scroll; a fade on each costs frames).
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val pic = if (failed && fallback != null && fallback != src) fallback else src
            AsyncImage(
                model = remember(pic) { coil.request.ImageRequest.Builder(ctx).data(pic).crossfade(false).build() },
                contentDescription = null,
                contentScale = contentScale,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A title you're part-way through: the show's own wide artwork with its logo (or name) bottom left,
 * how much is left bottom right, and a thin progress line along the bottom edge.
 */
@Composable
fun ContinueCard(
    progress: WatchProgress,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp = 220.dp,
    art: MediaViewModel.ShowArt? = null,
) {
    val name = art?.name?.takeIf { progress.name.isBlank() || progress.name.startsWith("tt") } ?: progress.name
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .aspectRatio(WideAspect),
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(12.dp),
        focusScale = 1f,
        restColor = ZappColors.Glass,
        focusColor = ZappColors.Glass,
        ring = true,
        ringCorner = 12.dp,
    ) {
        Artwork(art?.background ?: progress.background ?: progress.poster, name)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000))),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) {
                if (art?.logo != null) {
                    AsyncImage(
                        model = art.logo,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.heightIn(max = width * 0.17f).widthIn(max = width * 0.5f),
                    )
                } else {
                    Text(name, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(progress.episodeLabel?.substringBefore(","), minutesLeft(progress)).joinToString("  ·  "),
                fontSize = TextSize.Tag,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 1f), 4f)),
            )
        }
        ProgressLine(
            if (progress.duration > 1) (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f)
            else ((progress.percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                .fillMaxWidth(),
            track = Color(0x2EFFFFFF),
            thickness = 2.dp,
        )
    }
}

/**
 * A new episode of a series you follow, looking just like a Continue watching card: the show's
 * wide artwork with its logo (or name) bottom left and "New · S2 E5" bottom right.
 */
@Composable
fun NewEpisodeCard(
    meta: MetaPreview,
    label: String,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp = 220.dp,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .aspectRatio(WideAspect),
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(12.dp),
        focusScale = 1f,
        restColor = ZappColors.Glass,
        focusColor = ZappColors.Glass,
        ring = true,
        ringCorner = 12.dp,
    ) {
        Artwork(meta.background ?: meta.poster, meta.name)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xD9000000))),
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) {
                if (meta.logo != null) {
                    AsyncImage(
                        model = meta.logo,
                        contentDescription = meta.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.heightIn(max = width * 0.17f).widthIn(max = width * 0.5f),
                    )
                } else {
                    Text(meta.name, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontSize = TextSize.Tag,
                fontWeight = FontWeight.SemiBold,
                color = ZappColors.Brand2,
                maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 1f), 4f)),
            )
        }
    }
}

fun minutesLeft(p: WatchProgress): String {
    if ((p.percent ?: 0.0) < 0) return "Up next"
    if (p.duration <= 1) return "${(p.percent ?: 0.0).toInt()}% watched"
    val left = ((p.duration - p.position) / 60_000).coerceAtLeast(1)
    return if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
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
                Text(emptyTitle, fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
                Spacer(Modifier.height(8.dp))
                Text(emptyBody, color = ZappColors.Dim, fontSize = TextSize.Body, lineHeight = 19.sp)
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
                    Text(meta.name, fontSize = TextSize.Page, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                MetaFacts(meta.releaseInfo, meta.imdbRating, null, meta.genres)
                meta.description?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = ZappColors.Dim, fontSize = TextSize.Small, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A film or series title in poster lettering (Anton), large and tight. */
@Composable
fun MovieTitle(name: String, size: androidx.compose.ui.unit.TextUnit, maxLines: Int = 2, modifier: Modifier = Modifier, center: Boolean = false) {
    // Title lettering (Appearance): cinematic, clean modern, or bold condensed.
    val font = FocusStyle.titleFont
    Text(
        name,
        fontFamily = when (font) { 1 -> Inter; 2 -> TitleFont; else -> CinemaFont },
        fontWeight = when (font) { 1 -> FontWeight.SemiBold; 2 -> FontWeight.Normal; else -> FontWeight.SemiBold },
        fontSize = when (font) { 1 -> size * 0.9f; 2 -> size; else -> size * 0.86f },
        lineHeight = size * 1.0f,
        letterSpacing = when (font) { 1 -> (size.value * -0.015f).sp; 2 -> (size.value * 0.01f).sp; else -> (size.value * 0.05f).sp },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = Color.White,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
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
fun FactsLine(year: String?, rating: String?, runtime: String?, genres: List<String>, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 12.5.sp) {
    val parts = listOfNotNull(year, prettyRuntime(runtime), genres.take(3).joinToString(", ").ifBlank { null })
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(parts.joinToString("   ·   "))
        if (rating != null) {
            if (parts.isNotEmpty()) append("   ·   ")
            append("IMDb ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = ZappColors.Text, fontWeight = FontWeight.SemiBold))
            append(rating)
            pop()
        }
    }
    Text(text, color = ZappColors.Dim, fontSize = size, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

private const val ICONS = "https://cdn.jsdelivr.net/gh/selfhst/icons@df07d93a4b9e1c3930007971a1dfa6203ea02aea/png/"
/** The rating sites' logos (selfh.st/icons, CC BY 4.0; the logos belong to their sites). */
private val SCORE_LOGOS = mapOf(
    "IMDb" to ICONS + "imdb.png",
    "RT" to ICONS + "rotten-tomatoes.png",
    "Letterboxd" to ICONS + "letterboxd.png",
)

/**
 * Every score as a small card: the site's logo (or a small lettered badge) and the number. IMDb comes from the add-on when MDBList isn't connected.
 */
@Composable
fun ScoreCards(list: List<com.zapp.tv.data.RatingBadge>, imdb: String?, modifier: Modifier = Modifier) {
    val all = if (list.none { it.source == "IMDb" } && imdb != null) listOf(com.zapp.tv.data.RatingBadge("IMDb", imdb)) + list else list
    if (all.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        all.forEach { r ->
            val (mark, bg, fg) = when (r.source) {
                "IMDb" -> Triple("IMDb", Color(0xFFF5C518), Color.Black)
                "RT" -> Triple("RT", Color(0xFFE2362B), Color.White)
                "Audience" -> Triple("AUD", Color(0xFFF2A33A), Color.Black)
                "Metacritic" -> Triple("MC", Color(0xFF3A3A3A), Color.White)
                "Letterboxd" -> Triple("LB", Color(0xFF2C3440), Color.White)
                else -> Triple(r.source.take(3).uppercase(), ZappColors.GlassHigh, ZappColors.Text)
            }
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(ZappColors.Glass).padding(start = 5.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The site's own logo (from the open selfh.st icon set, loaded when shown); a small
                // lettered badge for the ones it doesn't have, or while it loads.
                val logo = SCORE_LOGOS[r.source]
                var logoFailed by remember(logo) { mutableStateOf(logo == null) }
                if (!logoFailed) {
                    coil.compose.AsyncImage(
                        model = logo,
                        contentDescription = r.source,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        onError = { logoFailed = true },
                        modifier = Modifier.height(18.dp).widthIn(max = 44.dp),
                    )
                } else {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(mark, color = fg, fontSize = TextSize.Tag, fontWeight = FontWeight.Black, letterSpacing = 0.3.sp)
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    r.value,
                    color = when (r.good) {
                        true -> Color(0xFF7EE2A8)
                        false -> Color(0xFFFF8A8A)
                        null -> ZappColors.Text
                    },
                    fontSize = TextSize.Body,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Scores from MDBList, quietly: a tiny label and the number (green or red where it means something). */
@Composable
fun RatingBadges(list: List<com.zapp.tv.data.RatingBadge>, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        list.forEach { r ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(r.source.uppercase(), color = ZappColors.Faint, fontSize = TextSize.Tag, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Spacer(Modifier.width(5.dp))
                Text(
                    r.value,
                    color = when (r.good) {
                        true -> Color(0xFF7EE2A8)
                        false -> Color(0xFFFF8A8A)
                        null -> ZappColors.Text
                    },
                    fontSize = TextSize.Small,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
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
        Text(text, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, color = if (highlight) ZappColors.Star else ZappColors.Text, maxLines = 1)
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
    top10: Boolean = false,
) {
    // Already got ready (see warmRows)? Then the row is simply there, with no loading cards.
    val items = remember(ref.key) { mutableStateListOf<MetaPreview>().apply { mvm.cachedPage(ref)?.let { addAll(it) } } }
    var loading by remember(ref.key) { mutableStateOf(items.isEmpty()) }
    var failed by remember(ref.key) { mutableStateOf(false) }
    var exhausted by remember(ref.key) { mutableStateOf(false) }
    var requestMore by remember(ref.key) { mutableStateOf(0) }
    val memory = rememberFocusMemory()
    val listState = rememberLazyListState()
    val rowStart = rememberRowStart()
    val width = cardWidth(size, landscape)

    LaunchedEffect(ref.key, requestMore) {
        if (exhausted) return@LaunchedEffect
        if (requestMore == 0 && items.isNotEmpty()) {
            loading = false
            if (!ref.def.pageable) exhausted = true
            return@LaunchedEffect
        }
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
        val numbered = top10 && !landscape
        val extra = (if (numbered) width * 1.62f else width) + 24.dp
        // With numbers beside posters, focus lands on the poster, so line the poster up (keeps "1" in view).
        RowScrolling(RowInset + extra + if (numbered) width * 0.62f else 0.dp) {
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
                if (items.isEmpty()) {
                    items(8) {
                        Box(
                            Modifier
                                .width(width)
                                .aspectRatio(if (landscape) WideAspect else 2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(shimmerBrush()),
                        )
                    }
                }
                // Things you disliked go to the back of the row.
                val disliked = mvm.ratings.byId.filterValues { it.rating == com.zapp.tv.data.Rating.Dislike }.keys
                val ordered = if (disliked.isEmpty()) items.toList() else items.sortedBy { if (it.id in disliked) 1 else 0 }
                itemsIndexed(if (top10) ordered.take(10) else ordered, key = { _, m -> m.id }) { index, meta ->
                    val requester = if (index == 0) rowStart.first else remember { FocusRequester() }
                    // An empty id means "the first card" (first start, nothing remembered yet).
                    if (pendingId == meta.id || (pendingId == "" && index == 0)) {
                        LaunchedEffect(Unit) {
                            requester.tryRequest()
                            onPendingDone()
                        }
                    }
                    val card: @Composable () -> Unit = {
                        PosterCard(
                            meta = meta,
                            modifier = Modifier
                                .rememberFocus(memory, meta.id)
                                .focusRequesterSafe(requester),
                            onFocus = {
                                rowStart.index = index
                                onFocusItem(meta)
                                if (!top10 && index >= items.size - 8 && !loading && !exhausted) requestMore++
                            },
                            onClick = { onOpen(meta) },
                            onLongClick = onLongPress?.let { f -> { f(meta) } },
                            width = width,
                            landscape = landscape,
                            showTitle = showTitles,
                            rank = if (top10 && landscape) index + 1 else null,
                        )
                    }
                    if (numbered) TopTenItem(index + 1, width, card) else card()
                }
            }
        }
    }
}

/** A Top 10 place: a tall outlined number with the poster overlapping its right side. */
@Composable
private fun TopTenItem(rank: Int, width: Dp, card: @Composable () -> Unit) {
    val h = width * 1.5f
    Box(Modifier.width(width * 1.62f).height(h)) {
        Text(
            rank.toString(),
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = TitleFont,
                fontSize = (h.value * 0.78f).sp,
                lineHeight = (h.value * 0.8f).sp,
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                color = Color(0x80FFFFFF),
                letterSpacing = (-4).sp,
            ),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 0.dp),
        )
        Box(Modifier.align(Alignment.BottomEnd)) { card() }
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
        Text(title, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
        subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontSize = TextSize.Small, color = ZappColors.Faint)
        }
    }
}

/** Thumbs down, thumbs up, and a heart, drawn to match the other line icons. */
@Composable
fun RatingGlyph(r: com.zapp.tv.data.Rating, filled: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = this.size.width
        val style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        if (r == com.zapp.tv.data.Rating.Love) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, w * 0.86f)
                cubicTo(w * 0.1f, w * 0.6f, w * 0.06f, w * 0.34f, w * 0.18f, w * 0.22f)
                cubicTo(w * 0.3f, w * 0.1f, w * 0.46f, w * 0.16f, w * 0.5f, w * 0.3f)
                cubicTo(w * 0.54f, w * 0.16f, w * 0.7f, w * 0.1f, w * 0.82f, w * 0.22f)
                cubicTo(w * 0.94f, w * 0.34f, w * 0.9f, w * 0.6f, w * 0.5f, w * 0.86f)
                close()
            }
            drawPath(p, color, style = style)
            return@Canvas
        }
        val up = r == com.zapp.tv.data.Rating.Like
        fun y(v: Float) = if (up) v * w else (1f - v) * w
        val hand = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.36f, y(0.86f))
            lineTo(w * 0.36f, y(0.46f))
            lineTo(w * 0.5f, y(0.16f))
            lineTo(w * 0.58f, y(0.12f))
            lineTo(w * 0.63f, y(0.2f))
            lineTo(w * 0.58f, y(0.4f))
            lineTo(w * 0.82f, y(0.4f))
            lineTo(w * 0.88f, y(0.48f))
            lineTo(w * 0.8f, y(0.82f))
            lineTo(w * 0.74f, y(0.86f))
            close()
        }
        drawPath(hand, color, style = style)
        val palmTop = if (up) w * 0.46f else w * 0.14f
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.12f, palmTop), androidx.compose.ui.geometry.Size(w * 0.16f, w * 0.4f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f), style = style)
    }
}

/** Dislike / Like / Love as three small buttons; pressing the current one clears it. */
@Composable
fun RatingButtons(current: com.zapp.tv.data.Rating?, size: Dp = 40.dp, first: androidx.compose.ui.focus.FocusRequester? = null, onRate: (com.zapp.tv.data.Rating?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(com.zapp.tv.data.Rating.Dislike, com.zapp.tv.data.Rating.Like, com.zapp.tv.data.Rating.Love).forEachIndexed { i, r ->
            val on = current == r
            FocusSurface(
                onClick = { onRate(if (on) null else r) },
                modifier = Modifier.size(size),
                focusRequester = if (i == 0) first else null,
                shape = RoundedCornerShape(50),
                focusScale = 1.2f,
                // Just the icon, white, with a soft shadow; no circle behind it.
                restColor = Color.Transparent,
                focusColor = Color.Transparent,
            ) { focused ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    RatingGlyph(
                        r,
                        filled = on,
                        color = Color.Black.copy(alpha = 0.6f),
                        size = size * 0.55f,
                        modifier = Modifier
                            .graphicsLayer { translationY = 1.5.dp.toPx() }
                            .blur(3.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                    )
                    RatingGlyph(
                        r,
                        filled = on || focused,
                        color = when {
                            on && r == com.zapp.tv.data.Rating.Love -> Color(0xFFFF5F8F)
                            focused || on -> Color.White
                            else -> Color.White.copy(alpha = 0.72f)
                        },
                        size = size * 0.55f,
                    )
                }
            }
        }
    }
}

/** Titles you love (for the little heart on cards), kept up to date by the app. */
object LovedMarks {
    var ids by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
}

/** Cinemeta's logo for an IMDb title (most films and series have one), used when nothing else has a logo. */
fun fallbackLogo(id: String): String? = id.substringBefore(':').takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/logo/medium/$it/img" }

/**
 * A title's logo: its own, else Cinemeta's, else [fallback] (its name in poster lettering), so
 * nearly everything gets a logo and nothing shows a broken image.
 */
@Composable
fun TitleLogo(
    id: String,
    logo: String?,
    name: String,
    modifier: Modifier,
    alignment: Alignment = Alignment.BottomStart,
    fallback: @Composable () -> Unit,
) {
    val url = logo ?: fallbackLogo(id)
    var failed by remember(url) { mutableStateOf(url == null) }
    if (failed) {
        fallback()
    } else {
        AsyncImage(
            model = url,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = alignment,
            onError = { failed = true },
            modifier = modifier,
        )
    }
}
