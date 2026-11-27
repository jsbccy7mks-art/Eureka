package com.zapp.tv.ui

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import kotlin.math.roundToInt

/**
 * The Glass style: panels made of frosted glass that pick up the colours of the artwork behind
 * them, with a light edge and a soft shadow, floating over full-bleed pictures.
 *
 * Real see-through blur costs a TV box dearly, so the glass works the way it looks: the picture
 * behind the page ([GlassScene]) is blurred once, very small, and each pane shows the part of it
 * that sits behind the pane. Nothing is blurred frame to frame.
 */
object GlassScene {
    /** The picture behind the page (a backdrop link); set by the page showing it. */
    var url by mutableStateOf<String?>(null)
    /** The same picture, tiny and softened, shared by the background and every pane. */
    var image by mutableStateOf<ImageBitmap?>(null)
    /** The picture before, for a gentle cross-fade. */
    var previous by mutableStateOf<ImageBitmap?>(null)
}

/** In Glass there's no sidebar: pages sit this far in from the left edge. */
val GlassInset = 24.dp

/** Glass: stretches a full-width picture out under the left margin too, so it reaches the screen's edge. */
fun Modifier.bleedStart(): Modifier = if (!glassOn) this else this.layout { m, c ->
    val extra = GlassInset.roundToPx()
    if (!c.hasBoundedWidth) return@layout m.measure(c).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
    val w = c.maxWidth + extra
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place(-extra, 0) }
}

/** True when the Glass style is on (Settings, Appearance, Style). */
val glassOn: Boolean get() = FocusStyle.glass

/** Loads [GlassScene.url] as a tiny, softened picture whenever it changes. */
@Composable
fun GlassSceneLoader() {
    if (!glassOn) return
    val ctx = LocalContext.current
    val url = GlassScene.url
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        val req = ImageRequest.Builder(ctx).data(url).size(64, 36).allowHardware(false).build()
        val bmp = (runCatching { ctx.imageLoader.execute(req).drawable }.getOrNull() as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val soft = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { soften(bmp) }
        GlassScene.previous = GlassScene.image
        GlassScene.image = soft.asImageBitmap()
    }
}

/** A few passes of box blur on the tiny picture, so it stretches to the screen silky smooth. */
private fun soften(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val b = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(3) {
        for (y in 0 until h) for (x in 0 until w) tmp[y * w + x] = avg(px, w, h, x, y, 2, true)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = avg(tmp, w, h, x, y, 2, false)
    }
    b.setPixels(px, 0, w, 0, 0, w, h)
    return b
}

private fun avg(p: IntArray, w: Int, h: Int, x: Int, y: Int, r: Int, horizontal: Boolean): Int {
    var a = 0; var rr = 0; var g = 0; var bb = 0; var n = 0
    for (d in -r..r) {
        val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
        val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
        val c = p[yy * w + xx]
        a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bb += c and 0xFF; n++
    }
    return ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bb / n)
}

/**
 * The page behind everything in Glass: the scene's colours, softly, under a gentle veil, with
 * the theme's glow. Pages with artwork of their own draw it on top.
 */
@Composable
fun GlassBackground() {
    val img = GlassScene.image
    val prev = GlassScene.previous
    val fade = remember(img) { Animatable(0f) }
    LaunchedEffect(img) { fade.animateTo(1f, tween(900)) }
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    Canvas(Modifier.fillMaxSize()) {
        drawRect(ZappColors.Bg)
        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
        if (prev != null && fade.value < 1f) drawImage(prev, dstSize = dst, filterQuality = FilterQuality.Low, alpha = 1f - fade.value)
        if (img != null) {
            drawImage(img, dstSize = dst, filterQuality = FilterQuality.Low, alpha = fade.value)
        } else {
            drawRect(Brush.linearGradient(listOf(b1.copy(alpha = 0.45f), ZappColors.Bg, b2.copy(alpha = 0.4f)), start = Offset.Zero, end = Offset(size.width, size.height)))
        }
        drawRect(Color.Black.copy(alpha = 0.42f))
    }
    AmbientGlow(0.6f, tint = if (FocusStyle.artGlow) ArtTint.color else null)
}

/**
 * A pane of glass: the scene behind, frosted, with a bright edge where the light catches it and
 * a soft shadow. [sample] false for panes in their own window (pop-ups), which use a deeper frost.
 */
fun Modifier.glass(
    shape: Shape = Shapes.Panel,
    sample: Boolean = true,
    dark: Float = 0.24f,
    frost: Float = 0.09f,
    elevation: androidx.compose.ui.unit.Dp = 14.dp,
    /** The tint for panes that don't show the scene ([sample] false). */
    fill: Color = Color(0xE6161722),
): Modifier = composed {
    val view = LocalView.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val scene = if (sample) GlassScene.image else null
    val b1 = ZappColors.Brand1
    val b2 = ZappColors.Brand2
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val edge = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.55f),
                0.3f to Color.White.copy(alpha = 0.14f),
                0.7f to Color.White.copy(alpha = 0.05f),
                1f to Color.White.copy(alpha = 0.22f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.45f to Color.Transparent)
            onDrawBehind {
                clipPath(path) {
                    if (scene != null) {
                        drawImage(
                            scene,
                            dstOffset = IntOffset(-pos.x.roundToInt(), -pos.y.roundToInt()),
                            dstSize = IntSize(view.width, view.height),
                            filterQuality = FilterQuality.Low,
                        )
                        drawRect(Color.Black.copy(alpha = dark))
                    } else if (sample) {
                        drawRect(Brush.linearGradient(listOf(b1.copy(alpha = 0.30f), b2.copy(alpha = 0.22f)), start = Offset(-pos.x, -pos.y), end = Offset(view.width - pos.x, view.height - pos.y)))
                        drawRect(Color.Black.copy(alpha = dark + 0.1f))
                    } else {
                        drawRect(fill)
                    }
                    drawRect(Color.White.copy(alpha = frost))
                    drawRect(sheen)
                }
                drawOutline(outline, edge, style = Stroke(1.dp.toPx()))
            }
        }
}

/** A pop-up's surface: glass in the Glass style, the panel colour otherwise. */
fun Modifier.panelSurface(): Modifier = composed {
    if (glassOn) this.glass(Shapes.Panel, sample = false, elevation = 0.dp)
    else this.then(Modifier.drawWithCache { val c = ZappColors.Panel; onDrawBehind { drawRect(c) } })
}

/** The spring the Glass style moves with: quick, with a soft settle. */
fun <T> glassSpring() = spring<T>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

/**
 * Glass in the player: a smoked pane floating above the bottom of the picture (the video itself
 * can't be frosted cheaply, so it's a deep translucent tint with the same bright edge).
 */
@Composable
fun GlassPlayerPane(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .drawWithCache {
                val fade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))
                onDrawBehind { drawRect(fade) }
            }
            .padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = 24.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .glass(androidx.compose.foundation.shape.RoundedCornerShape(26.dp), sample = false, elevation = 24.dp, frost = 0.06f, fill = Color(0xA6101016))
                .padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = 18.dp),
        ) { content() }
    }
}
