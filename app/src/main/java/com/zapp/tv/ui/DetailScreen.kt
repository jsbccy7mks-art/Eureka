package com.zapp.tv.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.addons.MetaDetail
import com.zapp.tv.addons.MetaPreview
import com.zapp.tv.addons.Video
import com.zapp.tv.data.WatchProgress

data class DetailRequest(val type: String, val id: String, val preview: MetaPreview?)

/** What to play: a film (video == null) or one episode, and where to start. */
data class PlayRequest(
    val meta: MetaPreview,
    val video: Video?,
    val videos: List<Video>,
    val resumeMs: Long,
    val autoPlay: Boolean,
    val preferBingeGroup: String? = null,
    /** Where to start when only a percentage is known (synced from Simkl). */
    val resumePercent: Double? = null,
    /** Sources that already failed for this title, skipped by autoplay. */
    val failed: Set<String> = emptySet(),
) {
    val videoId: String get() = video?.id ?: meta.id
    val episodeLabel: String? get() = video?.let { episodeLabel(it) }
}

fun episodeLabel(v: Video): String =
    if (v.season != null && v.episode != null) "S${v.season} E${v.episode}" + (v.title.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
    else v.title

fun shortLabel(v: Video): String =
    if (v.season != null && v.episode != null) "S${v.season} E${v.episode}" else v.title


private fun runtimeMinutes(runtime: String?): Int? =
    runtime?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it in 1..600 }

/** A small square action (library, sources, start over…), outlined until focused. */
@Composable
private fun IconAction(symbol: String, onClick: () -> Unit, highlight: Boolean = false) {
    var f by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .border(1.dp, if (f) Color.Transparent else if (highlight) ZappColors.Brand2.copy(alpha = 0.7f) else Color(0x2EFFFFFF), RoundedCornerShape(12.dp)),
        onFocusChange = { f = it },
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.06f,
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, color = if (focused) ZappColors.OnFocus else ZappColors.Text)
        }
    }
}

/**
 * Details: three calm "pages" you move down through.
 * 1. The title: artwork, poster lettering, a one-line tagline, facts, and the actions along the bottom.
 * 2. Episodes (series): the episode you're on fills the page; a season tab, a thread joining the
 *    episodes, and cards that grow when chosen.
 * 3. Cast and credits, then more like this, under a slim header that keeps the title in view.
 */
