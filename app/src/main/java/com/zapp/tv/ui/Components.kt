package com.zapp.tv.ui

import androidx.compose.ui.draw.drawWithContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat

// ---------------------------------------------------------------------------- helpers

fun FocusRequester.tryRequest() {
    try {
        requestFocus()
    } catch (e: Exception) {
        // Target isn't on screen (yet); nothing to focus.
    }
}

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** Current time, refreshed every [periodMs]. */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    val state = produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return state.value
}

/** Time format that follows the TV's 12/24-hour setting. */
@Composable
fun rememberTimeFormat(): DateFormat {
    val context = LocalContext.current
    return remember { android.text.format.DateFormat.getTimeFormat(context) }
}

fun progressOf(start: Long?, stop: Long?, now: Long): Float {
    if (start == null || stop == null || stop <= start) return 0f
    return ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------- focus memory

/**
 * Remembers which item of a list had focus, so moving back into the list lands on it again.
 * Used instead of Compose's focusRestorer, which can crash inside scrolling lists in this
 * Compose version ("Release should only be called once").
 */
class FocusMemory {
    private val requesters = HashMap<Any, FocusRequester>()
    var last: Any? = null

    fun attach(key: Any, requester: FocusRequester) {
        requesters[key] = requester
    }

    fun detach(key: Any, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
    }

    /** The remembered item if it is on screen; otherwise Compose's normal choice. */
    fun target(): FocusRequester = last?.let { requesters[it] } ?: FocusRequester.Default
}

@Composable
fun rememberFocusMemory(): FocusMemory = remember { FocusMemory() }

/** Put on a list: entering it goes to the remembered item. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.restoreFocus(memory: FocusMemory): Modifier = focusProperties { enter = { memory.target() } }

fun Modifier.focusRequesterSafe(requester: FocusRequester): Modifier = this.focusRequester(requester)

/** Put on an item inside a list using [restoreFocus]. */
@Composable
fun Modifier.rememberFocus(memory: FocusMemory, key: Any): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(key, requester) {
        memory.attach(key, requester)
        onDispose { memory.detach(key, requester) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.hasFocus) memory.last = key }
}

// ---------------------------------------------------------------------------- focus surface

private val SelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private class PressState {
    var down = false
    var longFired = false
}

