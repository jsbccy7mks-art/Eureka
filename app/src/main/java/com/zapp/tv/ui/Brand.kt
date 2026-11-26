package com.zapp.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The Zapp brand, drawn from shapes made once: the spark (a soft play triangle with a lightning
 * bolt cut through it, in the theme's gradient), the "zapp" lettering, and the two side by side.
 * Everything is in fixed units and only scaled when drawn, so nothing is rebuilt frame to frame.
 */
object BrandArt {
    /** The spark's box, in units. */
    const val W = 82f
    const val H = 102f

    val triangle: Path by lazy { Path().apply { moveTo(7f, 7f); lineTo(75f, 51f); lineTo(7f, 95f); close() } }
    val bolt: Path by lazy {
        Path().apply { moveTo(27f, 25f); lineTo(43f, 53f); lineTo(29f, 53f); lineTo(37f, 77f); lineTo(15f, 45f); lineTo(29f, 45f); close() }
    }

    /** "zapp" at size 100, baseline at 0 (it spans x 3.1–244.1, y -56.6–26.6). */
    val word: Path by lazy { PathParser().parsePathString(WORD).toPath() }

    // The spark and the name side by side (as on the TV's banner).
    const val LockW = 359.6f
    const val LockH = 108.7f
    const val WordX = 101.4f
    const val WordBase = 80.5f
    const val WordK = 1.0578f

    private const val WORD = "M22.2 -14.1H46.4V0H3.1V-13.6L26.3 -41.7H3.3V-55.8H45.9V-42.2ZM74.9 -56.6Q80.8 -56.6 85.2 -54.2Q89.7 -51.8 92.1 -47.9V-55.8H109.2V0H92.1V-7.9Q89.6 -4 85.1 -1.6Q80.7 0.8 74.8 0.8Q68 0.8 62.4 -2.8Q56.8 -6.3 53.5 -12.9Q50.3 -19.4 50.3 -28Q50.3 -36.6 53.5 -43.1Q56.8 -49.6 62.4 -53.1Q68 -56.6 74.9 -56.6ZM79.9 -41.7Q74.8 -41.7 71.2 -38Q67.7 -34.4 67.7 -28Q67.7 -21.6 71.2 -17.9Q74.8 -14.1 79.9 -14.1Q85 -14.1 88.5 -17.8Q92.1 -21.5 92.1 -27.9Q92.1 -34.3 88.5 -38Q85 -41.7 79.9 -41.7ZM153.7 -56.6Q160.6 -56.6 166.2 -53.1Q171.8 -49.6 175.1 -43.1Q178.3 -36.6 178.3 -28Q178.3 -19.4 175.1 -12.9Q171.8 -6.3 166.2 -2.8Q160.6 0.8 153.7 0.8Q147.9 0.8 143.5 -1.6Q139 -4 136.5 -7.8V26.6H119.4V-55.8H136.5V-47.9Q139 -51.8 143.4 -54.2Q147.8 -56.6 153.7 -56.6ZM148.6 -41.7Q143.5 -41.7 140 -38Q136.4 -34.3 136.4 -27.9Q136.4 -21.5 140 -17.8Q143.5 -14.1 148.6 -14.1Q153.7 -14.1 157.3 -17.9Q160.9 -21.6 160.9 -28Q160.9 -34.4 157.4 -38Q153.8 -41.7 148.6 -41.7ZM219.5 -56.6Q226.4 -56.6 232 -53.1Q237.6 -49.6 240.8 -43.1Q244.1 -36.6 244.1 -28Q244.1 -19.4 240.8 -12.9Q237.6 -6.3 232 -2.8Q226.4 0.8 219.5 0.8Q213.7 0.8 209.2 -1.6Q204.8 -4 202.3 -7.8V26.6H185.2V-55.8H202.3V-47.9Q204.8 -51.8 209.2 -54.2Q213.6 -56.6 219.5 -56.6ZM214.4 -41.7Q209.3 -41.7 205.7 -38Q202.2 -34.3 202.2 -27.9Q202.2 -21.5 205.7 -17.8Q209.3 -14.1 214.4 -14.1Q219.5 -14.1 223.1 -17.9Q226.7 -21.6 226.7 -28Q226.7 -34.4 223.1 -38Q219.6 -41.7 214.4 -41.7Z"
}

/** Eases past the end a little and settles back. */
val BackOut = Easing { t ->
    val c = 1.70158f
    val x = t - 1f
    1f + (c + 1f) * x * x * x + c * x * x
}

fun brandBrush(b1: Color, b2: Color) = Brush.linearGradient(listOf(b1, b2), start = Offset.Zero, end = Offset(BrandArt.W, BrandArt.H))

/** The spark in units. The bolt is cut out (see-through) unless [boltColor] is given. */
fun DrawScope.drawSpark(brush: Brush, bolt: Float = 1f, boltScale: Float = 1f, boltColor: Color? = null) {
    drawPath(BrandArt.triangle, brush)
    drawPath(BrandArt.triangle, brush, style = Stroke(14f, join = StrokeJoin.Round))
    if (bolt > 0f) {
        scale(boltScale, pivot = Offset(29f, 51f)) {
            if (boltColor != null) drawPath(BrandArt.bolt, boltColor, alpha = bolt)
            else drawPath(BrandArt.bolt, Color.Black, alpha = bolt, blendMode = BlendMode.DstOut)
        }
    }
}

/** The name, placed beside the spark (lockup units). */
fun DrawScope.drawLockWord(color: Color, alpha: Float = 1f) {
    withTransform({
        translate(BrandArt.WordX, BrandArt.WordBase)
        scale(BrandArt.WordK, BrandArt.WordK, pivot = Offset.Zero)
    }) { drawPath(BrandArt.word, color, alpha = alpha) }
}

/** Just the spark, [height] tall. */
@Composable
fun SparkMark(height: Dp, modifier: Modifier = Modifier) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Spacer(
        modifier
            .size(height * (BrandArt.W / BrandArt.H), height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val brush = brandBrush(b1, b2)
                val k = this.size.height / BrandArt.H
                onDrawBehind { scale(k, pivot = Offset.Zero) { drawSpark(brush) } }
            },
    )
}

