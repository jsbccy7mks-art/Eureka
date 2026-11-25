@file:OptIn(ExperimentalFoundationApi::class)

package com.zapp.tv.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

private val TvEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * TV-style scrolling: the focused item glides to a fixed spot ([pivot] from the start of the list)
 * instead of the list jumping only when focus reaches the edge. Near the ends the list stops and
 * focus moves on by itself, as on Google TV.
 */
private class PivotSpec(private val pivotPx: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 260, easing = TvEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Things bigger than the window (or nearly) just line up with the pivot.
        return offset - pivotPx
    }
}

@Composable
fun PivotScrolling(pivot: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(px) { PivotSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** A scroller that doesn't move by itself when focus changes; the screen scrolls it on purpose. */
private object NoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
fun NoAutoScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollSpec, content = content)
}

/** Scroll only as far as needed to show the focused item (for short lists such as season tabs). */
private object MinimalSpec : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 220, easing = TvEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0 && end <= containerSize -> 0f
            offset < 0 -> offset
            else -> end - containerSize
        }
    }
}

@Composable
fun MinimalScrolling(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalSpec, content = content)
}