/**
 * Base for everything the remote can land on. Handles OK (on key-up, so a press that
 * opens a new screen never "clicks" the item that appears under it) and hold-OK.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    shape: Shape = RoundedCornerShape(10.dp),
    focusScale: Float = 1.02f,
    restColor: Color = Color.Transparent,
    selectedColor: Color = ZappColors.Glass,
    focusColor: Color = ZappColors.Focus,
    /** Cards: draw the theme's focus ring (and glow, if switched on) instead of filling with colour. */
    ring: Boolean = false,
    ringCorner: Dp = 10.dp,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val pulse = LocalFocusPulse.current
    val ownRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val press = remember { PressState() }
    val clickState by rememberUpdatedState(onClick)
    val longState by rememberUpdatedState(onLongClick)
    val focusState by rememberUpdatedState(onFocusChange)
    var pressed by remember { mutableStateOf(false) }
    // A subtle dip while OK is held, then back up: feels like a real button.
    val scale by animateFloatAsState(
        when {
            focused && pressed -> focusScale * 0.96f
            focused -> focusScale
            else -> 1f
        },
        tween(if (pressed) 80 else 140),
        label = "scale",
    )
    val ringAlpha by animateFloatAsState(if (ring && focused) 1f else 0f, tween(180), label = "ring")
    // Cards lift a little, with a soft shadow beneath, rather than only growing.
    val lift by animateFloatAsState(if (ring && focused) 1f else 0f, tween(220), label = "lift")
    // Card style (Appearance): square corners, or a thin border at rest.
    val isCard = ring && ringCorner <= 14.dp // cards, not round swatches
    val corner = if (isCard && FocusStyle.cardStyle == 1) 3.dp else ringCorner
    val cardShape = if (isCard) RoundedCornerShape(corner) else shape
    val fill by animateColorAsState(
        when {
            focused -> focusColor
            selected -> selectedColor
            else -> restColor
        },
        tween(110),
        label = "fill",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Always one focusRequester node (ours if none given): adding or removing it would rebuild
            // the focus node below and drop focus.
            .focusRequester(focusRequester ?: ownRequester)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    focusState(it.isFocused)
                }
                if (!it.isFocused) {
                    pressed = false
                    press.down = false
                    press.longFired = false
                }
            }
            .onKeyEvent { e ->
                if (e.key !in SelectKeys) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            press.down = true
                            press.longFired = false
                            pressed = true
                        } else if (press.down && !press.longFired && longState != null) {
                            press.longFired = true
                            longState?.invoke()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        pressed = false
                        val fire = press.down && !press.longFired
                        press.down = false
                        press.longFired = false
                        if (fire) clickState()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            // The lift is drawn inside the focus area (after it in the chain), so the card's place for
            // the remote's Up/Down/Left/Right stays where it is laid out.
            .graphicsLayer {
                scaleX = 1f + 0.035f * lift
                scaleY = 1f + 0.035f * lift
                translationY = -5.dp.toPx() * lift
            }
            .drawWithContent {
                // The glow sits behind the card; the ring on top of it.
                if (ring) drawLiftShadow(corner.toPx(), lift)
                if (ring && ringAlpha > 0.01f) drawFocusGlow(corner.toPx(), pulse.value, ringAlpha)
                drawContent()
                if (ring && ringAlpha > 0.01f) drawFocusRing(corner.toPx(), pulse.value, ringAlpha)
                if (isCard && FocusStyle.cardStyle == 2 && ringAlpha < 0.99f) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.16f * (1f - ringAlpha)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { clickState() },
                    onLongPress = { longState?.invoke() },
                )
            }
            .clip(cardShape)
            .background(fill),
    ) {
        content(focused)
    }
}

/** How focus looks on cards, from Settings, Appearance. */
object FocusStyle {
    /** Ring thickness in dp. */
    var ringWidth by mutableStateOf(2.5f)
    var glow by mutableStateOf(true)
    /** Tint the page glow with the colour of the title you're on. */
    var artGlow by mutableStateOf(true)
    /** Play muted trailers behind the header on Home and details. */
    var trailers by mutableStateOf(true)
    /** Slow zoom and pan on artwork. */
    var drift by mutableStateOf(true)
    /** Seconds on a title before its trailer starts. */
    var trailerDelay by mutableStateOf(4)
    /** Trailers with sound. */
    var trailerSound by mutableStateOf(false)
    /** Trailers in up to 4K. */
    var trailerBest by mutableStateOf(true)
    /** Pure black pages (OLED TVs). */
    var trueBlack by mutableStateOf(false)
    /** Title lettering: 0 cinematic (Cinzel), 1 clean modern, 2 bold condensed. */
    var titleFont by mutableStateOf(0)
    /** Cards: 0 rounded, 1 square, 2 rounded with a thin border. */
    var cardStyle by mutableStateOf(0)
    /** Names under posters in rows. */
    var cardTitles by mutableStateOf(false)
    /** The page's colour glow: 0 subtle, 1 normal, 2 vivid. */
    var glowLevel by mutableStateOf(1)
}

/** How much the page glow shows, from the Glow setting. */
val glowFactor: Float get() = when (FocusStyle.glowLevel) { 0 -> 0.55f; 2 -> 1.7f; else -> 1f }

private val liftPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private var liftBlur: android.graphics.BlurMaskFilter? = null

/** A soft shadow under a lifted card. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiftShadow(corner: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val drop = 10.dp.toPx()
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        if (liftBlur == null) liftBlur = android.graphics.BlurMaskFilter(16.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        liftPaint.maskFilter = liftBlur
        liftPaint.color = Color.Black.copy(alpha = 0.55f * alpha).toArgb()
        drawIntoCanvas { c ->
            c.nativeCanvas.drawRoundRect(size.width * 0.06f, drop, size.width * 0.94f, size.height + drop, corner, corner, liftPaint)
        }
    } else {
        drawRoundRect(Color.Black.copy(alpha = 0.3f * alpha), topLeft = Offset(size.width * 0.05f, drop), size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner))
    }
}

/** One shared, slow pulse (0..1) for the focus glow, so cards don't each run an animation. */
val LocalFocusPulse = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Float>> {
    androidx.compose.runtime.mutableFloatStateOf(0.5f)
}

