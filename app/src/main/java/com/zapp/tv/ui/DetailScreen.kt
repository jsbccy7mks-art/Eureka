package com.zapp.tv.ui

import androidx.compose.ui.focus.onFocusChanged
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
) {
    val videoId: String get() = video?.id ?: meta.id
    val episodeLabel: String? get() = video?.let { episodeLabel(it) }
}

fun episodeLabel(v: Video): String =
    if (v.season != null && v.episode != null) "S${v.season} E${v.episode}" + (v.title.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
    else v.title

private fun shortLabel(v: Video): String =
    if (v.season != null && v.episode != null) "S${v.season} E${v.episode}" else v.title

@Composable
fun DetailScreen(
    mvm: MediaViewModel,
    request: DetailRequest,
    focusTick: Int,
    onPlay: (PlayRequest) -> Unit,
    onClose: () -> Unit,
    onOpenTitle: (MetaPreview) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val loaded by produceState<Pair<Boolean, MetaDetail?>>(false to null, request.id) {
        value = true to mvm.meta(request.type, request.id)
    }
    val (done, detail) = loaded
    val meta = detail?.preview ?: request.preview
    val progress by remember(request.id) { mvm.progressFor(request.id) }.collectAsState(initial = emptyList<WatchProgress>())
    val inLibrary by remember(request.id) { mvm.inLibrary(request.id) }.collectAsState(initial = false)
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(done, focusTick) { if (done) playFocus.tryRequest() }

    Box(
        Modifier
            .fillMaxSize()
            .background(ZappColors.Bg),
    ) {
        // Artwork at full strength, fading into the page only where the text sits.
        (meta?.background ?: meta?.poster)?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(*easedFade(start = 0f, end = 0.65f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*easedFade(start = 1f, end = 0.3f))))
        AmbientGlow(0.6f)

        if (!done && meta == null) {
            ZappLoader(44.dp, Modifier.align(Alignment.Center))
            return@Box
        }
        if (meta == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load this title", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("None of your add-ons returned details for it.", color = ZappColors.Dim, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                ZButton("Back", onClose, focusRequester = playFocus)
            }
            return@Box
        }

        val isSeries = request.type == "series" && !detail?.videos.isNullOrEmpty()
        val videos = detail?.videos.orEmpty()
        val nextUp = remember(videos, progress) { nextUp(videos, progress) }
        val movieProgress = progress.firstOrNull { it.videoId == meta.id && !it.watched && (it.position > 30_000 || (it.percent ?: 0.0) > 1) }

        // The first screen is the title, pinned to the bottom over the artwork; scrolling down
        // (moving below the buttons or episodes) shows more like this and the full details.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            val scroll = remember(request.id) { androidx.compose.foundation.ScrollState(0) }
            val scope = rememberCoroutineScope()
            val dim = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
            Box(Modifier.fillMaxSize().background(ZappColors.Bg.copy(alpha = 0.8f * dim)))
            NoAutoScroll {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(screenHeight)
                            .onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0, tween(450)) } },
                    ) {
                    // Everything sits along the bottom of the screen, over the picture.
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = 56.dp, bottom = if (isSeries) 26.dp else 52.dp),
                    ) {
                        Text(
                            if (isSeries) "SERIES" else "FILM",
                            color = ZappColors.Faint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.8.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        MovieTitle(meta.name, if (isSeries) 44.sp else 58.sp, maxLines = 2, modifier = Modifier.widthIn(max = 680.dp))
                        Spacer(Modifier.height(10.dp))
                        FactsLine(meta.releaseInfo, meta.imdbRating, detail?.runtime, meta.genres)
                        meta.description?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                it,
                                color = ZappColors.Dim,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                maxLines = if (isSeries) 2 else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 560.dp),
                            )
                        }
                        val people = listOfNotNull(
                            detail?.cast?.takeIf { it.isNotEmpty() }?.take(4)?.joinToString(", ")?.let { "With $it" },
                            detail?.directors?.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(", ")?.let { "Directed by $it" },
                        )
                        if (people.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                people.joinToString("   ·   "),
                                color = ZappColors.Faint,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 620.dp),
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isSeries) {
                                val (video, resume) = nextUp
                                if (video != null) {
                                    val p = progress.firstOrNull { it.videoId == video.id && !it.watched }
                                    PlayButton(
                                        when {
                                            resume > 0 -> "Resume ${shortLabel(video)}"
                                            progress.isEmpty() -> "Play ${shortLabel(video)}"
                                            else -> "Play ${shortLabel(video)}"
                                        },
                                        p?.takeIf { resume > 0 },
                                        playFocus,
                                    ) {
                                        onPlay(PlayRequest(meta, video, videos, resume, mvm.settings.autoPlayFirst, resumePercent = p?.percent))
                                    }
                                    ZButton("Sources", { onPlay(PlayRequest(meta, video, videos, resume, autoPlay = false)) })
                                }
                            } else {
                                val resume = movieProgress?.position ?: 0L
                                PlayButton(if (movieProgress != null) "Resume" else "Play", movieProgress, playFocus) {
                                    onPlay(PlayRequest(meta, null, emptyList(), resume, mvm.settings.autoPlayFirst, resumePercent = movieProgress?.percent))
                                }
                                if (movieProgress != null) {
                                    ZButton("Start over", { onPlay(PlayRequest(meta, null, emptyList(), 0, mvm.settings.autoPlayFirst)) })
                                }
                                ZButton("Sources", { onPlay(PlayRequest(meta, null, emptyList(), resume, autoPlay = false)) })
                            }
                            ZButton(if (inLibrary) "✓  In library" else "+  Library", { mvm.toggleLibrary(meta, inLibrary) })
                        }
                        if (!done) {
                            Spacer(Modifier.height(8.dp))
                            Text("Loading details…", color = ZappColors.Faint, fontSize = 11.sp)
                        }

                        if (isSeries) {
                            Spacer(Modifier.height(22.dp))
                            SeasonsAndEpisodes(videos, progress, nextUp.first) { v ->
                                val p = progress.firstOrNull { it.videoId == v.id }
                                val resume = if (p != null && !p.watched) p.position else 0L
                                onPlay(PlayRequest(meta, v, videos, resume, mvm.settings.autoPlayFirst, resumePercent = p?.takeIf { !it.watched }?.percent))
                            }
                        }
                    }

                    }
                    MoreSection(
                        mvm = mvm,
                        meta = meta,
                        detail = detail,
                        onOpen = onOpenTitle,
                        modifier = Modifier.onFocusChanged {
                            if (it.hasFocus) scope.launch { scroll.animateScrollTo(scroll.maxValue, tween(450)) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Below the first screen: more titles like this one (from your catalogues of the same kind that share
 * a genre) and the full details.
 */
@Composable
private fun MoreSection(
    mvm: MediaViewModel,
    meta: MetaPreview,
    detail: MetaDetail?,
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
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 48.dp),
    ) {
        if (similar.isNotEmpty()) {
            Text("More like this", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, bottom = 10.dp))
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
                            width = cardWidth(1, false),
                            showTitle = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        } else {
            // Something to land on, so the details below can still be reached.
            Box(Modifier.padding(start = 56.dp, bottom = 16.dp)) { ZButton("Details", {}) }
        }

        Text("Details", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
        Row(Modifier.padding(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailFact("Released", meta.releaseInfo)
                DetailFact("Runtime", prettyRuntime(detail?.runtime))
                DetailFact("Genres", meta.genres.joinToString(", ").ifBlank { null })
                DetailFact("IMDb", meta.imdbRating?.let { "$it / 10" })
            }
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailFact("Directed by", detail?.directors?.joinToString(", ")?.ifBlank { null })
                DetailFact("Cast", detail?.cast?.take(12)?.joinToString(", ")?.ifBlank { null })
                DetailFact("About", meta.description)
            }
        }
    }
}

