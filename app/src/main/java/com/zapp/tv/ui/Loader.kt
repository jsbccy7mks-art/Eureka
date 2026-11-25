package com.zapp.tv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The Zapp mark, breathing, with a glow behind it and a bright arc circling it.
 * Used wherever something is starting, so waiting feels like part of the show.
 */
@Composable
fun ZappLoader(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "loader")
    val breathe by t.animateFloat(
        0.93f,
        1.04f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val glow by t.animateFloat(
        0.25f,
        0.6f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")

    Box(modifier.size(size * 2.1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f
            // Soft glow.
            drawCircle(
                Brush.radialGradient(
                    listOf(ZappColors.Brand1.copy(alpha = glow), ZappColors.Brand2.copy(alpha = glow * 0.35f), Color.Transparent),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
            // Circling arc.
            val ringR = r * 0.78f
            val stroke = r * 0.045f
            drawCircle(Color.White.copy(alpha = 0.07f), radius = ringR, center = c, style = Stroke(stroke))
            rotate(spin, c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.55f to ZappColors.Brand1,
                        1f to ZappColors.Brand2,
                        center = c,
                    ),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        LogoMark(
            size,
            Modifier.graphicsLayer {
                scaleX = breathe
                scaleY = breathe
            },
        )
    }
}

/**
 * Full-screen "starting" page for films and episodes: the title's artwork dimmed behind the
 * animated Zapp mark. Shown from choosing play until the first picture appears, so it reads as one
 * smooth start rather than a list of loading steps.
 */
@Composable
fun StartingScreen(background: String?, title: String, subtitle: String? = null, status: String? = null) {
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
                alpha = 0.32f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0x66000000), Color(0xE6000000)))),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZappLoader(60.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = ZappColors.Dim, fontSize = 13.sp, maxLines = 1)
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = ZappColors.Faint, fontSize = 11.5.sp, maxLines = 1)
            }
        }
    }
}
