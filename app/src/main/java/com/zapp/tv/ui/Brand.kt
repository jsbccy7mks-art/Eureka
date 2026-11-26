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
import androidx.compose.ui.graphics.drawscope.clipPath
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
 * The Axon brand, drawn from shapes made once: the mark (a soft play triangle sliced into four
 * pieces by an X, in the theme's gradient), the "axon" lettering (its x in the gradient too) and
 * the two side by side. Everything is in fixed units and only scaled when drawn, so nothing is
 * rebuilt frame to frame.
 */
object BrandArt {
    /** The mark's box, in units. */
    const val W = 82f
    const val H = 102f

    val triangle: Path by lazy { Path().apply { moveTo(7f, 7f); lineTo(75f, 51f); lineTo(7f, 95f); close() } }

    // Where the X crosses, and how far each piece's edge sits from its line (half the cut).
    private const val CX = 31f
    private const val CY = 51f
    private const val D = 3.54f
    private const val R = 90f

    /** The four pieces' areas: top, right, bottom, left. */
    val pieces: List<Path> by lazy {
        listOf(
            listOf(CX to CY - D, CX - R to CY - D - R, CX + R to CY - D - R),
            listOf(CX + D to CY, CX + D + R to CY - R, CX + D + R to CY + R),
            listOf(CX to CY + D, CX + R to CY + D + R, CX - R to CY + D + R),
            listOf(CX - D to CY, CX - D - R to CY + R, CX - D - R to CY - R),
        ).map { pts ->
            Path().apply {
                moveTo(pts[0].first, pts[0].second)
                pts.drop(1).forEach { lineTo(it.first, it.second) }
                close()
            }
        }
    }

    /** The way each piece faces. */
    val directions = listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f))
    val pivot = Offset(CX, CY)

    /** "axon" at size 100, baseline at 0 (x 2.8–245.9, y -56.6–0.8): all but the x, and the x. */
    val word: Path by lazy { PathParser().parsePathString(WORD).toPath() }
    val wordX: Path by lazy { PathParser().parsePathString(WORD_X).toPath() }
    val xStart = Offset(62f, -56f)
    val xEnd = Offset(124f, 0f)

    // The mark and the name side by side (as on the TV's banner).
    const val LockW = 371f
    const val LockH = 102f
    const val WordX = 101.6f
    const val WordBase = 81.57f
    const val WordK = 1.0956f

    private const val WORD = "M27.4 -56.6Q33.3 -56.6 37.8 -54.2Q42.2 -51.8 44.6 -47.9V-55.8H61.7V0H44.6V-7.9Q42.1 -4 37.7 -1.6Q33.2 0.8 27.3 0.8Q20.5 0.8 14.9 -2.8Q9.3 -6.3 6.1 -12.9Q2.8 -19.4 2.8 -28Q2.8 -36.6 6.1 -43.1Q9.3 -49.6 14.9 -53.1Q20.5 -56.6 27.4 -56.6ZM32.4 -41.7Q27.3 -41.7 23.8 -38Q20.2 -34.4 20.2 -28Q20.2 -21.6 23.8 -17.9Q27.3 -14.1 32.4 -14.1Q37.5 -14.1 41 -17.8Q44.6 -21.5 44.6 -27.9Q44.6 -34.3 41 -38Q37.5 -41.7 32.4 -41.7ZM125.4 -27.9Q125.4 -36.5 129.2 -43Q133 -49.6 139.6 -53.1Q146.2 -56.6 154.4 -56.6Q162.6 -56.6 169.2 -53.1Q175.8 -49.6 179.6 -43Q183.4 -36.5 183.4 -27.9Q183.4 -19.3 179.5 -12.8Q175.7 -6.2 169 -2.7Q162.4 0.8 154.2 0.8Q146 0.8 139.4 -2.7Q132.9 -6.2 129.1 -12.7Q125.4 -19.2 125.4 -27.9ZM166 -27.9Q166 -34.6 162.6 -38.2Q159.3 -41.8 154.4 -41.8Q149.4 -41.8 146.1 -38.2Q142.8 -34.7 142.8 -27.9Q142.8 -21.2 146 -17.6Q149.3 -14 154.2 -14Q159.1 -14 162.5 -17.6Q166 -21.2 166 -27.9ZM245.9 -32.6V0H228.9V-30.3Q228.9 -35.9 226 -39Q223.1 -42.1 218.2 -42.1Q213.3 -42.1 210.4 -39Q207.5 -35.9 207.5 -30.3V0H190.4V-55.8H207.5V-48.4Q210.1 -52.1 214.5 -54.2Q218.9 -56.4 224.4 -56.4Q234.2 -56.4 240 -50.1Q245.9 -43.7 245.9 -32.6Z"
    private const val WORD_X = "M105 0 94.3 -15.5 85.3 0H66.8L85.2 -28.5L66.3 -55.8H85.5L96.2 -40.4L105.2 -55.8H123.7L105 -27.7L124.2 0Z"
}