/** The theme-coloured ring around a focused card, with an optional soft pulsing glow outside it. */
private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private var glowBlur: android.graphics.BlurMaskFilter? = null
private var glowBlurRadius = 0f

/** A soft, smooth halo in the theme colour behind the focused card, gently pulsing. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusGlow(corner: Float, pulse: Float, alpha: Float = 1f) {
    if (!FocusStyle.glow) return
    val a = alpha * (0.42f + 0.30f * pulse)
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        // One blurred shape: no steps or bands.
        val r = 13.dp.toPx()
        if (glowBlur == null || glowBlurRadius != r) {
            glowBlur = android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL)
            glowBlurRadius = r
        }
        val g = 1.dp.toPx()
        glowPaint.maskFilter = glowBlur
        glowPaint.color = androidx.compose.ui.graphics.lerp(ZappColors.Brand1, ZappColors.Brand2, 0.35f).copy(alpha = a).toArgb()
        drawIntoCanvas { c ->
            c.nativeCanvas.drawRoundRect(-g, -g, size.width + g, size.height + g, corner + g, corner + g, glowPaint)
        }
    } else {
        // Older Android: many fine steps, which read as smooth.
        val spread = 12.dp.toPx()
        val steps = 16
        for (i in 1..steps) {
            val grow = spread * i / steps
            drawRoundRect(
                color = ZappColors.Brand1.copy(alpha = a * 0.22f * (1f - i / (steps + 1f))),
                topLeft = Offset(-grow, -grow),
                size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner + grow),
                style = androidx.compose.ui.graphics.drawscope.Stroke(spread / steps + 0.6f),
            )
        }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusRing(corner: Float, pulse: Float, alpha: Float = 1f) {
    val stroke = FocusStyle.ringWidth.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(listOf(ZappColors.Focus, ZappColors.Brand2.copy(alpha = 0.9f).compositeOverWhite())),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius((corner - stroke / 2).coerceAtLeast(0f)),
        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        alpha = alpha,
    )
}

private fun Color.compositeOverWhite(): Color = androidx.compose.ui.graphics.lerp(this, Color.White, 0.35f)

enum class ButtonStyle { Primary, Secondary, Quiet, Danger }

@Composable
fun ZButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Secondary,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    // Minimal: primary is a soft filled pill, others a hairline outline; focus turns any of them white.
    var isFocused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        // Always the same modifiers (only the colour changes), so focus is never lost by rebuilding them.
        modifier = modifier.border(
            1.dp,
            if (!isFocused && style != ButtonStyle.Primary && style != ButtonStyle.Quiet) Color(0x2EFFFFFF) else Color.Transparent,
            RoundedCornerShape(50),
        ),
        focusRequester = focusRequester,
        onFocusChange = {
            isFocused = it
            onFocusChange(it)
        },
        shape = RoundedCornerShape(50),
        focusScale = 1.03f,
        restColor = when (style) {
            ButtonStyle.Primary -> Color(0x2EFFFFFF)
            else -> Color.Transparent
        },
    ) { focused ->
        Text(
            text = text,
            color = when {
                focused && style == ButtonStyle.Danger -> Color(0xFFD6243F)
                focused -> ZappColors.OnFocus
                style == ButtonStyle.Quiet -> ZappColors.Dim
                else -> ZappColors.Text
            },
            fontSize = 12.5.sp,
            fontWeight = if (style == ButtonStyle.Primary || focused) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.1.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------- brand + glyphs

@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(ZappColors.Brand1, ZappColors.Brand2),
                start = Offset.Zero,
                end = Offset(s, s),
            ),
            cornerRadius = CornerRadius(s * 0.28f),
        )
        val bolt = Path().apply {
            moveTo(0.58f * s, 0.12f * s)
            lineTo(0.28f * s, 0.56f * s)
            lineTo(0.47f * s, 0.56f * s)
            lineTo(0.42f * s, 0.88f * s)
            lineTo(0.73f * s, 0.43f * s)
            lineTo(0.54f * s, 0.43f * s)
            lineTo(0.62f * s, 0.12f * s)
            close()
        }
        drawPath(bolt, Color.White)
    }
}

@Composable
fun ZappLogo(height: Dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LogoMark(height)
        Spacer(Modifier.width(height * 0.36f))
        Text(
            text = "zapp",
            fontSize = (height.value * 0.82f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.02f * height.value).sp,
            color = ZappColors.Text,
        )
    }
}

@Composable
fun StarGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.45f
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + (radius * Math.cos(angle)).toFloat()
            val y = cy + (radius * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
fun ChevronGlyph(color: Color, size: Dp, down: Boolean = true) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            if (down) {
                moveTo(w * 0.2f, h * 0.35f)
                lineTo(w * 0.5f, h * 0.65f)
                lineTo(w * 0.8f, h * 0.35f)
            } else {
                moveTo(w * 0.35f, h * 0.2f)
                lineTo(w * 0.65f, h * 0.5f)
                lineTo(w * 0.35f, h * 0.8f)
            }
        }
        drawPath(
            path,
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.12f),
        )
    }
}

@Composable
fun LiveDot(size: Dp = 6.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(ZappColors.Live),
    )
}

@Composable
fun LivePill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ZappColors.Live)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Live", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Rounded logo tile; shows the first letter until (or unless) the logo loads. */
@Composable
fun LogoTile(
    url: String?,
    fallback: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onLight: Boolean = false,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(if (onLight) Color(0xFFE2E7F2) else ZappColors.Glass),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                text = fallback.trim().take(1).uppercase(),
                color = if (onLight) ZappColors.OnFocusDim else ZappColors.Faint,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.14f),
            )
        }
    }
}