@Composable
fun DetailScreen(
    mvm: MediaViewModel,
    request: DetailRequest,
    focusTick: Int,
    onPlay: (PlayRequest) -> Unit,
    onClose: () -> Unit,
    onOpenTitle: (MetaPreview) -> Unit = {},
    /** Search for someone in the cast (OK on their photo). */
    onSearchPerson: (String) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val loaded by produceState<Pair<Boolean, MetaDetail?>>(false to null, request.id) {
        value = true to mvm.meta(request.type, request.id)
    }
    val (done, detail) = loaded
    val meta = detail?.preview ?: request.preview
    // Optional extras from TMDB (with your own key): photos, rating, reviews, recommendations.
    val tmdb by produceState<com.zapp.tv.data.TmdbDetails?>(null, request.id) { value = runCatching { mvm.tmdbDetails(request.id, request.type) }.getOrNull() }
    val progress by remember(request.id) { mvm.progressFor(request.id) }.collectAsState(initial = emptyList<WatchProgress>())
    val inLibrary by remember(request.id) { mvm.inLibrary(request.id) }.collectAsState(initial = false)
    // How well it fits your taste (from your ratings).
    val match by produceState<Int?>(null, request.id, mvm.tasteVersion) { value = mvm.matchPercent(request.id, request.type) }
    // Scores from MDBList (IMDb, Rotten Tomatoes, Metacritic, Letterboxd), when connected.
    val ratings by produceState(emptyList<com.zapp.tv.data.RatingBadge>(), request.id) { value = mvm.ratings(request.id, request.type) }
    val playFocus = remember { FocusRequester() }
    val now = rememberNow(30_000)
    val timeFormat = rememberTimeFormat()
    var trailerOpen by remember(request.id) { mutableStateOf(false) }
    // The trailer starts while you rest on Play, and stops at any press (resting again restarts it).
    var playFocused by remember(request.id) { mutableStateOf(false) }
    var keyTick by remember(request.id) { mutableIntStateOf(0) }
    var trailerShowing by remember(request.id) { mutableStateOf(false) }
    // Like the Home header: once the trailer is really playing, the page steps aside for it.
    var breathe by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(trailerShowing) {
        breathe = false
        if (trailerShowing) {
            kotlinx.coroutines.delay(600)
            breathe = true
        }
    }
    val textAlpha by androidx.compose.animation.core.animateFloatAsState(if (breathe) 0f else 1f, androidx.compose.animation.core.tween(if (breathe) 700 else 250), label = "detailText")
    val logoAlpha by androidx.compose.animation.core.animateFloatAsState(if (breathe) 1f else 0f, androidx.compose.animation.core.tween(if (breathe) 700 else 200), label = "detailLogo")
    // The trailer stays where it is on details pages (only Home's header moves it to the middle).
    val grow = 0f
    // Have the trailer's streams ready before Trailer is pressed (or Play rested on).
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(detail?.trailerYoutube) {
        detail?.trailerYoutube?.let { (appContext as com.zapp.tv.ZappApp).youtube.prefetch(it, trailerHeightCap()) }
    }

    LaunchedEffect(done, focusTick) { if (done) playFocus.tryRequest() }

    val isSeries = request.type == "series" && !detail?.videos.isNullOrEmpty()
    val videos = detail?.videos.orEmpty()
    val nextUp = remember(videos, progress) { nextUp(videos, progress) }
    val seasons = remember(videos) { seasonsOf(videos) }
    var season by remember(request.id, nextUp.first?.season) { mutableStateOf(nextUp.first?.season ?: seasons.firstOrNull()) }
    var focusedEpisode by remember(request.id) { mutableStateOf<Video?>(null) }
    var section by remember(request.id) { mutableIntStateOf(0) } // 0 title, 1 episodes or story, 2 cast, 3 more
    var episodeMenu by remember(request.id) { mutableStateOf<Video?>(null) }
    // Spoiler shield: episodes whose story you've chosen to see anyway.
    var revealed by remember(request.id) { mutableStateOf(setOf<String>()) }
    fun shielded(v: Video): Boolean {
        if (!mvm.settings.spoilerShield || v.id in revealed) return false
        val p = progress.firstOrNull { it.videoId == v.id }
        return p == null || (!p.watched && p.position < 60_000)
    }

    // Get ready for Play while you read: fetch the sources for what Play would start (the film, or
    // the next episode), then, after a few seconds here, get its debrid link ready too.
    val playTarget = if (request.type == "series") nextUp.first?.id else request.id
    var sources by remember(request.id) { mutableStateOf<MediaViewModel.Prefetch?>(null) }
    LaunchedEffect(playTarget, done) {
        val target = playTarget ?: return@LaunchedEffect
        if (!done) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        sources = mvm.prefetchStreams(request.type, target)
        kotlinx.coroutines.delay(3_000)
        mvm.prepareLink(request.type, target, request.id, detail?.preview?.name ?: request.preview?.name.orEmpty())
    }

    fun playEpisode(v: Video) {
        val p = progress.firstOrNull { it.videoId == v.id }
        val resume = if (p != null && !p.watched) p.position else 0L
        val m = meta ?: return
        onPlay(PlayRequest(m, v, videos, resume, mvm.settings.autoPlayFirst, resumePercent = p?.takeIf { !it.watched }?.percent))
    }

    Box(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                keyTick++
                false
            }
            .background(ZappColors.Bg),
    ) {
        // ---- a blurred wash of the title's colours across the whole page
        BlurredBackdrop(meta?.background ?: meta?.poster, dim = 0.5f)
        // ---- the picture behind everything: the title, or the episode you're on
        val backdrop = when {
            section == 1 && focusedEpisode?.thumbnail != null -> focusedEpisode?.thumbnail
            else -> meta?.background ?: meta?.poster
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.78f)
                .fillMaxHeight(if (section == 1) 0.72f else 0.9f)
                // While the trailer plays it glides to the middle of the screen (moved and scaled
                // only, never re-laid out, so it stays smooth).
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    val k = 1f + 0.08f * grow
                    scaleX = k
                    scaleY = k
                    translationX = -(size.width / 0.78f - size.width) / 2f * grow
                }
                .clipToBounds()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // A long soft fade on the left at rest; just a slight one on each side while playing.
                    drawRect(Brush.horizontalGradient(*easedMask(start = 0f, end = 0.55f - 0.33f * grow)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    if (grow > 0f) {
                        drawRect(Brush.horizontalGradient(*easedMask(start = 1f, end = 1f - 0.22f * grow)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        drawRect(Brush.verticalGradient(*easedMask(start = 0f, end = 0.1f * grow)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    }
                    drawRect(Brush.verticalGradient(*easedMask(start = 1f, end = 0.5f)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
        ) {
            androidx.compose.animation.Crossfade(targetState = backdrop, animationSpec = androidx.compose.animation.core.tween(600), label = "detailArt", modifier = Modifier.fillMaxSize()) { u ->
                if (u != null) {
                    AsyncImage(model = u, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().drift())
                }
            }
            if (section == 0) {
                DelayedTrailer(
                    detail?.trailerYoutube,
                    "${request.id}|$keyTick",
                    Modifier.fillMaxSize(),
                    onDetail = true,
                    enabled = playFocused && !trailerOpen,
                    onShowing = { trailerShowing = it },
                )
            }
        }
        AmbientGlow(0.6f, tint = rememberArtColor(meta?.poster ?: meta?.background))

        if (!done && meta == null) {
            // The page's shape while it loads: logo, facts, story and buttons, shimmering.
            DetailSkeleton(Modifier.align(Alignment.BottomStart))
            return@Box
        }
        if (meta == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load this title", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("None of your add-ons returned details for it.", color = ZappColors.Dim, fontSize = TextSize.Body)
                Spacer(Modifier.height(14.dp))
                ZButton("Back", onClose, focusRequester = playFocus)
            }
            return@Box
        }

        val movieProgress = progress.firstOrNull { it.videoId == meta.id && !it.watched && (it.position > 30_000 || (it.percent ?: 0.0) > 1) }
        val minutes = runtimeMinutes(detail?.runtime)
        val endsAt: Long? = when {
            movieProgress != null && movieProgress.duration > 0 -> now + (movieProgress.duration - movieProgress.position)
            minutes != null -> now + minutes * 60_000L
            else -> null
        }
        val playLabel = if (isSeries) {
            val (v, resume) = nextUp
            when {
                v == null -> "Play"
                resume > 0 -> "Resume ${shortLabel(v)}"
                else -> "Play ${shortLabel(v)}"
            }
        } else if (movieProgress != null) "Resume" else "Play"
        fun playMain() {
            if (isSeries) {
                nextUp.first?.let { playEpisode(it) }
            } else {
                onPlay(PlayRequest(meta, null, emptyList(), movieProgress?.position ?: 0L, mvm.settings.autoPlayFirst, resumePercent = movieProgress?.percent))
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            val scroll = remember(request.id) { androidx.compose.foundation.ScrollState(0) }
            val scope = rememberCoroutineScope()
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { screenHeight.toPx() }.toInt()
            fun goTo(sec: Int) {
                section = sec
                scope.launch {
                    scroll.animateScrollTo(
                        when (sec) {
                            0 -> 0
                            1 -> px
                            2 -> (2 * px).coerceAtMost(scroll.maxValue)
                            else -> scroll.maxValue
                        },
                        androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    )
                }
            }
            val dim by androidx.compose.animation.core.animateFloatAsState(if (section >= 2) 0.88f else 0f, androidx.compose.animation.core.tween(500), label = "dim")
            Box(Modifier.fillMaxSize().background(ZappColors.Bg.copy(alpha = dim)))

            NoAutoScroll {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    // ================================================= 1. the title
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(screenHeight)
                            .onFocusChanged { if (it.hasFocus && section != 0) goTo(0) },
                    ) {
                        // While the trailer plays: just the title's logo, top left.
                        if (logoAlpha > 0f) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 56.dp, top = 34.dp)
                                    .graphicsLayer { alpha = logoAlpha },
                            ) {
                                TitleLogo(meta.id, meta.logo, meta.name, Modifier.height(72.dp).widthIn(max = 340.dp), alignment = Alignment.TopStart) {
                                    MovieTitle(meta.name, if (meta.name.length > 24) 24.sp else 30.sp, maxLines = 1)
                                }
                            }
                        }
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 48.dp, bottom = 30.dp)
                                .graphicsLayer { alpha = textAlpha },
                        ) {
                            val credit = when {
                                tmdb?.createdBy?.isNotEmpty() == true -> "CREATED BY " + tmdb!!.createdBy.take(2).joinToString(", ").uppercase()
                                isSeries && detail?.writers?.isNotEmpty() == true -> "CREATED BY " + detail.writers.first().uppercase()
                                detail?.directors?.isNotEmpty() == true -> "DIRECTED BY " + detail.directors.take(2).joinToString(", ").uppercase()
                                else -> null
                            }
                            Text(
                                listOfNotNull(tmdb?.network?.uppercase() ?: if (isSeries) "SERIES" else "FILM", credit).joinToString("   ·   "),
                                color = ZappColors.Brand2.copy(alpha = 0.9f),
                                fontSize = TextSize.Label,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 2.2.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                            // The title's own logo when there is one, otherwise its name in poster lettering.
                            TitleLogo(meta.id, meta.logo, meta.name, Modifier.heightIn(max = 120.dp).widthIn(max = 460.dp)) {
                                MovieTitle(meta.name, if (meta.name.length > 24) 38.sp else 48.sp, maxLines = 2, modifier = Modifier.widthIn(max = 600.dp))
                            }
                            // Tagline: the first sentence of the story, after a short accent line.
                            val tagline = tmdb?.tagline ?: meta.description?.let { d -> d.split(Regex("(?<=[.!?])\\s")).firstOrNull()?.trim() }?.takeIf { it.length in 8..140 }
                            if (tagline != null) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .width(26.dp)
                                            .height(2.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(tagline, fontSize = TextSize.Heading, fontWeight = FontWeight.Medium, color = ZappColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            tmdb?.certification?.let { c ->
                                Text(
                                    c,
                                    fontSize = TextSize.Label,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZappColors.Text,
                                    modifier = Modifier
                                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            FactsLine(
                                meta.releaseInfo,
                                meta.imdbRating,
                                if (isSeries) (seasons.count { it > 0 }.takeIf { it > 0 }?.let { "$it season" + if (it > 1) "s" else "" }) else detail?.runtime,
                                meta.genres,
                                size = 12.sp,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // The best picture and sound on offer, from the sources found for it.
                            val all = sources?.batches?.flatMap { it.streams }.orEmpty()
                            val best = all.maxOfOrNull { it.qualityRank } ?: 0
                            val top = all.filter { it.qualityRank == best }
                            val av = buildList {
                                if (best >= 2160) add("4K")
                                if (top.any { "Dolby Vision" in it.tags }) add("DOLBY VISION") else if (top.any { "HDR" in it.tags }) add("HDR")
                                if (all.any { "Atmos" in it.tags }) add("ATMOS")
                            }
                            av.forEach { b ->
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    b,
                                    fontSize = TextSize.Tag,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp,
                                    color = ZappColors.Text,
                                    modifier = Modifier
                                        .background(Color(0x1FFFFFFF), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            }
                            // Just IMDb up here (in the line above); every score is on the next page.
                            match?.let { m ->
                                Spacer(Modifier.height(8.dp))
                                Text("$m% match", color = if (m >= 70) Color(0xFF7EE2A8) else if (m < 40) Color(0xFFFF8A8A) else ZappColors.Text, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(26.dp))
                            Text(
                                "↓   " + if (isSeries) "EPISODES, CAST AND MORE" else "STORY, CAST AND MORE",
                                color = ZappColors.Faint,
                                fontSize = TextSize.Tag,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 2.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.onFocusChanged { playFocused = it.hasFocus }) {
                                    PlayButton(playLabel, if (isSeries) progress.firstOrNull { it.videoId == nextUp.first?.id && !it.watched && it.position > 30_000 } else movieProgress, playFocus) { playMain() }
                                }
                                Spacer(Modifier.width(8.dp))
                                if (detail?.trailerYoutube != null) {
                                    ZButton("Trailer", { trailerOpen = true })
                                    Spacer(Modifier.width(8.dp))
                                }
                                IconAction(if (inLibrary) "✓" else "+", { mvm.toggleLibrary(meta, inLibrary) }, highlight = inLibrary)
                                Spacer(Modifier.width(8.dp))
                                if (!isSeries && movieProgress != null) {
                                    IconAction("↺", { onPlay(PlayRequest(meta, null, emptyList(), 0, mvm.settings.autoPlayFirst)) })
                                    Spacer(Modifier.width(8.dp))
                                }
                                IconAction("⋯", {
                                    if (isSeries) {
                                        nextUp.first?.let { v -> onPlay(PlayRequest(meta, v, videos, nextUp.second, autoPlay = false)) }
                                    } else {
                                        onPlay(PlayRequest(meta, null, emptyList(), movieProgress?.position ?: 0L, autoPlay = false))
                                    }
                                })
                                if (endsAt != null && !isSeries) {
                                    Spacer(Modifier.width(16.dp))
                                    Text("Ends at ${timeFormat.format(endsAt)}", color = ZappColors.Dim, fontSize = TextSize.Small, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.weight(1f))
                                // A few of the cast, quietly, on the right.
                                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    (tmdb?.cast?.map { it.name } ?: detail?.people.orEmpty().map { it.name }).take(2).forEach { name ->
                                        Text(name.uppercase(), color = ZappColors.Dim, fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, maxLines = 1)
                                    }
                                }
                            }
                            if (!done) {
                                Spacer(Modifier.height(8.dp))
                                Text("Loading details…", color = ZappColors.Faint, fontSize = TextSize.Small)
                            }
                        }
                    }

                    // ================================================= 2. episodes
                    if (isSeries) {
                        val episodes = videos.filter { season == null || it.season == season }
                        // Each episode's rating (TMDB), shown small on its card.
                        val epRatings by produceState(emptyMap<Int, Double>(), request.id, season) {
                            value = season?.takeIf { it > 0 }?.let { mvm.episodeRatings(request.id, it) } ?: emptyMap()
                        }
                        val shown = focusedEpisode?.takeIf { it.season == season } ?: nextUp.first?.takeIf { it.season == season } ?: episodes.firstOrNull()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(screenHeight)
                                .onFocusChanged { if (it.hasFocus && section != 1) goTo(1) },
                        ) {
                            // The episode you're on.
                            Column(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp).widthIn(max = 600.dp)) {
                                if (shown != null) {
                                    Text(
                                        "SEASON ${shown.season ?: 0}   ·   EPISODE ${shown.episode ?: 0}",
                                        color = ZappColors.Brand2.copy(alpha = 0.9f),
                                        fontSize = TextSize.Label,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 2.2.sp,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(shown.title.ifBlank { "Episode ${shown.episode}" }, fontSize = TextSize.Hero, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    shown.released?.take(10)?.let {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it, color = ZappColors.Dim, fontSize = TextSize.Small)
                                    }
                                    shown.overview?.let {
                                        Spacer(Modifier.height(8.dp))
                                        if (shielded(shown)) {
                                            Text("Story hidden by the spoiler shield  ·  hold OK on the episode to show it", color = ZappColors.Faint, fontSize = TextSize.Small)
                                        } else {
                                            Text(it, color = Color(0xFFC9CCD6), fontSize = TextSize.Small, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 26.dp)) {
                                // Season tabs.
                                if (seasons.size > 1) {
                                    // Seasons as tiles: a still from the season, its name, and how much you've watched.
                                    MinimalScrolling {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 56.dp)) {
                                            items(seasons) { sn ->
                                                val eps = videos.filter { it.season == sn }
                                                val seen = eps.count { v -> progress.any { it.videoId == v.id && it.watched } }
                                                val still = eps.firstOrNull { it.thumbnail != null }?.thumbnail ?: meta.background
                                                FocusSurface(
                                                    onClick = { season = sn },
                                                    modifier = Modifier.width(132.dp).height(62.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                    focusScale = 1f,
                                                    restColor = ZappColors.Glass,
                                                    focusColor = ZappColors.Glass,
                                                    ring = true,
                                                ) { focused ->
                                                    Artwork(still, "")
                                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (sn == season || focused) 0.35f else 0.62f)))
                                                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 9.dp, vertical = 6.dp)) {
                                                        Text(seasonName(sn), fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (sn == season || focused) 1f else 0.75f), maxLines = 1)
                                                        Text(
                                                            if (seen == 0) "${eps.size} episodes" else if (seen >= eps.size) "Watched" else "$seen of ${eps.size} watched",
                                                            fontSize = TextSize.Tag,
                                                            color = Color.White.copy(alpha = 0.7f),
                                                            maxLines = 1,
                                                        )
                                                    }
                                                    if (eps.isNotEmpty() && seen > 0) {
                                                        ProgressLine((seen.toFloat() / eps.size).coerceIn(0f, 1f), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp), track = Color(0x33FFFFFF))
                                                    }
                                                    if (sn == season) {
                                                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(ZappColors.Brand2))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                } else {
                                    Text(
                                        (season?.let { seasonName(it) } ?: "Episodes") + "  ·  ${episodes.size} episodes",
                                        fontSize = TextSize.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 56.dp, bottom = 10.dp),
                                    )
                                }
                                // The thread: one dot per episode; watched ones filled, the one you're on bright.
                                EpisodeThread(
                                    count = episodes.size,
                                    focusedIndex = episodes.indexOf(shown).coerceAtLeast(0),
                                    watched = episodes.map { v -> progress.any { it.videoId == v.id && it.watched } },
                                    modifier = Modifier.padding(horizontal = 56.dp).fillMaxWidth().height(18.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                val memory = rememberFocusMemory()
                                PivotScrolling(56.dp) {
                                    LazyRow(
                                        modifier = Modifier.restoreFocus(memory),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(horizontal = 56.dp),
                                        verticalAlignment = Alignment.Bottom,
                                    ) {
                                        items(episodes, key = { it.id }) { v ->
                                            val p = progress.firstOrNull { it.videoId == v.id }
                                            EpisodeCard(
                                                v,
                                                p,
                                                Modifier.rememberFocus(memory, v.id),
                                                upNext = v.id == nextUp.first?.id,
                                                onFocus = { focusedEpisode = v },
                                                rating = v.episode?.let { epRatings[it] },
                                                shield = shielded(v),
                                                onLongClick = { episodeMenu = v },
                                            ) { playEpisode(v) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ================================================= 2. the story (films)
                    if (!isSeries) {
                        StorySection(
                            mvm = mvm,
                            meta = meta,
                            detail = detail,
                            tmdb = tmdb,
                            ratings = ratings,
                            watched = meta.id in WatchedMarks.ids,
                            playLabel = playLabel,
                            onPlay = { playMain() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(screenHeight)
                                .onFocusChanged { if (it.hasFocus && section != 1) goTo(1) },
                        )
                    }
                    // ================================================= 3. cast, credits and reviews
                    CastSection(
                        onPerson = onSearchPerson,
                        meta = meta,
                        detail = detail,
                        tmdb = tmdb,
                        ratings = ratings,
                        mvm = mvm,
                        modifier = Modifier
                            .heightIn(min = screenHeight)
                            .onFocusChanged { if (it.hasFocus && section != 2) goTo(2) },
                    )
                    // ================================================= 4. more with, people also watched, more like this
                    MoreSection(
                        mvm = mvm,
                        meta = meta,
                        tmdb = tmdb,
                        onOpen = onOpenTitle,
                        modifier = Modifier.onFocusChanged { if (it.hasFocus && section != 3) goTo(3) },
                    )
                }
            }

            // Slim header over the last page, so you always know where you are.
            androidx.compose.animation.AnimatedVisibility(
                visible = section >= 2,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(ZappColors.Bg, ZappColors.Bg.copy(alpha = 0.92f), Color.Transparent)))
                        .padding(start = 56.dp, end = 48.dp, top = 22.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (meta.logo != null) {
                        AsyncImage(model = meta.logo, contentDescription = meta.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.heightIn(max = 34.dp).widthIn(max = 200.dp))
                    } else {
                        MovieTitle(meta.name, 22.sp, maxLines = 1, modifier = Modifier.widthIn(max = 360.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (isSeries) "${seasons.count { it > 0 }} seasons  ·  ${videos.size} episodes" else listOfNotNull(meta.releaseInfo, prettyRuntime(detail?.runtime)).joinToString("  ·  "),
                        color = ZappColors.Dim,
                        fontSize = TextSize.Small,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Up to go back", color = ZappColors.Faint, fontSize = TextSize.Small)
                }
            }
        }

        episodeMenu?.let { v ->
            EpisodeMenu(
                mvm = mvm,
                meta = meta,
                episode = v,
                videos = videos,
                progress = progress,
                onPlayFromStart = {
                    onPlay(PlayRequest(meta, v, videos, 0, mvm.settings.autoPlayFirst))
                },
                onReveal = if (shielded(v)) ({ revealed = revealed + v.id }) else null,
                onClose = { episodeMenu = null },
            )
        }

        // Trailer with sound, full screen.
        if (trailerOpen && detail?.trailerYoutube != null) {
            FullTrailer(detail.trailerYoutube) {
                trailerOpen = false
                playFocus.tryRequest()
            }
        }
    }
}

/** Hold OK on an episode: watched marks, including everything before it, and play from the start. */
@Composable
private fun EpisodeMenu(
    mvm: MediaViewModel,
    meta: MetaPreview,
    episode: Video,
    videos: List<Video>,
    progress: List<WatchProgress>,
    onPlayFromStart: () -> Unit,
    onReveal: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        first.tryRequest()
    }
    val p = progress.firstOrNull { it.videoId == episode.id }
    val watched = p?.watched == true
    // Everything before this one, specials aside.
    val regular = videos.filter { (it.season ?: 0) > 0 }
    val idx = regular.indexOfFirst { it.id == episode.id }
    val before = if (idx > 0) regular.take(idx) else emptyList()
    val unwatchedBefore = before.count { v -> progress.none { it.videoId == v.id && it.watched } }
    val seasonEps = videos.filter { it.season == episode.season }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        DialogBlurBehind()
        Column(
            Modifier
                .width(440.dp)
                .clip(Shapes.Panel)
                .background(ZappColors.Panel)
                .padding(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp)) {
                Text(episode.title.ifBlank { "Episode ${episode.episode}" }, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text("${meta.name}  ·  ${shortLabel(episode)}", color = ZappColors.Faint, fontSize = TextSize.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            MenuItem(if (watched) "○" else "◉", if (watched) "Mark as unwatched" else "Mark as watched", first) {
                mvm.setEpisodesWatched(meta, listOf(episode), !watched)
                onClose()
            }
            onReveal?.let { reveal ->
                MenuItem("◐", "Show its picture and story", null) {
                    reveal()
                    onClose()
                }
            }
            if (before.isNotEmpty()) {
                MenuItem(
                    "⇤",
                    if (unwatchedBefore > 0) "Mark previous as watched" else "Previous episodes are watched",
                    null,
                    trailing = if (unwatchedBefore > 0) "$unwatchedBefore episode" + if (unwatchedBefore == 1) "" else "s" else null,
                ) {
                    if (unwatchedBefore > 0) mvm.setEpisodesWatched(meta, before, true)
                    onClose()
                }
            }
            if (seasonEps.size > 1 && episode.season != null) {
                val seasonWatched = seasonEps.all { v -> progress.any { it.videoId == v.id && it.watched } }
                MenuItem("☰", if (seasonWatched) "Mark ${seasonName(episode.season)} as unwatched" else "Mark all of ${seasonName(episode.season)} as watched", null) {
                    mvm.setEpisodesWatched(meta, seasonEps, !seasonWatched)
                    onClose()
                }
            }
            if (p != null && !p.watched && p.position > 30_000) {
                MenuItem("↺", "Play from the start", null) {
                    onClose()
                    onPlayFromStart()
                }
            }
        }
    }
}

/** Page two for films: the whole story, with the key facts beside it. */
@Composable
private fun StorySection(
    mvm: MediaViewModel,
    meta: MetaPreview,
    detail: MetaDetail?,
    tmdb: com.zapp.tv.data.TmdbDetails?,
    ratings: List<com.zapp.tv.data.RatingBadge>,
    watched: Boolean,
    playLabel: String,
    onPlay: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 56.dp, end = 56.dp, bottom = 40.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1.6f)) {
                Text("THE STORY", color = ZappColors.Brand2.copy(alpha = 0.9f), fontSize = TextSize.Label, fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp)
                Spacer(Modifier.height(12.dp))
                tmdb?.tagline?.let {
                    Text(it, fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, color = ZappColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    meta.description?.takeIf { it.isNotBlank() } ?: "No synopsis yet for this film.",
                    fontSize = TextSize.Heading,
                    lineHeight = 24.sp,
                    color = Color(0xFFD6D9E2),
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ZButton(playLabel, onPlay, style = ButtonStyle.Primary)
                    ZButton(if (watched) "Mark as unwatched" else "Mark as watched", { mvm.setWatched(meta, !watched) })
                    Spacer(Modifier.width(10.dp))
                    RatingButtons(mvm.ratingOf(meta.id), size = 40.dp) { r -> mvm.rate(meta, r) }
                }
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                CreditLine("Directed by", detail?.directors?.take(2)?.joinToString(", ")?.ifBlank { null })
                CreditLine("Starring", (tmdb?.cast?.map { it.name } ?: detail?.people?.map { it.name } ?: detail?.cast).orEmpty().take(3).joinToString(", ").ifBlank { null })
                CreditLine("Genres", meta.genres.take(3).joinToString(", ").ifBlank { null })
                CreditLine("Released", detail?.released?.take(10) ?: meta.releaseInfo)
                CreditLine("Runtime", prettyRuntime(detail?.runtime))
                CreditLine("Rated", tmdb?.certification)
                if (ratings.isNotEmpty() || meta.imdbRating != null) {
                    Spacer(Modifier.height(10.dp))
                    ScoreCards(ratings, meta.imdbRating)
                }
            }
        }
    }
}

/** A line joining a season's episodes: watched ones filled, the one you're on larger and bright. */
@Composable
private fun EpisodeThread(count: Int, focusedIndex: Int, watched: List<Boolean>, modifier: Modifier) {
    if (count <= 0) return
    val a = ZappColors.Brand1
    val b = ZappColors.Brand2
    val faint = ZappColors.Line
    androidx.compose.foundation.Canvas(modifier) {
        val y = size.height / 2
        val n = count.coerceAtMost(40)
        val step = if (n > 1) size.width / (n - 1) else 0f
        drawLine(faint, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2f)
        val lastWatched = watched.take(n).indexOfLast { it }
        if (lastWatched > 0) {
            drawLine(Brush.horizontalGradient(listOf(a, b)), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(step * lastWatched, y), strokeWidth = 3f)
        }
        for (i in 0 until n) {
            val x = if (n > 1) step * i else 0f
            val c = androidx.compose.ui.geometry.Offset(x, y)
            when {
                i == focusedIndex -> {
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = c)
                    drawCircle(ZappColors.Bg, radius = 4.dp.toPx(), center = c)
                }
                watched.getOrNull(i) == true -> drawCircle(b, radius = 4.dp.toPx(), center = c)
                else -> {
                    drawCircle(ZappColors.Bg, radius = 4.5.dp.toPx(), center = c)
                    drawCircle(Color(0x88FFFFFF), radius = 4.5.dp.toPx(), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/** Page three: the cast (with photos when there are any) beside the credits, then reviews. */
@Composable
private fun CastSection(
    meta: MetaPreview,
    detail: MetaDetail?,
    tmdb: com.zapp.tv.data.TmdbDetails?,
    ratings: List<com.zapp.tv.data.RatingBadge> = emptyList(),
    modifier: Modifier = Modifier,
    mvm: MediaViewModel? = null,
    onPerson: (String) -> Unit = {},
) {
    val people = tmdb?.cast?.takeIf { it.isNotEmpty() }?.map { com.zapp.tv.addons.Person(it.name, it.role, it.photo) } ?: detail?.people.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 86.dp, bottom = 30.dp),
    ) {
        Row(Modifier.padding(horizontal = 56.dp)) {
            Column(Modifier.weight(1.5f)) {
                if (people.isNotEmpty()) {
                    Text("Cast", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    val memory = rememberFocusMemory()
                    MinimalScrolling {
                        LazyRow(modifier = Modifier.restoreFocus(memory), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(people, key = { it.name }) { person ->
                                // Round photos, with who they play underneath.
                                Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    FocusSurface(
                                        onClick = { onPerson(person.name) },
                                        modifier = Modifier
                                            .rememberFocus(memory, person.name)
                                            .size(84.dp),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        focusScale = 1.06f,
                                        restColor = ZappColors.Glass,
                                        focusColor = ZappColors.Glass,
                                        ring = true,
                                        ringCorner = 42.dp,
                                    ) {
                                        if (person.photo != null) {
                                            AsyncImage(model = person.photo, contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        } else {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(person.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), fontFamily = TitleFont, fontSize = TextSize.Page, color = ZappColors.Dim)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(person.name, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    person.role?.let { Text(it, fontSize = TextSize.Label, color = ZappColors.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                // Series: rate it here (films have the buttons with the story).
                if (meta.type == "series" && mvm != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your rating", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(12.dp))
                        RatingButtons(mvm.ratingOf(meta.id), size = 38.dp) { r -> mvm.rate(meta, r) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text("Credits", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val creators = tmdb?.createdBy?.takeIf { it.isNotEmpty() } ?: if (meta.type == "series") detail?.writers else null
                CreditLine("Created by", creators?.take(2)?.joinToString(", ")?.ifBlank { null })
                CreditLine("Network", tmdb?.network?.takeIf { meta.type == "series" })
                CreditLine("Directed by", detail?.directors?.take(2)?.joinToString(", ")?.ifBlank { null })
                CreditLine("Written by", (tmdb?.writers?.takeIf { it.isNotEmpty() } ?: detail?.writers?.takeIf { meta.type != "series" })?.take(2)?.joinToString(", ")?.ifBlank { null })
                CreditLine("Music by", tmdb?.music?.take(2)?.joinToString(", ")?.ifBlank { null })
                CreditLine("Released", detail?.released?.take(10) ?: meta.releaseInfo)
                CreditLine("Runtime", prettyRuntime(detail?.runtime))
                CreditLine("Rated", tmdb?.certification)
                CreditLine("Country", detail?.country)
                CreditLine("Awards", detail?.awards)
                // Series show every score here (films have them with the story).
                if (meta.type == "series" && (ratings.isNotEmpty() || meta.imdbRating != null)) {
                    Spacer(Modifier.height(10.dp))
                    ScoreCards(ratings, meta.imdbRating)
                }
            }
        }

        val reviews = tmdb?.reviews.orEmpty()
        if (reviews.isNotEmpty()) {
            Spacer(Modifier.height(30.dp))
            Text("Reviews", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
            val memory = rememberFocusMemory()
            PivotScrolling(56.dp) {
                LazyRow(
                    modifier = Modifier.restoreFocus(memory),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(horizontal = 56.dp),
                ) {
                    items(reviews.size) { i -> ReviewCard(reviews[i], Modifier.rememberFocus(memory, i)) }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(r: com.zapp.tv.data.TmdbReview, modifier: Modifier) {
    FocusSurface(
        onClick = {},
        modifier = modifier
            .width(330.dp)
            .height(160.dp)
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        focusScale = 1f,
        restColor = ZappColors.Glass,
        focusColor = ZappColors.GlassHigh,
        ring = true,
        ringCorner = 16.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                r.rating?.let { rating ->
                    val stars = (rating / 2).coerceIn(0.0, 5.0)
                    Text("★".repeat(stars.toInt()) + "☆".repeat(5 - stars.toInt()), color = ZappColors.Star, fontSize = TextSize.Small, letterSpacing = 1.sp)
                    Spacer(Modifier.width(10.dp))
                }
                Text("TMDB", color = ZappColors.Faint, fontSize = TextSize.Tag, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                Spacer(Modifier.weight(1f))
                Text("“", fontFamily = TitleFont, fontSize = TextSize.Page, color = ZappColors.Brand2.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(6.dp))
            Text(r.text, color = ZappColors.Text, fontSize = TextSize.Small, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(r.author, color = ZappColors.Dim, fontSize = TextSize.Small, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Page four: more with the lead actor, what people also watched, and more like this. */
@Composable
private fun MoreSection(
    mvm: MediaViewModel,
    meta: MetaPreview,
    tmdb: com.zapp.tv.data.TmdbDetails?,
    onOpen: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val similar by produceState(emptyList<MetaPreview>(), meta.id) {
        val genres = meta.genres.map { it.lowercase() }.toSet()
        val found = LinkedHashMap<String, MetaPreview>()
        for (ref in mvm.catalogs(meta.type).take(8)) {
            val page = runCatching { mvm.page(ref, 0) }.getOrNull() ?: continue
            for (m in page) {
                if (m.id == meta.id || found.containsKey(m.id)) continue
                if (genres.isEmpty() || m.genres.any { it.lowercase() in genres }) found[m.id] = m
            }
            value = found.values.take(20)
            if (found.size >= 20) break
        }
    }
    val lead = tmdb?.cast?.firstOrNull()
    val leadWork by produceState(emptyList<com.zapp.tv.data.TmdbItem>(), lead?.id) {
        value = if (lead != null && tmdb != null) runCatching { mvm.tmdbPersonWork(lead.id, tmdb.tmdbId) }.getOrDefault(emptyList()) else emptyList()
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun openTmdb(item: com.zapp.tv.data.TmdbItem) {
        scope.launch {
            val m = mvm.tmdbToPreview(item)
            if (m != null) onOpen(m) else android.widget.Toast.makeText(context, "Couldn't find ${item.name} in your add-ons", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 90.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        var menuFor by remember { mutableStateOf<MetaPreview?>(null) }
        menuFor?.let { mm -> TitleMenu(mvm, mm) { menuFor = null } }
        if (lead != null && leadWork.isNotEmpty()) {
            TmdbRow("More with ${lead.name}", leadWork) { openTmdb(it) }
        }
        if (tmdb?.recommendations?.isNotEmpty() == true) {
            TmdbRow("People also watched", tmdb.recommendations) { openTmdb(it) }
        }
        if (similar.isNotEmpty()) {
            Column {
                Text("More like this", fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, bottom = 10.dp))
                val memory = rememberFocusMemory()
                PivotScrolling(56.dp) {
                    LazyRow(
                        modifier = Modifier.restoreFocus(memory),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 56.dp),
                    ) {
                        items(similar, key = { it.id }) { m ->
                            PosterCard(
                                meta = m,
                                modifier = Modifier.rememberFocus(memory, m.id),
                                onFocus = {},
                                onClick = { onOpen(m) },
                                onLongClick = { menuFor = m },
                                width = cardWidth(1, false),
                                showTitle = false,
                            )
                        }
                    }
                }
            }
        }
        if (similar.isEmpty() && leadWork.isEmpty() && tmdb?.recommendations.isNullOrEmpty()) {
            // Something to land on, so the page above can still be reached.
            Box(Modifier.padding(start = 56.dp)) { ZButton("Back to top", {}, style = ButtonStyle.Quiet) }
        }
    }
}

/** A row of TMDB titles; the one you're on opens wide, like the episodes. */
@Composable
private fun TmdbRow(title: String, items: List<com.zapp.tv.data.TmdbItem>, onOpen: (com.zapp.tv.data.TmdbItem) -> Unit) {
    Column {
        Text(title, fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, bottom = 10.dp))
        val memory = rememberFocusMemory()
        PivotScrolling(56.dp) {
            LazyRow(
                modifier = Modifier.restoreFocus(memory),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 56.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(items, key = { it.type + it.id }) { item ->
                    var focused by remember { mutableStateOf(false) }
                    val wide = focused && item.backdrop != null
                    val w by androidx.compose.animation.core.animateDpAsState(if (wide) 300.dp else 114.dp, androidx.compose.animation.core.tween(220), label = "tw")
                    FocusSurface(
                        onClick = { onOpen(item) },
                        onFocusChange = { focused = it },
                        modifier = Modifier
                            .rememberFocus(memory, item.type + item.id)
                            .width(w)
                            .height(171.dp),
                        shape = RoundedCornerShape(10.dp),
                        focusScale = 1f,
                        restColor = ZappColors.Glass,
                        focusColor = ZappColors.Glass,
                        ring = true,
                    ) {
                        Artwork(if (wide) item.backdrop else item.poster ?: item.backdrop, item.name)
                        if (wide) {
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xD9000000))))
                            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                Text(item.name, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                item.year?.let { Text(it, fontSize = TextSize.Small, color = Color(0xCCFFFFFF)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(label.uppercase(), color = ZappColors.Faint, fontSize = TextSize.Tag, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, maxLines = 1)
        // Dotted leader between the label and the value.
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(10.dp).padding(horizontal = 8.dp)) {
            var x = 0f
            while (x < size.width) {
                drawCircle(Color(0x44FFFFFF), radius = 1f, center = androidx.compose.ui.geometry.Offset(x, size.height - 2f))
                x += 6f
            }
        }
        Text(value, color = ZappColors.Text, fontSize = TextSize.Small, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** The main action: a play triangle, the label, and how far you got when resuming. */
@Composable
private fun PlayButton(label: String, progress: WatchProgress?, requester: FocusRequester, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        focusRequester = requester,
        shape = RoundedCornerShape(50),
        focusScale = 1.03f,
        restColor = Color(0x2EFFFFFF),
    ) { focused ->
        val fg = if (focused) ZappColors.OnFocus else Color.White
        Row(Modifier.padding(start = 16.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
                val w = size.width
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.1f, 0f)
                        lineTo(w, w * 0.5f)
                        lineTo(w * 0.1f, w)
                        close()
                    },
                    fg,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(label, color = fg, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (progress != null) {
                Spacer(Modifier.width(12.dp))
                ProgressLine(
                    if (progress.duration > 1) (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f)
                    else ((progress.percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f),
                    Modifier.width(46.dp),
                    track = if (focused) Color(0x33000000) else Color(0x40FFFFFF),
                    fill = fg,
                )
                Spacer(Modifier.width(10.dp))
                Text(minutesLeft(progress), color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = TextSize.Small, maxLines = 1)
            }
        }
    }
}

/** The episode to offer first: the one in progress, else the one after the last watched, else the first. */
private fun nextUp(videos: List<Video>, progress: List<WatchProgress>): Pair<Video?, Long> {
    if (videos.isEmpty()) return null to 0L
    val latest = progress.maxByOrNull { it.updatedAt }
    if (latest != null) {
        val i = videos.indexOfFirst { it.id == latest.videoId }
        if (i >= 0) {
            if (!latest.watched && (latest.position > 30_000 || (latest.percent ?: 0.0) > 1)) return videos[i] to latest.position
            if (!latest.watched && (latest.percent ?: 0.0) < 0) return videos[i] to 0L // "Up next"
            videos.getOrNull(i + 1)?.let { return it to 0L }
        }
    }
    return (videos.firstOrNull { (it.season ?: 0) > 0 } ?: videos.first()) to 0L
}

private fun seasonsOf(videos: List<Video>): List<Int> =
    videos.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))

private fun seasonName(s: Int) = if (s == 0) "Specials" else "Season $s"

@Composable
private fun EpisodeCard(v: Video, p: WatchProgress?, modifier: Modifier, upNext: Boolean = false, onFocus: () -> Unit = {}, rating: Double? = null, shield: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // The card you're on grows; the others stay compact.
    val w by androidx.compose.animation.core.animateDpAsState(if (focused) 290.dp else 190.dp, androidx.compose.animation.core.tween(220), label = "epw")
    Column(Modifier.width(w)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocusChange = {
                focused = it
                if (it) onFocus()
            },
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
            focusScale = 1f,
            restColor = ZappColors.Glass,
            focusColor = ZappColors.Glass,
            ring = true,
            ringCorner = 12.dp,
        ) { f ->
            // After a second on it, the still slowly pans, like the art behind Home.
            var panning by remember { mutableStateOf(false) }
            LaunchedEffect(f) {
                panning = false
                if (f) {
                    kotlinx.coroutines.delay(1_000)
                    panning = true
                }
            }
            if (shield) {
                // Spoiler shield: a blur (on newer TVs) under a soft veil, so nothing gives the episode away.
                Box(Modifier.fillMaxSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(22.dp) else Modifier)) { Artwork(v.thumbnail, shortLabel(v)) }
                Box(Modifier.fillMaxSize().background(if (android.os.Build.VERSION.SDK_INT >= 31) Color(0x55000000) else Color(0xD9101018)))
            } else {
                Box(Modifier.fillMaxSize().then(if (panning) Modifier.drift() else Modifier)) { Artwork(v.thumbnail, shortLabel(v)) }
            }
            if (f) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text((if (v.episode != null) "${v.episode}. " else "") + v.title.ifBlank { "Episode" }, fontSize = TextSize.Body, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(if (upNext) "Up next" else null, if (p?.watched == true) "Watched" else null, p?.takeIf { !it.watched && it.position > 30_000 }?.let { minutesLeft(it) }).joinToString("  ·  ").ifBlank { " " },
                        fontSize = TextSize.Small,
                        color = Color(0xCCFFFFFF),
                    )
                }
            }
            if (p != null && !p.watched && p.duration > 0) {
                ProgressLine(
                    (p.position.toFloat() / p.duration).coerceIn(0f, 1f),
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth(),
                    track = Color(0x55FFFFFF),
                )
            }
            if (p?.watched == true) {
                WatchedTick(Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
            // The episode's rating, tiny, top left.
            rating?.let { r ->
                Text(
                    "★ %.1f".format(java.util.Locale.UK, r),
                    fontSize = TextSize.Label,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), androidx.compose.ui.geometry.Offset(0f, 1f), 5f)),
                    modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
                )
            }
        }
        if (!focused) {
            Spacer(Modifier.height(6.dp))
            Text((if (v.episode != null) "${v.episode}. " else "") + v.title.ifBlank { "Episode" }, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (upNext) "Up next" else v.released?.take(10).orEmpty(), fontSize = TextSize.Label, color = if (upNext) ZappColors.Brand2 else ZappColors.Faint, maxLines = 1)
        }
    }
}


/** Shimmering blocks where the details page's title, facts, story and buttons will be. */
@Composable
private fun DetailSkeleton(modifier: Modifier) {
    val b = shimmerBrush()
    @Composable
    fun bar(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, r: androidx.compose.ui.unit.Dp = 6.dp) =
        Box(Modifier.width(w).height(h).clip(RoundedCornerShape(r)).background(b))
    Column(modifier.padding(start = 56.dp, bottom = 70.dp)) {
        bar(90.dp, 10.dp)
        Spacer(Modifier.height(14.dp))
        bar(380.dp, 64.dp, 10.dp)
        Spacer(Modifier.height(16.dp))
        bar(300.dp, 12.dp)
        Spacer(Modifier.height(18.dp))
        bar(520.dp, 12.dp)
        Spacer(Modifier.height(8.dp))
        bar(480.dp, 12.dp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            bar(140.dp, 40.dp, 50.dp)
            bar(100.dp, 40.dp, 50.dp)
            bar(40.dp, 40.dp, 50.dp)
        }
    }
}