/** The app icon: the spark on a dark rounded tile with a gradient edge. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Spacer(
        modifier
            .size(size)
            .drawWithCache {
                val s = this.size.width
                val k = s / 180f
                val tile = Brush.linearGradient(listOf(Color(0xFF16162A), Color(0xFF0B0B14)), Offset.Zero, Offset(s, s))
                val edge = Brush.linearGradient(listOf(b1, b2), Offset.Zero, Offset(s, s))
                val brush = brandBrush(b1, b2)
                val ew = max(1f, 3f * k)
                onDrawBehind {
                    drawRoundRect(tile, cornerRadius = CornerRadius(52f * k))
                    drawRoundRect(
                        edge,
                        topLeft = Offset(ew / 2f, ew / 2f),
                        size = Size(s - ew, s - ew),
                        cornerRadius = CornerRadius(52f * k - ew / 2f),
                        style = Stroke(ew),
                    )
                    withTransform({
                        translate(55f * k, 39f * k)
                        scale(k, k, pivot = Offset.Zero)
                    }) { drawSpark(brush, boltColor = Color(0xFF111120)) }
                }
            },
    )
}

/** The spark and the name side by side; [height] is the spark's height. */
@Composable
fun ZappLogo(height: Dp, modifier: Modifier = Modifier, wordColor: Color = ZappColors.Text) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Spacer(
        modifier
            .size(height * (BrandArt.LockW / BrandArt.H), height * (BrandArt.LockH / BrandArt.H))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val brush = brandBrush(b1, b2)
                val k = this.size.width / BrandArt.LockW
                onDrawBehind {
                    scale(k, pivot = Offset.Zero) {
                        drawSpark(brush)
                        drawLockWord(wordColor)
                    }
                }
            },
    )
}

/** Just the name, at [fontSize]; its box is centred on the small letters so it lines up in a row. */
@Composable
fun WordMark(fontSize: Dp, modifier: Modifier = Modifier, color: Color = ZappColors.Text) {
    Spacer(
        modifier
            .size(fontSize * 2.42f, fontSize * 1.09f)
            .drawWithCache {
                val k = this.size.height / 109f
                onDrawBehind {
                    withTransform({
                        scale(k, k, pivot = Offset.Zero)
                        translate(-3.1f, 82.4f)
                    }) { drawPath(BrandArt.word, color) }
                }
            },
    )
}

/**
 * The spark, alive: it pops in, a soft glow breathes behind it (tinted by [glow], the poster's
 * colour, when given) and a band of light passes over it now and then. Used wherever something is
 * on its way. Only opacity, scale and one small redraw change per frame.
 */
@Composable
fun ZappMarkAnimated(size: Dp, modifier: Modifier = Modifier, glow: Color? = null) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(650, easing = BackOut)) }
    val t = rememberInfiniteTransition(label = "mark")
    val sweep = t.animateFloat(-0.7f, 1.7f, infiniteRepeatable(tween(2800, delayMillis = 900, easing = LinearEasing)), label = "sweep")
    val breathe = t.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val glowColor = glow ?: ZappColors.Brand1
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    val markHeight = size * 0.92f

    Box(modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = breathe.value * pop.value.coerceIn(0f, 1f) }
                .drawWithCache {
                    val r = this.size.minDimension / 2f
                    val brush = Brush.radialGradient(listOf(glowColor, b2.copy(alpha = 0.35f), Color.Transparent), center = this.size.center(), radius = r)
                    onDrawBehind { drawCircle(brush, r) }
                },
        )
        Spacer(
            Modifier
                .size(markHeight * (BrandArt.W / BrandArt.H), markHeight)
                .graphicsLayer {
                    val p = pop.value
                    alpha = (p * 2.5f).coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * p
                    scaleY = 0.6f + 0.4f * p
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    val brush = brandBrush(b1, b2)
                    val k = this.size.height / BrandArt.H
                    onDrawBehind {
                        scale(k, pivot = Offset.Zero) {
                            drawSpark(brush)
                            val x = sweep.value * BrandArt.W
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.4f), Color.Transparent),
                                    start = Offset(x - 30f, 0f),
                                    end = Offset(x + 10f, 40f),
                                ),
                                size = Size(BrandArt.W, BrandArt.H),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                    }
                },
        )
    }
}

