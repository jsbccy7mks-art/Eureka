package com.zapp.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** The colours and marks of each season's row. */
data class SeasonLook(val ring: Color, val glow: List<Color>, val mark: String, val snow: Boolean, val flake: Color)

fun seasonLook(key: String): SeasonLook = when (key) {
    "christmas" -> SeasonLook(Color(0xFFE8C46A), listOf(Color(0x40C8323C), Color(0x30E8C46A)), "✦", snow = true, flake = Color.White)
    "halloween" -> SeasonLook(Color(0xFFFF9A3C), listOf(Color(0x40FF7A1A), Color(0x2A7B3FA0)), "☾", snow = true, flake = Color(0xFFFFC48A))
    "valentine" -> SeasonLook(Color(0xFFFF7EA8), listOf(Color(0x40FF4F8B), Color(0x26FFB0C8)), "♥", snow = false, flake = Color.White)
    "easter" -> SeasonLook(Color(0xFFB9E37A), listOf(Color(0x30B9E37A), Color(0x26FFE07A)), "✿", snow = false, flake = Color.White)
    else -> SeasonLook(Color(0xFFFFD166), listOf(Color(0x40FFB347), Color(0x2633C3FF)), "☀", snow = false, flake = Color.White)
}

/**
 * The seasonal row's dressing: a warm glow behind it, gentle snow (or embers) drifting over its
 * heading, and a gold focus edge on its cards. Everything else on Home stays as it is. The snow
 * only moves while you're resting; scrolling freezes it so nothing competes for frames.
 */
@Composable
fun SeasonalRow(key: String, on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    val look = seasonLook(key)
    Box {
        // The glow: soft colour behind the row, strongest at the start, fading out smoothly at
        // the top and bottom so it never ends in a line.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(
                Brush.radialGradient(
                    listOf(look.glow[0], Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.2f),
                    radius = size.width * 0.45f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(look.glow[1], Color.Transparent),
                    center = Offset(size.width * 0.7f, size.height * 0.35f),
                    radius = size.width * 0.4f,
                ),
            )
            drawRect(Brush.verticalGradient(*easedMask(start = 0f, end = 0.35f)), blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(*easedMask(start = 1f, end = 0.6f)), blendMode = BlendMode.DstIn)
        }
        CompositionLocalProvider(LocalRingTint provides look.ring) { content() }
        if (look.snow) Snow(look.flake, Modifier.fillMaxWidth().height(46.dp))
    }
}

/** A few soft flakes drifting down over the row's heading. */
@Composable
private fun Snow(color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "snow")
    val clock by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "fall")
    var held by remember { mutableFloatStateOf(0f) }
    val shown = if (Motion.moving) held else clock.also { held = it }
    val flakes = remember {
        val r = java.util.Random(7)
        List(28) { Triple(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.4f) }
    }
    Canvas(modifier) {
        flakes.forEachIndexed { i, (x0, phase, s) ->
            val y = ((phase + shown * (0.6f + s * 0.25f)) % 1f)
            val sway = sin((y * 2f + i) * PI.toFloat()) * 10.dp.toPx()
            val x = x0 * size.width + sway
            val fade = (1f - y) * 0.75f
            drawCircle(color.copy(alpha = 0.55f * fade), radius = s.dp.toPx(), center = Offset(x, y * size.height))
        }
    }
}
