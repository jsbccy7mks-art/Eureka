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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch

private val TvEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * TV-style scrolling: the focused item glides to a fixed spot ([pivot] from the start of the list)
 * instead of the list jumping only when focus reaches the edge. Near the ends the list stops and
 * focus moves on by itself, as on Google TV.
 */
private class PivotSpec(private val pivotPx: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = if (Motion.held) tween(durationMillis = 130, easing = androidx.compose.animation.core.LinearEasing) else tween(durationMillis = 230, easing = TvEasing)

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

/**
 * Rows: focus walks freely across the cards on screen until it's about halfway along; from there
 * the row slides and focus stays at that point, so a couple of upcoming cards are always in view.
 * Going back, focus walks back to the first card's place before the row slides the other way.
 * At either end the row stops, so focus walks the rest of the way to the first or last card.
 */
private class RowSpec(private val startPx: Float, private val middle: Float) : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = if (Motion.held) tween(durationMillis = 130, easing = androidx.compose.animation.core.LinearEasing) else tween(durationMillis = 220, easing = TvEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val farthest = maxOf(startPx, containerSize * middle - size / 2f)
        return when {
            offset < startPx -> offset - startPx
            offset > farthest -> offset - farthest
            else -> 0f
        }
    }
}

/** For horizontal rows; [start] is where the first card sits (the row's start padding). */
@Composable
fun RowScrolling(start: Dp, middle: Float = 0.5f, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { start.toPx() }
    val spec = remember(px, middle) { RowSpec(px, middle) }
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

/** Hide everything above [line] (it stays laid out and focusable, just invisible). */
fun Modifier.fadeAbove(line: Dp): Modifier = drawWithContent {
    // A plain clip: much cheaper than a blended mask, and a clean edge.
    // Only the top edge is cut; the sides and bottom are left open so focus glows aren't clipped.
    val big = 200.dp.toPx()
    clipRect(left = -big, top = line.toPx(), right = size.width + big, bottom = size.height + big) { this@drawWithContent.drawContent() }
}

/** Hide everything left of [line]. */
fun Modifier.fadeLeftOf(line: Dp): Modifier = drawWithContent {
    // Only the left edge is cut; above and below stay open so a focused card's glow isn't sliced off.
    val big = 200.dp.toPx()
    clipRect(left = line.toPx() - 3.dp.toPx(), top = -big, right = size.width + big, bottom = size.height + big) { this@drawWithContent.drawContent() }
}

/**
 * Let a row reach [extra] further left than its slot (under the margin), so the card before the
 * one you're on stays laid out and Left always finds it.
 */
fun Modifier.extendLeft(extra: Dp): Modifier = layout { measurable, constraints ->
    val e = extra.roundToPx()
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth + e else constraints.maxWidth
    val p = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, p.height) { p.place(-e, 0) }
}

/** Up and Down never leave this list (no jumping to the sidebar from the top row). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.trapVertical(): Modifier = this
    .focusProperties {
        exit = { d -> if (d == FocusDirection.Up || d == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default }
    }
    .focusGroup()

/** Like [trapVertical], but Up from the top goes to [up] (the Search and Settings buttons). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.trapVertical(up: FocusRequester?): Modifier = this
    .focusProperties {
        exit = { d ->
            when (d) {
                FocusDirection.Up -> up ?: FocusRequester.Cancel
                FocusDirection.Down -> FocusRequester.Cancel
                else -> FocusRequester.Default
            }
        }
    }
    .focusGroup()

/**
 * The arrow keys never move focus out of this (an overlay over other screens). Only arrows are
 * blocked: something opened on top (the player, a dialog) can still take focus.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties {
        exit = { d ->
            if (d == FocusDirection.Up || d == FocusDirection.Down || d == FocusDirection.Left || d == FocusDirection.Right) {
                FocusRequester.Cancel
            } else {
                FocusRequester.Default
            }
        }
    }
    .focusGroup()

/**
 * A row that remembers which card you're on; Back from further along it scrolls to the start and
 * puts you on the first card (the next Back goes on to whatever the page does with Back).
 */
class RowStart {
    var index = 0
    val first = FocusRequester()
}

@Composable
fun rememberRowStart(): RowStart = remember { RowStart() }

@Composable
fun Modifier.backToStart(row: RowStart, state: androidx.compose.foundation.lazy.LazyListState): Modifier {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this.onPreviewKeyEvent { e ->
        if (e.key != androidx.compose.ui.input.key.Key.Back || row.index <= 0) return@onPreviewKeyEvent false
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
            row.index = 0
            scope.launch {
                state.scrollToItem(0)
                repeat(6) {
                    kotlinx.coroutines.delay(30)
                    if (runCatching { row.first.requestFocus() }.isSuccess) return@launch
                }
            }
        }
        true
    }
}
