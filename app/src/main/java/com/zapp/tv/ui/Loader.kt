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

/**
 * The Zapp mark, animated: the tile traces itself in the theme's gradient, fills, and the bolt
 * strikes in. Then it rests with a slow light sweep across it and a soft breathing glow behind,
 * tinted by [glow] (the poster's colour) when given.
 */
@Composable
fun ZappMarkAnimated(size: Dp, modifier: Modifier = Modifier, glow: Color? = null) {
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
    val t = rememberInfiniteTransition(label = "mark")
    val sweep by t.animateFloat(-0.7f, 1.7f, infiniteRepeatable(tween(2600, delayMillis = 700, easing = LinearEasing)), label = "sweep")
    val breathe by t.animateFloat(0.3f, 0.62f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val glowColor = glow ?: ZappColors.Brand1
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2

    Canvas(modifier.size(size * 2.2f)) {
        val d = draw.value
        val s = size.toPx()
        val c = center
        val tl = Offset(c.x - s / 2f, c.y - s / 2f)
        val brand = Brush.linearGradient(listOf(b1, b2), start = tl, end = Offset(tl.x + s, tl.y + s))

        // Glow behind.
        val g = breathe * d
        drawCircle(
            Brush.radialGradient(listOf(glowColor.copy(alpha = g), b2.copy(alpha = g * 0.35f), Color.Transparent), center = c, radius = s * 1.1f),
            radius = s * 1.1f,
            center = c,
        )

        val tile = Path().apply { addRoundRect(RoundRect(tl.x, tl.y, tl.x + s, tl.y + s, CornerRadius(s * 0.28f))) }

        // 1. The outline traces itself.
        val trace = (d / 0.5f).coerceIn(0f, 1f)
        if (trace in 0.001f..0.999f) {
            val pm = PathMeasure()
            pm.setPath(tile, false)
            val seg = Path()
            pm.getSegment(0f, pm.length * trace, seg, true)
            drawPath(seg, brand, style = Stroke(s * 0.04f, cap = StrokeCap.Round))
        }
        // 2. The tile fills.
        val fill = ((d - 0.4f) / 0.3f).coerceIn(0f, 1f)
        if (fill > 0f) drawPath(tile, brand, alpha = fill)

        // 3. The bolt strikes: traced, then filled.
        val bolt = Path().apply {
            moveTo(tl.x + 0.58f * s, tl.y + 0.12f * s)
            lineTo(tl.x + 0.28f * s, tl.y + 0.56f * s)
            lineTo(tl.x + 0.47f * s, tl.y + 0.56f * s)
            lineTo(tl.x + 0.42f * s, tl.y + 0.88f * s)
            lineTo(tl.x + 0.73f * s, tl.y + 0.43f * s)
            lineTo(tl.x + 0.54f * s, tl.y + 0.43f * s)
            lineTo(tl.x + 0.62f * s, tl.y + 0.12f * s)
            close()
        }
        val strike = ((d - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (strike > 0f) {
            val pm = PathMeasure()
            pm.setPath(bolt, false)
            val seg = Path()
            pm.getSegment(0f, pm.length * (strike / 0.6f).coerceAtMost(1f), seg, true)
            drawPath(seg, Color.White, style = Stroke(s * 0.025f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val solid = ((strike - 0.45f) / 0.55f).coerceIn(0f, 1f)
            if (solid > 0f) drawPath(bolt, Color.White, alpha = solid)
        }

        // 4. Resting: a band of light sweeps across the tile.
        if (d >= 1f) {
            clipPath(tile) {
                val x = tl.x + s * sweep
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent),
                        start = Offset(x - s * 0.35f, tl.y),
                        end = Offset(x + s * 0.15f, tl.y + s * 0.5f),
                    ),
                    topLeft = tl,
                    size = Size(s, s),
                )
            }
        }
    }
}

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
 * animated Zapp mark, the title, and a rotating fun line. Shown from choosing play until the first
 * picture appears, so it reads as one smooth start rather than a list of loading steps.
 */
@Composable
fun StartingScreen(background: String?, title: String, subtitle: String? = null, status: String? = null) {
    val tint = rememberArtColor(background)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (background != null) {
            AsyncImage(
                model = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.3f,
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
 * Shown when Zapp opens: the mark draws itself, the name fades in, and (if switched on) a short
 * chime plays, while Home loads behind. [ready] lets it finish early once the minimum has passed.
 */
@Composable
fun LaunchScreen(sound: Boolean, ready: () -> Boolean, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fade = remember { Animatable(1f) }
    val word = remember { Animatable(0f) }
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
        delay(700)
        word.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZappMarkAnimated(80.dp)
            Text(
                "zapp",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = ZappColors.Text,
                modifier = Modifier.graphicsLayer {
                    alpha = word.value
                    translationY = (1f - word.value) * 18f
                },
            )
        }
    }
}
