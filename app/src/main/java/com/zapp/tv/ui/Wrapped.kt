package com.zapp.tv.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zapp.tv.ZappApp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A month ([month] 1–12) or a whole year ([month] null). */
data class WrappedPeriod(val year: Int, val month: Int?) {
    val title: String get() = month?.let { YearMonth.of(year, it).month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK) } ?: year.toString()
    val word: String get() = if (month != null) "month" else "year"
    val months: List<String> get() = (month?.let { listOf(it) } ?: (1..12).toList()).map { "%04d-%02d".format(year, it) }
    val start: Long get() = LocalDate.of(year, month ?: 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val end: Long get() = (if (month != null) LocalDate.of(year, month, 1).plusMonths(1) else LocalDate.of(year + 1, 1, 1)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val previous: WrappedPeriod get() = if (month != null) YearMonth.of(year, month).minusMonths(1).let { WrappedPeriod(it.year, it.monthValue) } else WrappedPeriod(year - 1, null)

    companion object {
        /** What's ready now: last month during the first week; the year from mid-December to early January. */
        fun ready(today: LocalDate = LocalDate.now()): WrappedPeriod? = when {
            (today.monthValue == 12 && today.dayOfMonth >= 15) -> WrappedPeriod(today.year, null)
            (today.monthValue == 1 && today.dayOfMonth <= 7) -> WrappedPeriod(today.year - 1, null)
            today.dayOfMonth <= 7 -> today.minusMonths(1).let { WrappedPeriod(it.year, it.monthValue) }
            else -> null
        }

        /** The month just gone (always available from Library). */
        fun lastMonth(today: LocalDate = LocalDate.now()) = today.minusMonths(1).let { WrappedPeriod(it.year, it.monthValue) }
    }
}

data class WrappedTitle(val id: String, val type: String, val name: String, val poster: String?, val count: Int)
data class WrappedPerson(val name: String, val photo: String?, val count: Int)

data class WrappedData(
    val period: WrappedPeriod,
    val hours: Int,
    val prevHours: Int?,
    val titles: Int,
    val episodes: Int,
    val topSeries: List<WrappedTitle>,
    val topFilms: List<WrappedTitle>,
    val genres: List<Pair<String, Int>>,
    val actor: WrappedPerson?,
    val binge: WrappedTitle?,
    val lateNight: String?,
    val channel: Pair<String, Int>?,
    val taste: String?,
)

/** Which Wrapped is open (shown over everything by the app). */
object WrappedState {
    var open by mutableStateOf<WrappedPeriod?>(null)
}

/** Works out a month or year from what's been watched on this profile. */
object WrappedMaker {
    private val episodeId = Regex(":(\\d+):(\\d+)$")

    /**
     * Titles left out of a year's story (and every month in it), per profile: usually something
     * someone else watched. Only the story changes; watch history stays as it is.
     */
    fun leftOut(app: ZappApp, year: Int): Set<String> =
        app.getSharedPreferences("wrapped", android.content.Context.MODE_PRIVATE)
            .getString("out:${app.settings.profileId}:$year", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun leaveOut(app: ZappApp, year: Int, id: String) {
        val p = app.getSharedPreferences("wrapped", android.content.Context.MODE_PRIVATE)
        p.edit().putString("out:${app.settings.profileId}:$year", (leftOut(app, year) + id).joinToString(",")).apply()
    }

    suspend fun make(app: ZappApp, period: WrappedPeriod): WrappedData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pid = app.settings.profileId
        val everything = app.mediaDao.allProgress(pid).filter { !it.videoId.startsWith("catchup:") }
        val out = leftOut(app, period.year)
        val all = everything.filter { it.metaId !in out }

        fun estimateSeconds(rows: List<com.zapp.tv.data.WatchProgress>, p: WrappedPeriod): Long =
            rows.filter { it.updatedAt in p.start until p.end }.sumOf { r ->
                val isEp = episodeId.containsMatchIn(r.videoId)
                val dur = if (r.duration > 0) r.duration else if (isEp) 45 * 60_000L else 110 * 60_000L
                (if (r.watched) dur else r.position.coerceAtMost(dur)) / 1000
            }
        fun hours(p: WrappedPeriod): Int {
            // The running totals can't tell titles apart, so what was left out comes off them.
            val skip = leftOut(app, p.year)
            val gone = estimateSeconds(everything.filter { it.metaId in skip }, p)
            val logged = com.zapp.tv.data.WatchLog.seconds(app, p.months, "vod") + com.zapp.tv.data.WatchLog.seconds(app, p.months, "live") - gone
            return ((maxOf(logged, estimateSeconds(everything.filter { it.metaId !in skip }, p)) + 1800) / 3600).toInt()
        }

        val rows = all.filter { it.updatedAt in period.start until period.end }
        val episodes = rows.filter { episodeId.containsMatchIn(it.videoId) && (it.watched || it.position > 5 * 60_000) }
        val films = rows.filter { it.type == "movie" && it.videoId == it.metaId && (it.watched || it.position > 10 * 60_000) }

        // History synced from elsewhere (Nuvio, Stremio) can carry just the IMDb id ("tt…") and no
        // poster: the real name and poster come from your library, then your add-ons.
        val library = runCatching { app.mediaDao.allLibrary(pid) }.getOrDefault(emptyList()).associateBy { it.metaId }
        fun bare(name: String?) = name.isNullOrBlank() || name.startsWith("tt") || name.startsWith("tmdb:")
        suspend fun named(t: WrappedTitle): WrappedTitle {
            if (!bare(t.name) && t.poster != null) return t
            val l = library[t.id]
            var name = t.name.takeUnless { bare(it) } ?: l?.name?.takeUnless { bare(it) }
            var poster = t.poster ?: l?.poster
            if (name == null || poster == null) {
                val m = runCatching { app.media.meta(t.type, t.id) }.getOrNull()
                name = name ?: m?.preview?.name?.takeUnless { bare(it) }
                poster = poster ?: m?.preview?.poster
            }
            return t.copy(name = name ?: t.name, poster = poster ?: fallbackPoster(t.id))
        }
        suspend fun named(list: List<WrappedTitle>) = kotlinx.coroutines.coroutineScope { list.map { async { named(it) } }.awaitAll() }

        val topSeries = named(
            episodes.groupBy { it.metaId }.map { (id, l) ->
                val r = l.maxBy { it.updatedAt }
                WrappedTitle(id, "series", r.name, r.poster, l.size)
            }.sortedByDescending { it.count }.take(5),
        )
        val topFilms = named(
            films.sortedWith(compareByDescending<com.zapp.tv.data.WatchProgress> { it.watched }.thenByDescending { it.updatedAt })
                .distinctBy { it.metaId }
                .map { WrappedTitle(it.metaId, "movie", it.name, it.poster, 1) }.take(5),
        )

        // Genres and the most-seen actor, from TMDB (with your key), weighted by how much you watched.
        val genres = HashMap<String, Int>()
        val actors = HashMap<String, Pair<String?, Int>>()
        if (app.tmdb.enabled) {
            (topSeries + topFilms).filter { it.id.startsWith("tt") }.forEach { t ->
                val d = runCatching { app.tmdb.details(t.id, t.type) }.getOrNull() ?: return@forEach
                val w = t.count.coerceIn(1, 6)
                d.genres.forEach { (_, g) -> genres[g] = (genres[g] ?: 0) + w }
                d.cast.take(4).forEach { c -> actors[c.name] = (actors[c.name]?.first ?: c.photo) to ((actors[c.name]?.second ?: 0) + 1) }
            }
        }
        val actor = actors.entries.filter { it.value.second >= 2 }.maxByOrNull { it.value.second }?.let { WrappedPerson(it.key, it.value.first, it.value.second) }

        // The biggest night: most episodes of one series in one evening (6pm to 6am).
        val zone = ZoneId.systemDefault()
        val binge = episodes.groupBy { it.metaId to java.time.Instant.ofEpochMilli(it.updatedAt - 6 * 3_600_000L).atZone(zone).toLocalDate() }
            .maxByOrNull { it.value.size }?.takeIf { it.value.size >= 3 }
            ?.let { (k, l) -> topSeries.firstOrNull { it.id == k.first }?.copy(count = l.size) ?: named(WrappedTitle(k.first, "series", l.first().name, l.first().poster, l.size)) }

        // The latest you watched until (after midnight counts as later than the evening before).
        val late = rows.map { java.time.Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalTime() }
            .maxByOrNull { t -> (t.toSecondOfDay() - 6 * 3600 + 86_400) % 86_400 }
            ?.takeIf { it.hour < 5 || it.hour >= 22 }
            ?.let { java.time.format.DateTimeFormatter.ofPattern("h:mma", java.util.Locale.UK).format(it).lowercase() }

        val channel = com.zapp.tv.data.WatchLog.channels(app, period.months).firstOrNull()?.let { it.first to (it.second / 60).toInt() }?.takeIf { it.second >= 30 }

        val g = genres.entries.sortedByDescending { it.value }.map { it.key to it.value }
        val taste = when {
            g.size >= 2 -> "${g[0].first} lover with a soft spot for ${g[1].first.lowercase()}"
            g.size == 1 -> "A true ${g[0].first.lowercase()} fan"
            else -> null
        }
        WrappedData(
            period = period,
            hours = hours(period),
            prevHours = hours(period.previous).takeIf { it > 0 },
            titles = topSeries.size + films.size,
            episodes = episodes.size,
            topSeries = topSeries,
            topFilms = topFilms,
            genres = g.take(5),
            actor = actor,
            binge = binge,
            lateNight = late,
            channel = channel,
            taste = taste,
        )
    }
}

/**
 * Your month (or year) on Axon, as a story: one card at a time, OK or Right for the next, Left to
 * go back, Back to close. Big numbers count up, posters rise in, the genres grow as bars.
 */
@Composable
fun WrappedScreen(period: WrappedPeriod, onClose: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as ZappApp
    var redo by remember { mutableIntStateOf(0) }
    val data by produceState<WrappedData?>(null, period, redo) { value = runCatching { WrappedMaker.make(app, period) }.getOrNull() }
    // Down on a card with posters: pick one (Left and Right), OK to leave it out of the story.
    var choosing by remember { mutableStateOf(false) }
    var chosen by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<WrappedTitle?>(null) }
    BackHandler { if (choosing) choosing = false else onClose() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequest() }
    val d = data
    val pages = remember(d) { if (d == null) emptyList() else wrappedPages(d) }
    var page by remember(period) { mutableIntStateOf(0) }
    LaunchedEffect(pages.size) { if (pages.isNotEmpty() && page > pages.size - 1) page = pages.size - 1 }
    val titlesHere = pages.getOrNull(page)?.titles.orEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070C))
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (choosing) {
                    when (e.key) {
                        Key.DirectionRight -> chosen = (chosen + 1).coerceAtMost(titlesHere.size - 1)
                        Key.DirectionLeft -> chosen = (chosen - 1).coerceAtLeast(0)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> confirm = titlesHere.getOrNull(chosen)
                        Key.DirectionUp -> choosing = false
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                when (e.key) {
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (page < pages.size - 1) page++ else onClose()
                        true
                    }
                    Key.DirectionLeft -> {
                        if (page > 0) page--
                        true
                    }
                    Key.DirectionDown -> {
                        if (titlesHere.isNotEmpty()) {
                            choosing = true
                            chosen = 0
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        WrappedBackdrop(page)
        if (d == null) {
            ZappLoader(54.dp, Modifier.align(Alignment.Center))
            return@Box
        }
        // Progress dashes at the top, like a story.
        Row(Modifier.align(Alignment.TopCenter).padding(top = 22.dp).widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            pages.indices.forEach { i ->
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= page) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.2f)))
            }
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState > initialState
                (fadeIn(tween(420, delayMillis = 80)) + slideInHorizontally(tween(480, easing = FastOutSlowInEasing)) { if (forward) it / 6 else -it / 6 }) togetherWith
                    (fadeOut(tween(220)) + slideOutHorizontally(tween(300)) { if (forward) -it / 8 else it / 8 })
            },
            label = "wrapped",
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            Box(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 60.dp), contentAlignment = Alignment.Center) {
                pages.getOrNull(i)?.content?.invoke(if (choosing && i == page) chosen else null)
            }
        }
        Text(
            when {
                choosing -> "Left and Right to pick   ·   OK to leave it out   ·   Back when done"
                titlesHere.isNotEmpty() -> "OK for next   ·   Down to leave a title out   ·   Back to close"
                page < pages.size - 1 -> "OK for next   ·   Back to close"
                else -> "OK to finish"
            },
            color = Color.White.copy(alpha = 0.45f),
            fontSize = TextSize.Label,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
        )
    }
    confirm?.let { t ->
        LeaveOutDialog(
            t,
            period,
            onLeaveOut = {
                WrappedMaker.leaveOut(app, period.year, t.id)
                confirm = null
                choosing = false
                redo++
                focus.tryRequest()
            },
        ) {
            confirm = null
            focus.tryRequest()
        }
    }
}