/** Progress colours: the theme's gradient, or near-white for Monochrome so it stands out on video. */
fun barColors(): List<Color> =
    if (ZappColors.palette.id == "mono") listOf(Color(0xFFC8C8C8), Color.White) else listOf(ZappColors.Brand1, ZappColors.Brand2)

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, track: Color = ZappColors.Line, fill: Color = Color.Unspecified) {
    // No colour given: the theme's gradient, like the focus ring.
    val brush = if (fill == Color.Unspecified) Brush.horizontalGradient(barColors()) else SolidColor(fill)
    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(brush),
        )
    }
}

/** Remote button hint, e.g. [OK] Watch. */
@Composable
fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .border(1.dp, ZappColors.Line, RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(key, color = ZappColors.Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = ZappColors.Faint, fontSize = 11.sp)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ZappColors.Glass)
            .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(18.dp)),
    ) {
        content()
    }
}

/** The page colour with the theme's glow: its first colour top left, melting into the second bottom right. */
@Composable
fun AmbientBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(ZappColors.Bg)
    }
    AmbientGlow(tint = if (FocusStyle.artGlow) ArtTint.color else null)
}

/** Just the glow, to lay over artwork so every screen shares the same light. */
@Composable
fun AmbientGlow(strengthIn: Float = 1f, modifier: Modifier = Modifier, tint: Color? = null) {
    val strength = strengthIn * glowFactor
    val c1 by animateColorAsState(tint ?: ZappColors.Brand1, tween(900), label = "glow1")
    val c2 by animateColorAsState(if (tint != null) androidx.compose.ui.graphics.lerp(tint, ZappColors.Brand2, 0.45f) else ZappColors.Brand2, tween(900), label = "glow2")
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(c1.copy(alpha = (if (tint != null) 0.22f else 0.16f) * strength), Color.Transparent),
                center = Offset(w * 0.05f, h * 0.02f),
                radius = w * 0.6f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(c2.copy(alpha = 0.12f * strength), Color.Transparent),
                center = Offset(w * 0.95f, h * 1.02f),
                radius = w * 0.6f,
            ),
        )
    }
}