private fun Size.center() = Offset(width / 2f, height / 2f)

private fun seg(clock: Float, from: Float, length: Float, easing: Easing = LinearEasing) =
    easing.transform(((clock - from) / length).coerceIn(0f, 1f))

/**
 * The opening: the spark spins and settles into place, the bolt strikes through it with a flash,
 * the name slides out from behind it, and a band of light passes over the whole logo. [clock] is
 * the time into the animation in ms (0–1800). Drawn from shapes made once; [height] is the spark's.
 */
@Composable
fun LaunchLogo(clock: () -> Float, height: Dp, modifier: Modifier = Modifier) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    val text = ZappColors.Text
    val w = height * (BrandArt.LockW / BrandArt.H)
    val h = height * (BrandArt.LockH / BrandArt.H)
    Box(modifier.size(w, h)) {
        // Light behind the spark: comes up with it, flares as the bolt strikes.
        Spacer(
            Modifier
                // Larger than the logo, centred on the spark.
                .layout { m, _ ->
                    val hp = height.roundToPx()
                    val gs = (hp * 2.6f).toInt()
                    val p = m.measure(Constraints.fixed(gs, gs))
                    layout(0, 0) { p.place((hp * 0.4f).toInt() - gs / 2, hp / 2 - gs / 2) }
                }
                .graphicsLayer {
                    val c = clock()
                    val flash = sin(PI * seg(c, 560f, 600f)).toFloat()
                    alpha = (0.45f * seg(c, 0f, 500f) + 0.5f * flash).coerceIn(0f, 1f)
                    val k = 0.9f + 0.25f * flash
                    scaleX = k
                    scaleY = k
                }
                .drawWithCache {
                    val r = this.size.minDimension / 2f
                    val brush = Brush.radialGradient(listOf(b1.copy(alpha = 0.6f), b2.copy(alpha = 0.18f), Color.Transparent), center = this.size.center(), radius = r)
                    onDrawBehind { drawCircle(brush, r) }
                },
        )
        Spacer(
            Modifier
                .size(w, h)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val brush = brandBrush(b1, b2)
                    val k = this.size.width / BrandArt.LockW
                    onDrawBehind {
                        val c = clock()
                        scale(k, pivot = Offset.Zero) {
                            // 1. The spark.
                            val p = seg(c, 0f, 720f, BackOut)
                            val a = seg(c, 0f, 280f)
                            if (a > 0f) {
                                withTransform({
                                    rotate(-16f * (1f - p), pivot = Offset(41f, 51f))
                                    scale(0.5f + 0.5f * p, pivot = Offset(41f, 51f))
                                }) {
                                    val b = seg(c, 430f, 240f, FastOutSlowInEasing)
                                    // drawn with its own fade by layering: the triangle alone, then the cut
                                    drawPath(BrandArt.triangle, brush, alpha = a)
                                    drawPath(BrandArt.triangle, brush, alpha = a, style = Stroke(14f, join = StrokeJoin.Round))
                                    if (b > 0f) {
                                        scale(1.9f - 0.9f * b, pivot = Offset(29f, 51f)) {
                                            drawPath(BrandArt.bolt, Color.Black, alpha = b, blendMode = BlendMode.DstOut)
                                        }
                                    }
                                }
                            }
                            // 2. The name slides out from behind the spark.
                            val n = seg(c, 560f, 680f, FastOutSlowInEasing)
                            if (n > 0f) {
                                clipRect(left = 90f, top = 0f, right = BrandArt.LockW + 4f, bottom = BrandArt.LockH + 4f) {
                                    translate(-70f * (1f - n), 0f) { drawLockWord(text, alpha = n) }
                                }
                            }
                            // 3. A band of light across everything.
                            val g = seg(c, 1080f, 720f)
                            if (g > 0f && g < 1f) {
                                val x = -60f + g * (BrandArt.LockW + 120f)
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                        start = Offset(x - 40f, 0f),
                                        end = Offset(x + 14f, 54f),
                                    ),
                                    size = Size(BrandArt.LockW, BrandArt.LockH),
                                    blendMode = BlendMode.SrcAtop,
                                )
                            }
                        }
                    }
                },
        )
    }
}