/** "Leave … out of Your October?" */
@Composable
private fun LeaveOutDialog(t: WrappedTitle, period: WrappedPeriod, onLeaveOut: () -> Unit, onCancel: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        first.tryRequest()
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        DialogBlurBehind()
        Column(
            Modifier.width(440.dp).clip(Shapes.Panel).background(ZappColors.Panel).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Leave ${t.name} out of Your ${period.title}?", fontSize = TextSize.Title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (period.month != null) "It won't count towards your ${period.title} or your ${period.year}. It stays in your watch history."
                else "It won't count towards your ${period.year} or any month in it. It stays in your watch history.",
                color = ZappColors.Dim,
                fontSize = TextSize.Body,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZButton("Leave out", onLeaveOut, style = ButtonStyle.Primary, focusRequester = first)
                ZButton("Cancel", onCancel, style = ButtonStyle.Quiet)
            }
        }
    }
}

/** Slowly moving colour behind the story, shifting a little with each card. */
@Composable
private fun WrappedBackdrop(page: Int) {
    val t = rememberInfiniteTransition(label = "wbg")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "drift")
    val hue = remember { Animatable(0f) }
    LaunchedEffect(page) { hue.animateTo(page.toFloat(), tween(900)) }
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Canvas(Modifier.fillMaxSize()) {
        val a = (drift * 2 * Math.PI).toFloat()
        val shift = (hue.value % 4) / 4f
        drawRect(
            Brush.radialGradient(
                listOf(b1.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * (0.25f + 0.1f * kotlin.math.sin(a) + 0.3f * shift), size.height * (0.3f + 0.1f * kotlin.math.cos(a))),
                radius = size.width * 0.55f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(b2.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * (0.8f - 0.1f * kotlin.math.cos(a) - 0.25f * shift), size.height * (0.75f + 0.08f * kotlin.math.sin(a))),
                radius = size.width * 0.5f,
            ),
        )
    }
}

