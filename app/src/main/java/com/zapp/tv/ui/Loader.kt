package com.zapp.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Small loader wherever something is on its way (details, buffering). */
@Composable
fun ZappLoader(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    ZappMarkAnimated(size, modifier)
}

/** Lines shown while a film is starting, a new one every few seconds, in a random order. */
val WaitingLines = listOf(
    "Grab the popcorn, we're nearly there",
    "Dimming the lights…",
    "Not long now, the wait is almost over",
    "Finding you the best seat in the house",
    "Shh… it's about to start",
    "Rolling the film…",
    "Phones on silent, please",
    "Fluffing the cushions",
    "Good things come to those who wait",
    "Warming up the projector",
    "Getting the picture just right",
    "Almost showtime",
    "Popcorn: salted or sweet?",
    "Pulling the curtains back",
    "Settle in, this one's worth it",
    "Lining up the opening shot",
    "Cue the opening credits…",
    "Blanket? Snacks? Perfect",
    "The good bit is coming",
    "Tuning the sound just for you",
    "One moment, the stars are getting ready",
    "Buffering the butterflies",
    "Feet up, it's nearly time",
    "Quiet on set…",
    "Your front-row seat is ready",
    "Polishing every pixel",
)

/** A fun line that changes every few seconds (shuffled, no repeats until all have been shown). */
@Composable
fun RotatingLine(modifier: Modifier = Modifier, everyMs: Long = 3_600) {
    var line by remember { mutableStateOf(WaitingLines.random()) }
    LaunchedEffect(Unit) {
        var deck = WaitingLines.shuffled().filter { it != line }
        while (true) {
            delay(everyMs)
            if (deck.isEmpty()) deck = WaitingLines.shuffled().filter { it != line }
            line = deck.first()
            deck = deck.drop(1)
        }
    }
    AnimatedContent(
        targetState = line,
        transitionSpec = {
            (fadeIn(tween(500, delayMillis = 150)) + slideInVertically(tween(500)) { it / 3 }) togetherWith
                (fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it / 3 })
        },
        label = "line",
        modifier = modifier,
    ) { text ->
        Text(text, color = ZappColors.Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * Full-screen "starting" page for films and episodes: the title's artwork dimmed behind the
 * animated Axon mark, the title, and a rotating fun line. Shown from choosing play until the first
 * picture appears, so it reads as one smooth start rather than a list of loading steps.
 */
/** The largest version of a known artwork link (Cinemeta/metahub and TMDB), for full-screen use. */
fun hiRes(url: String?): String? = url
    ?.replace("/background/medium/", "/background/large/")
    ?.replace("/background/small/", "/background/large/")
    ?.replace(Regex("image\\.tmdb\\.org/t/p/w\\d+/"), "image.tmdb.org/t/p/original/")

@Composable
fun StartingScreen(background: String?, title: String, subtitle: String? = null, status: String? = null) {
    val tint = rememberArtColor(background)
    val big = remember(background) { hiRes(background) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (background != null) {
            AsyncImage(
                model = big,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.34f,
                modifier = Modifier.fillMaxSize().drift(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0x66000000), Color(0xEB000000)))),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZappMarkAnimated(58.dp, glow = tint)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = ZappColors.Dim, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.height(18.dp))
            RotatingLine()
            status?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = ZappColors.Faint, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

/**
 * Shown when Axon opens: the logo builds itself (see [LaunchLogo]) and, if switched on, a short
 * chime plays. [onSettled] comes once the logo has finished its main movement (Home starts
 * loading behind then, so the two don't compete for the same frames); [ready] lets it finish
 * early once the minimum has passed.
 */
@Composable
fun LaunchScreen(sound: Boolean, ready: () -> Boolean, onSettled: () -> Unit = {}, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fade = remember { Animatable(1f) }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (sound) {
            runCatching {
                android.media.MediaPlayer.create(context, com.zapp.tv.R.raw.zapp_chime)?.apply {
                    setVolume(0.55f, 0.55f)
                    setOnCompletionListener { it.release() }
                    start()
                }
            }
        }
        launch { clock.animateTo(1800f, tween(1800, easing = LinearEasing)) }
        delay(1250)
        onSettled()
        // At least about 2.5 s; then until Home has loaded (up to 8 s in all).
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1_200 || (!ready() && System.currentTimeMillis() - start < 6_700)) delay(100)
        fade.animateTo(0f, tween(450))
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = fade.value
                val k = 1f + (1f - fade.value) * 0.04f
                scaleX = k
                scaleY = k
            }
            .background(ZappColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        AmbientGlow(0.8f)
        LaunchLogo(clock = { clock.value }, height = 66.dp)
    }
}