/** Eases past the end a little and settles back. */
val BackOut = Easing { t ->
    val c = 1.70158f
    val x = t - 1f
    1f + (c + 1f) * x * x * x + c * x * x
}

/** Fast, then a long gentle settle. */
val SettleOut = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) * (1f - t) }

fun brandBrush(b1: Color, b2: Color) = Brush.linearGradient(listOf(b1, b2), start = Offset.Zero, end = Offset(BrandArt.W, BrandArt.H))

fun xBrush(b1: Color, b2: Color) = Brush.linearGradient(listOf(b1, b2), start = BrandArt.xStart, end = BrandArt.xEnd)

/**
 * The mark in units. [spread] moves the four pieces apart (0 = together), [turn] turns each a
 * little about the centre, [alpha] fades them.
 */
fun DrawScope.drawMark(brush: Brush, spread: Float = 0f, turn: Float = 0f, alpha: Float = 1f) {
    BrandArt.pieces.forEachIndexed { i, area ->
        val d = BrandArt.directions[i]
        translate(d.x * spread, d.y * spread) {
            rotate(if (i % 2 == 0) turn else -turn, pivot = BrandArt.pivot) {
                clipPath(area) {
                    drawPath(BrandArt.triangle, brush, alpha = alpha)
                    drawPath(BrandArt.triangle, brush, alpha = alpha, style = Stroke(14f, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** The name, placed beside the mark (lockup units), its x in [xb]. */
fun DrawScope.drawLockWord(color: Color, xb: Brush, alpha: Float = 1f) {
    withTransform({
        translate(BrandArt.WordX, BrandArt.WordBase)
        scale(BrandArt.WordK, BrandArt.WordK, pivot = Offset.Zero)
    }) {
        drawPath(BrandArt.word, color, alpha = alpha)
        drawPath(BrandArt.wordX, xb, alpha = alpha)
    }
}

/** Just the mark, [height] tall. */
@Composable
fun SparkMark(height: Dp, modifier: Modifier = Modifier) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Spacer(
        modifier
            .size(height * (BrandArt.W / BrandArt.H), height)
            .drawWithCache {
                val brush = brandBrush(b1, b2)
                val k = this.size.height / BrandArt.H
                onDrawBehind { scale(k, pivot = Offset.Zero) { drawMark(brush) } }
            },
    )
}

/** The app icon: the mark on a dark rounded tile with a gradient edge. */
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
                        translate(49.5f * k, 39.6f * k)
                        scale(0.988f * k, 0.988f * k, pivot = Offset.Zero)
                    }) { drawMark(brush) }
                }
            },
    )
}

/** The mark and the name side by side; [height] is the mark's height. */
@Composable
fun ZappLogo(height: Dp, modifier: Modifier = Modifier, wordColor: Color = ZappColors.Text) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Spacer(
        modifier
            .size(height * (BrandArt.LockW / BrandArt.H), height * (BrandArt.LockH / BrandArt.H))
            .drawWithCache {
                val brush = brandBrush(b1, b2)
                val xb = xBrush(b1, b2)
                val k = this.size.width / BrandArt.LockW
                onDrawBehind {
                    scale(k, pivot = Offset.Zero) {
                        drawMark(brush)
                        drawLockWord(wordColor, xb)
                    }
                }
            },
    )
}

/** Just the name, at [fontSize]; its box is the small letters' height, so it lines up in a row. */
@Composable
fun WordMark(fontSize: Dp, modifier: Modifier = Modifier, color: Color = ZappColors.Text) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Spacer(
        modifier
            .size(fontSize * 2.44f, fontSize * 0.6f)
            .drawWithCache {
                val k = this.size.height / 60f
                val xb = xBrush(b1, b2)
                onDrawBehind {
                    withTransform({
                        scale(k, k, pivot = Offset.Zero)
                        translate(-2.8f, 58f)
                    }) {
                        drawPath(BrandArt.word, color)
                        drawPath(BrandArt.wordX, xb)
                    }
                }
            },
    )
}

