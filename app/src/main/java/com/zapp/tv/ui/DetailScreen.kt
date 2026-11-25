package com.zapp.tv.ui

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
        (meta?.background ?: meta?.poster)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.7f, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to ZappColors.Bg, 0.5f to ZappColors.Bg.copy(alpha = 0.82f), 1f to Color(0x330A0E1A))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to ZappColors.Bg)))

        if (!done && meta == null) {
            CircularProgressIndicator(color = ZappColors.Brand2, modifier = Modifier.align(Alignment.Center).size(32.dp))
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
        val movieProgress = progress.firstOrNull { it.videoId == meta.id && !it.watched && it.position > 30_000 }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 56.dp, top = 44.dp, end = 40.dp, bottom = 30.dp),
        ) {
            if (meta.logo != null) {
                AsyncImage(
                    model = meta.logo,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.heightIn(max = 84.dp).widthIn(max = 340.dp),
                )
            } else {
                Text(meta.name, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, modifier = Modifier.widthIn(max = 560.dp))
            }
            Spacer(Modifier.height(12.dp))
            MetaFacts(meta.releaseInfo, meta.imdbRating, detail?.runtime, meta.genres)
            meta.description?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = ZappColors.Dim, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 540.dp))
            }
            val people = listOfNotNull(
                detail?.cast?.takeIf { it.isNotEmpty() }?.take(5)?.joinToString(", ")?.let { "With $it" },
                detail?.directors?.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Directed by $it" },
            )
            if (people.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                people.forEach { Text(it, color = ZappColors.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp)) }
            }
            if (!done) {
                Spacer(Modifier.height(10.dp))
                Text("Loading details…", color = ZappColors.Faint, fontSize = 12.sp)
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isSeries) {
                    val (video, resume) = nextUp
                    if (video != null) {
                        ZButton(
                            when {
                                resume > 0 -> "Resume ${shortLabel(video)}"
                                progress.isEmpty() -> "Play ${shortLabel(video)}"
                                else -> "Next: ${shortLabel(video)}"
                            },
                            { onPlay(PlayRequest(meta, video, videos, resume, mvm.settings.autoPlayFirst)) },
                            style = ButtonStyle.Primary,
                            focusRequester = playFocus,
                        )
                        ZButton("Choose source", { onPlay(PlayRequest(meta, video, videos, resume, autoPlay = false)) })
                    }
                } else {
                    val resume = movieProgress?.position ?: 0L
                    ZButton(
                        if (movieProgress != null) "Resume, ${minutesLeft(movieProgress)}" else "Play",
                        { onPlay(PlayRequest(meta, null, emptyList(), resume, mvm.settings.autoPlayFirst)) },
                        style = ButtonStyle.Primary,
                        focusRequester = playFocus,
                    )
                    if (movieProgress != null) {
                        ZButton("Start over", { onPlay(PlayRequest(meta, null, emptyList(), 0, mvm.settings.autoPlayFirst)) })
                    }
                    ZButton("Choose source", { onPlay(PlayRequest(meta, null, emptyList(), resume, autoPlay = false)) })
                }
                ZButton(if (inLibrary) "Remove from library" else "Add to library", { mvm.toggleLibrary(meta, inLibrary) })
            }

            if (isSeries) {
                Spacer(Modifier.height(24.dp))
                SeasonsAndEpisodes(videos, progress, nextUp.first) { v ->
                    val p = progress.firstOrNull { it.videoId == v.id }
                    val resume = if (p != null && !p.watched) p.position else 0L
                    onPlay(PlayRequest(meta, v, videos, resume, mvm.settings.autoPlayFirst))
                }
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
            if (!latest.watched && latest.position > 30_000) return videos[i] to latest.position
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(seasons) { s ->
                FocusSurface(
                    onClick = { season = s },
                    selected = s == season,
                    onFocusChange = { if (it) season = s },
                    shape = RoundedCornerShape(50),
                    selectedColor = ZappColors.GlassHigh,
                ) { focused ->
                    Text(
                        if (s == 0) "Specials" else "Season $s",
                        color = when {
                            focused -> ZappColors.OnFocus
                            s == season -> ZappColors.Text
                            else -> ZappColors.Dim
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    val episodes = videos.filter { season == null || it.season == season }
    LazyRow(
        modifier = Modifier.restoreFocus(memory),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(end = 40.dp),
    ) {
        items(episodes, key = { it.id }) { v ->
            val p = progress.firstOrNull { it.videoId == v.id }
            EpisodeCard(v, p, Modifier.rememberFocus(memory, v.id)) { onPlay(v) }
        }
    }
}

@Composable
private fun EpisodeCard(v: Video, p: WatchProgress?, modifier: Modifier, onClick: () -> Unit) {
    Column(Modifier.width(220.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
            focusScale = 1.06f,
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
                            .background(Color(0xCC0A0E1A))
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
            if (focused) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(12.dp)))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            (if (v.episode != null) "${v.episode}. " else "") + v.title.ifBlank { "Episode" },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        v.overview?.let {
            Text(it, color = ZappColors.Faint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
    }
}