// ---------------------------------------------------------------------------- QR code

@Composable
fun QrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        try {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                ),
            )
        } catch (e: Exception) {
            null
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp),
    ) {
        Canvas(Modifier.size(size)) {
            val m = matrix ?: return@Canvas
            val n = m.width
            val cell = this.size.minDimension / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (m[x, y]) {
                        drawRect(
                            color = Color(0xFF0A0E1A),
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell + 0.6f, cell + 0.6f),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- text input

/**
 * Text field that works with a remote: moving onto it does NOT open the keyboard.
 * Press OK to type; Up/Down, Next or Back leave the field again.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    last: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var restoreTick by remember { mutableIntStateOf(0) }
    var moveAfter by remember { mutableStateOf<FocusDirection?>(null) }
    val surfaceFocus = focusRequester ?: remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun finish(direction: FocusDirection?) {
        editing = false
        moveAfter = direction
        restoreTick++
    }

    LaunchedEffect(editing) {
        if (editing) {
            editFocus.tryRequest()
            keyboard?.show()
        }
    }
    LaunchedEffect(restoreTick) {
        if (restoreTick == 0) return@LaunchedEffect
        keyboard?.hide()
        surfaceFocus.tryRequest()
        moveAfter?.let { focusManager.moveFocus(it) }
        moveAfter = null
    }
    BackHandler(enabled = editing) { finish(null) }

    Column(modifier.fillMaxWidth()) {
        Text(label, color = ZappColors.Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        if (!editing) {
            FocusSurface(
                onClick = { editing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                focusRequester = surfaceFocus,
                shape = RoundedCornerShape(11.dp),
                focusScale = 1.01f,
                restColor = ZappColors.Glass,
            ) { focused ->
                val shown = when {
                    value.isEmpty() -> placeholder
                    password -> "•".repeat(value.length.coerceAtMost(16))
                    else -> value
                }
                Text(
                    text = shown,
                    color = when {
                        focused && value.isEmpty() -> ZappColors.OnFocusDim
                        focused -> ZappColors.OnFocus
                        value.isEmpty() -> ZappColors.Faint
                        else -> ZappColors.Text
                    },
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 14.dp),
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(ZappColors.PanelHigh)
                    .border(1.5.dp, ZappColors.Brand2, RoundedCornerShape(11.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = ZappColors.Text),
                    cursorBrush = SolidColor(ZappColors.Brand2),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = if (last) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { finish(FocusDirection.Down) },
                        onDone = { finish(null) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(editFocus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    finish(FocusDirection.Down)
                                    true
                                }
                                Key.DirectionUp -> {
                                    finish(FocusDirection.Up)
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
        }
    }
}

/** Two-or-more option switch, e.g. TS / HLS. */
@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(ZappColors.Glass)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (key, label) ->
            FocusSurface(
                onClick = { onChange(key) },
                selected = key == value,
                shape = RoundedCornerShape(50),
                selectedColor = ZappColors.GlassHigh,
            ) { focused ->
                Text(
                    text = label,
                    color = when {
                        focused -> ZappColors.OnFocus
                        key == value -> ZappColors.Text
                        else -> ZappColors.Dim
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x24FF4F6A))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = ZappColors.Error, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------- overlays

/** Blocks the remote while something is downloading. */
@Composable
fun BusyOverlay(message: String) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequest() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZappColors.Scrim)
            .onKeyEvent { it.key != Key.Back }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(ZappColors.Panel)
                .padding(horizontal = 26.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = ZappColors.Brand2, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text(message, color = ZappColors.Text, fontSize = 15.sp)
        }
    }
}

data class ToastMessage(val text: String, val id: Long)

@Composable
fun ToastHost(message: ToastMessage?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    var visibleId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        shown = m
        visibleId = m.id
        delay(2600)
        if (visibleId == m.id) visibleId = -1L
    }
    AnimatedVisibility(
        visible = shown != null && visibleId == shown?.id,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ZappColors.Focus)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(shown?.text.orEmpty(), color = ZappColors.OnFocus, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