private fun Size.middle() = Offset(width / 2f, height / 2f)

/**
 * The mark, alive: its pieces come together, a soft glow breathes behind it (tinted by [glow], the
 * poster's colour, when given) and a band of light passes over it now and then. Used wherever
 * something is on its way. Only opacity, scale and one small redraw change per frame.
 */
@Composable
fun ZappMarkAnimated(size: Dp, modifier: Modifier = Modifier, glow: Color? = null) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(700, easing = SettleOut)) }
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
                .size(size * 2.2f)
                .graphicsLayer { alpha = breathe.value * pop.value }
                .drawWithCache {
                    val r = this.size.minDimension / 2f
                    val brush = Brush.radialGradient(listOf(glowColor, b2.copy(alpha = 0.35f), Color.Transparent), center = this.size.middle(), radius = r)
                    onDrawBehind { drawCircle(brush, r) }
                },
        )
        Spacer(
            Modifier
                .size(markHeight * (BrandArt.W / BrandArt.H), markHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val brush = brandBrush(b1, b2)
                    val k = this.size.height / BrandArt.H
                    onDrawBehind {
                        val p = pop.value
                        scale(k, pivot = Offset.Zero) {
                            drawMark(brush, spread = 16f * (1f - p), turn = 10f * (1f - p), alpha = (p * 2f).coerceAtMost(1f))
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

private fun seg(clock: Float, from: Float, length: Float, easing: Easing = LinearEasing) =
    easing.transform(((clock - from) / length).coerceIn(0f, 1f))

/**
 * The opening: the four pieces of the mark fly in and snap together with a flash, the name slides
 * out from behind it, and a band of light passes over the whole logo. [clock] is the time into the
 * animation in ms (0–1800). Drawn from shapes made once; [height] is the mark's.
 */
@Composable
fun LaunchLogo(clock: () -> Float, height: Dp, modifier: Modifier = Modifier) {
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    val text = ZappColors.Text
    val w = height * (BrandArt.LockW / BrandArt.H)
    val h = height * (BrandArt.LockH / BrandArt.H)
    Box(modifier.size(w, h)) {
        // Light behind the mark: comes up with it, flares as the pieces meet.
        Spacer(
            Modifier
                .layout { m, _ ->
                    val hp = height.roundToPx()
                    val gs = (hp * 2.6f).toInt()
                    val p = m.measure(Constraints.fixed(gs, gs))
                    layout(0, 0) { p.place((hp * 0.4f).toInt() - gs / 2, hp / 2 - gs / 2) }
                }
                .graphicsLayer {
                    val c = clock()
                    val flash = sin(PI * seg(c, 520f, 600f)).toFloat()
                    alpha = (0.45f * seg(c, 0f, 500f) + 0.5f * flash).coerceIn(0f, 1f)
                    val k = 0.9f + 0.25f * flash
                    scaleX = k
                    scaleY = k
                }
                .drawWithCache {
                    val r = this.size.minDimension / 2f
                    val brush = Brush.radialGradient(listOf(b1.copy(alpha = 0.6f), b2.copy(alpha = 0.18f), Color.Transparent), center = this.size.middle(), radius = r)
                    onDrawBehind { drawCircle(brush, r) }
                },
        )
        Spacer(
            Modifier
                .size(w, h)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val brush = brandBrush(b1, b2)
                    val xb = xBrush(b1, b2)
                    val k = this.size.width / BrandArt.LockW
                    onDrawBehind {
                        val c = clock()
                        scale(k, pivot = Offset.Zero) {
                            // 1. The pieces fly in and meet.
                            val p = seg(c, 0f, 640f, SettleOut)
                            val a = seg(c, 0f, 260f)
                            if (a > 0f) drawMark(brush, spread = 46f * (1f - p), turn = 24f * (1f - p), alpha = a)
                            // 2. The name slides out from behind the mark.
                            val n = seg(c, 520f, 680f, FastOutSlowInEasing)
                            if (n > 0f) {
                                clipRect(left = 90f, top = -4f, right = BrandArt.LockW + 4f, bottom = BrandArt.LockH + 4f) {
                                    translate(-70f * (1f - n), 0f) { drawLockWord(text, xb, alpha = n) }
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
