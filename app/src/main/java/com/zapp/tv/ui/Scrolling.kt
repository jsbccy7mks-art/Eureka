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