/** One card of the story: what it draws (with the picked poster, when picking) and the titles on it. */
private class WPage(val titles: List<WrappedTitle> = emptyList(), val content: @Composable (picked: Int?) -> Unit)

private fun wrappedPages(d: WrappedData): List<WPage> = buildList<WPage> {
    fun add(content: @Composable () -> Unit) = add(WPage { content() })
    val p = d.period
    add {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZappLogo(40.dp)
            Spacer(Modifier.height(34.dp))
            BigLine("Your ${p.title}", 54.sp)
            Spacer(Modifier.height(10.dp))
            SmallLine("A look back at your ${p.word} on Axon")
        }
    }
    add {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SmallLine("You watched for")
            Spacer(Modifier.height(6.dp))
            CountUp(d.hours, " hours", 96.sp)
            Spacer(Modifier.height(10.dp))
            val prev = d.prevHours
            SmallLine(
                when {
                    prev == null -> "${d.titles} titles and ${d.episodes} episodes"
                    d.hours > prev -> "${d.hours - prev} more than the ${p.word} before"
                    d.hours < prev -> "${prev - d.hours} fewer than the ${p.word} before"
                    else -> "The same as the ${p.word} before"
                },
            )
        }
    }
    if (d.topSeries.isNotEmpty()) add(WPage(d.topSeries) { picked -> PosterPage("Your top series", d.topSeries, picked) { "${it.count} episode" + if (it.count == 1) "" else "s" } })
    if (d.topFilms.isNotEmpty()) add(WPage(d.topFilms) { picked -> PosterPage("Your films", d.topFilms, picked) { null } })
    if (d.genres.isNotEmpty()) add { GenrePage(d.genres) }
    d.actor?.let { a ->
        add {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallLine("The face you saw most")
                Spacer(Modifier.height(18.dp))
                val rise = remember { Animatable(0f) }
                LaunchedEffect(Unit) { rise.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
                Box(
                    Modifier.size(170.dp).graphicsLayer { alpha = rise.value; scaleX = 0.85f + 0.15f * rise.value; scaleY = 0.85f + 0.15f * rise.value }
                        .clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
                ) {
                    if (a.photo != null) AsyncImage(model = a.photo, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(18.dp))
                BigLine(a.name, 40.sp)
                Spacer(Modifier.height(6.dp))
                SmallLine("In ${a.count} of the things you watched")
            }
        }
    }
    d.binge?.let { b ->
        add(WPage(listOf(b)) { picked ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(b.poster, b.name, 150.dp, picked = picked == 0)
                Spacer(Modifier.width(36.dp))
                Column {
                    SmallLine("Your biggest night", center = false)
                    Spacer(Modifier.height(6.dp))
                    CountUp(b.count, " episodes", 72.sp)
                    Spacer(Modifier.height(6.dp))
                    SmallLine("of ${b.name} in one sitting", center = false)
                }
            }
        })
    }
    d.lateNight?.let { t ->
        add {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallLine("Night owl award")
                Spacer(Modifier.height(6.dp))
                BigLine(t, 88.sp)
                Spacer(Modifier.height(8.dp))
                SmallLine("The latest you were still watching")
            }
        }
    }
    d.channel?.let { (name, minutes) ->
        add {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallLine("Your go-to channel")
                Spacer(Modifier.height(6.dp))
                BigLine(name, 56.sp)
                Spacer(Modifier.height(8.dp))
                SmallLine(if (minutes >= 120) "${minutes / 60} hours of it" else "$minutes minutes of it")
            }
        }
    }
    add {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SmallLine("Your ${p.word} in a sentence")
            Spacer(Modifier.height(10.dp))
            BigLine(d.taste ?: "Off to a great start", 44.sp)
            Spacer(Modifier.height(26.dp))
            SmallLine(if (p.month != null) "See you next month" else "Here's to next year")
        }
    }
}

