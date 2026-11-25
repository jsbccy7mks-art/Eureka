package com.zapp.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Line icons drawn in code, so they stay crisp at any size and match each other. */
enum class Glyph { Home, Live, Guide, Movies, Series, Library, Search, Settings, Clock }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.Home -> {
                val p = Path().apply {
                    moveTo(w * 0.14f, w * 0.46f)
                    lineTo(w * 0.5f, w * 0.15f)
                    lineTo(w * 0.86f, w * 0.46f)
                    moveTo(w * 0.24f, w * 0.38f)
                    lineTo(w * 0.24f, w * 0.85f)
                    lineTo(w * 0.76f, w * 0.85f)
                    lineTo(w * 0.76f, w * 0.38f)
                    moveTo(w * 0.42f, w * 0.85f)
                    lineTo(w * 0.42f, w * 0.62f)
                    lineTo(w * 0.58f, w * 0.62f)
                    lineTo(w * 0.58f, w * 0.85f)
                }
                drawPath(p, color, style = stroke)
            }
            Glyph.Live -> {
                drawRoundRect(color, Offset(w * 0.1f, w * 0.3f), Size(w * 0.8f, w * 0.56f), CornerRadius(w * 0.1f), style = stroke)
                line(color, w, 0.36f, 0.12f, 0.5f, 0.3f, stroke)
                line(color, w, 0.64f, 0.12f, 0.5f, 0.3f, stroke)
            }
            Glyph.Guide -> {
                drawRoundRect(color, Offset(w * 0.1f, w * 0.16f), Size(w * 0.8f, w * 0.68f), CornerRadius(w * 0.1f), style = stroke)
                line(color, w, 0.1f, 0.39f, 0.9f, 0.39f, stroke)
                line(color, w, 0.1f, 0.61f, 0.9f, 0.61f, stroke)
                line(color, w, 0.36f, 0.16f, 0.36f, 0.84f, stroke)
            }
            Glyph.Movies -> {
                drawRoundRect(color, Offset(w * 0.14f, w * 0.2f), Size(w * 0.72f, w * 0.6f), CornerRadius(w * 0.08f), style = stroke)
                val play = Path().apply {
                    moveTo(w * 0.43f, w * 0.38f)
                    lineTo(w * 0.62f, w * 0.5f)
                    lineTo(w * 0.43f, w * 0.62f)
                    close()
                }
                drawPath(play, color)
            }
            Glyph.Series -> {
                drawRoundRect(color, Offset(w * 0.22f, w * 0.3f), Size(w * 0.64f, w * 0.52f), CornerRadius(w * 0.08f), style = stroke)
                line(color, w, 0.14f, 0.7f, 0.14f, 0.22f, stroke)
                line(color, w, 0.14f, 0.22f, 0.74f, 0.22f, stroke)
            }
            Glyph.Library -> {
                line(color, w, 0.22f, 0.18f, 0.22f, 0.84f, stroke)
                line(color, w, 0.42f, 0.18f, 0.42f, 0.84f, stroke)
                line(color, w, 0.6f, 0.24f, 0.78f, 0.82f, stroke)
            }
            Glyph.Search -> {
                drawCircle(color, w * 0.27f, Offset(w * 0.43f, w * 0.43f), style = stroke)
                line(color, w, 0.63f, 0.63f, 0.86f, 0.86f, stroke)
            }
            Glyph.Settings -> {
                line(color, w, 0.12f, 0.28f, 0.88f, 0.28f, stroke)
                line(color, w, 0.12f, 0.5f, 0.88f, 0.5f, stroke)
                line(color, w, 0.12f, 0.72f, 0.88f, 0.72f, stroke)
                drawCircle(color, w * 0.08f, Offset(w * 0.32f, w * 0.28f))
                drawCircle(color, w * 0.08f, Offset(w * 0.66f, w * 0.5f))
                drawCircle(color, w * 0.08f, Offset(w * 0.42f, w * 0.72f))
            }
            Glyph.Clock -> {
                drawCircle(color, w * 0.4f, Offset(w * 0.5f, w * 0.5f), style = stroke)
                line(color, w, 0.5f, 0.5f, 0.5f, 0.27f, stroke)
                line(color, w, 0.5f, 0.5f, 0.67f, 0.6f, stroke)
            }
        }
    }
}

private fun DrawScope.line(color: Color, w: Float, x1: Float, y1: Float, x2: Float, y2: Float, stroke: Stroke) {
    drawLine(color, Offset(w * x1, w * y1), Offset(w * x2, w * y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
}

@Composable
fun ClockGlyph(color: Color, size: Dp) = GlyphIcon(Glyph.Clock, color, size)