@Composable
private fun DetailFact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label.uppercase(), color = ZappColors.Faint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = ZappColors.Dim, fontSize = 12.5.sp, lineHeight = 18.sp)
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
        Row(Modifier.padding(start = 18.dp, end = 22.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(11.dp)) {
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
            Text(label, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
                Text(minutesLeft(progress), color = if (focused) ZappColors.OnFocusDim else ZappColors.Dim, fontSize = 12.sp, maxLines = 1)
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
            videos.getOrNull(i + 1)?.let { return it to 0L }
        }
    }
    return (videos.firstOrNull { (it.season ?: 0) > 0 } ?: videos.first()) to 0L
}

@Composable
private fun SeasonsAndEpisodes(
    videos: List<Video>,
    progress: List<WatchProgress>,
    nextUp: Video?,
    onPlay: (Video) -> Unit,
) {
    val seasons = remember(videos) {
        videos.mapNotNull { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
    }
    var season by remember(videos) { mutableStateOf(nextUp?.season ?: seasons.firstOrNull()) }
    val memory = rememberFocusMemory()

    if (seasons.size > 1) {
        MinimalScrolling {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(seasons) { s ->
                FocusSurface(
                    onClick = { season = s },
                    onFocusChange = { if (it) season = s },
                    shape = RoundedCornerShape(50),
                    focusScale = 1f,
                ) { focused ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (s == 0) "Specials" else "Season $s",
                            color = when {
                                focused -> ZappColors.OnFocus
                                s == season -> ZappColors.Text
                                else -> ZappColors.Faint
                            },
                            fontSize = 12.5.sp,
                            fontWeight = if (s == season || focused) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        if (s == season && !focused) {
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier
                                    .width(16.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                            )
                        }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(8.dp))
    }

    val episodes = videos.filter { season == null || it.season == season }
    PivotScrolling(0.dp) {
    LazyRow(
        modifier = Modifier.restoreFocus(memory),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 40.dp),
    ) {
        items(episodes, key = { it.id }) { v ->
            val p = progress.firstOrNull { it.videoId == v.id }
            EpisodeCard(v, p, Modifier.rememberFocus(memory, v.id)) { onPlay(v) }
        }
    }
    }
}

@Composable
private fun EpisodeCard(v: Video, p: WatchProgress?, modifier: Modifier, onClick: () -> Unit) {
    Column(Modifier.width(196.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(10.dp),
            focusScale = 1f,
            restColor = ZappColors.Glass,
        ) { focused ->
            Artwork(v.thumbnail, shortLabel(v))
            if (p != null) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xAA000000))))
                if (p.watched) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ZappColors.Bg.copy(alpha = 0.80f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) { Text("Watched", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
                } else {
                    ProgressLine(
                        (p.position.toFloat() / p.duration.coerceAtLeast(1)).coerceIn(0f, 1f),
                        Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                        track = Color(0x66FFFFFF),
                        fill = Color.White,
                    )
                }
            }
            if (focused) Box(Modifier.fillMaxSize().border(2.5.dp, Color.White, RoundedCornerShape(10.dp)))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            (if (v.episode != null) "${v.episode}. " else "") + v.title.ifBlank { "Episode" },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = ZappColors.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