@Composable
private fun BigLine(text: String, size: androidx.compose.ui.unit.TextUnit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    Text(
        text,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1).sp,
        lineHeight = size * 1.08f,
        textAlign = TextAlign.Center,
        color = Color.White,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 30f },
    )
}

@Composable
private fun SmallLine(text: String, center: Boolean = true) {
    Text(text, fontSize = TextSize.Heading, color = Color.White.copy(alpha = 0.7f), textAlign = if (center) TextAlign.Center else TextAlign.Start)
}

/** A number that counts up when its card appears. */
@Composable
private fun CountUp(target: Int, suffix: String, size: androidx.compose.ui.unit.TextUnit) {
    val n = remember { Animatable(0f) }
    LaunchedEffect(target) { n.animateTo(target.toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
    Text(
        "${n.value.toInt()}$suffix",
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-2).sp,
        color = Color.White,
    )
}

@Composable
private fun Poster(url: String?, name: String, width: androidx.compose.ui.unit.Dp, picked: Boolean = false, dim: Boolean = false) {
    // Picking one to leave out: the picked poster lifts with a white edge, the others step back.
    val lift by androidx.compose.animation.core.animateFloatAsState(if (picked) 1f else 0f, tween(160), label = "pick")
    val fade by androidx.compose.animation.core.animateFloatAsState(if (dim) 0.4f else 1f, tween(160), label = "dim")
    Box(
        Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                scaleX = 1f + 0.06f * lift
                scaleY = 1f + 0.06f * lift
                alpha = fade
            }
            .border(2.5.dp, Color.White.copy(alpha = lift), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        Artwork(url, name)
    }
}

@Composable
private fun PosterPage(title: String, items: List<WrappedTitle>, picked: Int? = null, note: (WrappedTitle) -> String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BigLine(title, 40.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            items.forEachIndexed { i, t ->
                val rise = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(120L * i)
                    rise.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
                }
                Column(
                    Modifier.width(if (i == 0) 150.dp else 118.dp).graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 60f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${i + 1}", fontSize = if (i == 0) 30.sp else 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(6.dp))
                    Poster(t.poster, t.name, if (i == 0) 150.dp else 118.dp, picked = picked == i, dim = picked != null && picked != i)
                    Spacer(Modifier.height(8.dp))
                    Text(t.name, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    note(t)?.let { Text(it, fontSize = TextSize.Label, color = Color.White.copy(alpha = 0.6f), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun GenrePage(genres: List<Pair<String, Int>>) {
    val top = genres.first().second.coerceAtLeast(1)
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        BigLine("What you were into", 40.sp)
        Spacer(Modifier.height(30.dp))
        genres.forEachIndexed { i, (g, v) ->
            val grow = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(150L * i)
                grow.animateTo(v.toFloat() / top, tween(900, easing = FastOutSlowInEasing))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 7.dp)) {
                Text(g, fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.width(170.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(16.dp)) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(grow.value.coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(listOf(ZappColors.Brand1, ZappColors.Brand2))),
                    )
                }
            }
        }
    }
}
