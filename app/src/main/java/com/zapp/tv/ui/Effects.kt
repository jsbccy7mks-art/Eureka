package com.zapp.tv.ui

import android.annotation.SuppressLint
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------- colour from artwork

/** The colour of the title on screen, shared so the whole app's glow (and the sidebar) can follow it. */
object ArtTint {
    var color by mutableStateOf<Color?>(null)
}

private val artColors = ConcurrentHashMap<String, Int>()

/**
 * The most vivid colour in a picture (from a tiny copy of it), for tinting the page's glow so each
 * title has its own mood. Null until worked out, or when switched off in Settings.
 */
@Composable
fun rememberArtColor(url: String?): Color? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(url, FocusStyle.artGlow) {
        if (url == null || !FocusStyle.artGlow) {
            color = null
            return@LaunchedEffect
        }
        artColors[url]?.let {
            color = Color(it)
            return@LaunchedEffect
        }
        val argb = withContext(Dispatchers.Default) {
            try {
                val req = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
                val result = context.imageLoader.execute(req) as? SuccessResult ?: return@withContext null
                val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch)?.rgb
            } catch (e: Exception) {
                null
            }
        }
        if (argb != null) {
            artColors[url] = argb
            color = Color(argb)
        }
    }
    return color
}

// ---------------------------------------------------------------------------- slow drift

/** A slow, gentle zoom and pan, so still artwork feels alive (off with "Moving artwork"). */
fun Modifier.drift(): Modifier = composed {
    if (!FocusStyle.drift) return@composed this
    // Back and forth over 26 s; it holds still while you're moving around (smoother scrolling).
    var p by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var dir = 1f
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L && !Motion.moving) {
                    val step = (t - last) / 26_000_000_000f
                    var next = p + dir * step
                    if (next >= 1f) { next = 1f; dir = -1f }
                    if (next <= 0f) { next = 0f; dir = 1f }
                    p = next
                }
                last = t
            }
        }
    }
    graphicsLayer {
        val s = 1.04f + 0.07f * p
        scaleX = s
        scaleY = s
        translationX = size.width * (0.015f - 0.03f * p)
        translationY = size.height * (-0.01f + 0.015f * p)
    }
}

// ---------------------------------------------------------------------------- shimmer

/** Soft moving highlight for things that are still loading. */
@Composable
fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    val base = ZappColors.Glass
    return Brush.linearGradient(
        listOf(base, Color(0x26FFFFFF), base),
        start = Offset(x * 600f - 300f, 0f),
        end = Offset(x * 600f, 300f),
    )
}

// ---------------------------------------------------------------------------- frosted dialogs

/** Call inside a Dialog: blurs the page behind it (Android 12 and newer; just dims on older TVs). */
@Composable
fun DialogBlurBehind() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = 48 }
                w.setDimAmount(0.45f)
            }
        }
        onDispose {}
    }
}

// ---------------------------------------------------------------------------- clock

/** Small clock (and avatar when there are profiles) for the top right of Home, Movies and Series. */
/** Set by the app so headers can open Search and Settings. */
object NavShortcuts {
    var search: () -> Unit = {}
    var settings: () -> Unit = {}
    /** Down from the buttons: back to the page (on Home, its first row: Continue watching). */
    var down: () -> Unit = {}
    /** Opens Rate films (from Settings). */
    var rate: () -> Unit = {}
    /** The Search button, so Up from the top row of a page can land on it. */
    val requester = androidx.compose.ui.focus.FocusRequester()
}

/** Small Search and Settings buttons beside the clock (Up from the top of a page reaches them). */
@Composable
fun HeaderShortcuts() {
    Row(
        Modifier.onPreviewKeyEvent { e ->
            if (e.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) NavShortcuts.down()
                true
            } else {
                false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Your month / Your year, when one is ready (first week of the month; mid-December on).
        WrappedPeriod.ready()?.let { p ->
            FocusSurface(
                onClick = { WrappedState.open = p },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                focusScale = 1.06f,
                restColor = Color.White.copy(alpha = 0.1f),
            ) { focused ->
                Text(
                    "✦  Your ${p.title}",
                    fontSize = TextSize.Small,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focused) ZappColors.OnFocus else ZappColors.Text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        listOf(Glyph.Search to { NavShortcuts.search() }, Glyph.Settings to { NavShortcuts.settings() }).forEachIndexed { i, (g, go) ->
            // Just the icon with a soft drop shadow; on focus it brightens and grows a little.
            FocusSurface(
                onClick = go,
                modifier = Modifier.size(34.dp),
                focusRequester = if (i == 0) NavShortcuts.requester else null,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                focusScale = 1.18f,
                restColor = Color.Transparent,
                focusColor = Color.Transparent,
            ) { focused ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlyphIcon(
                        g,
                        Color.Black.copy(alpha = if (focused) 0.75f else 0.6f),
                        18.dp,
                        Modifier
                            .graphicsLayer {
                                translationX = 0.8.dp.toPx()
                                translationY = 1.5.dp.toPx()
                            }
                            .blur(3.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                    )
                    GlyphIcon(g, if (focused) Color.White else ZappColors.Text.copy(alpha = 0.85f), 18.dp)
                }
            }
        }
    }
}

@Composable
fun ClockAndAvatar(mvm: MediaViewModel) {
    val now = rememberNow(15_000)
    val fmt = rememberTimeFormat()
    val profiles = mvm.profiles.value
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderShortcuts()
        Spacer(Modifier.width(14.dp))
        Text(fmt.format(now), fontSize = TextSize.Heading, fontWeight = FontWeight.SemiBold, color = ZappColors.Text)
        val p = mvm.currentProfile
        if (p != null && profiles.size > 1) {
            Spacer(Modifier.width(12.dp))
            Avatar(p, 26.dp)
        }
    }
}

// ---------------------------------------------------------------------------- trailers

/**
 * A muted YouTube trailer filling its box, cropped like artwork. It stays invisible until it's
 * actually playing (so errors or loading never show) and fades in over the picture.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebTrailer(youtubeId: String, modifier: Modifier = Modifier, sound: Boolean = false, onPlaying: () -> Unit = {}) {
    var playing by remember(youtubeId) { mutableStateOf(false) }
    val playingNow by androidx.compose.runtime.rememberUpdatedState(onPlaying)
    LaunchedEffect(playing) { if (playing) playingNow() }
    val alpha by animateFloatAsState(if (playing) 1f else 0f, tween(900), label = "trailer")
    val best = FocusStyle.trailerBest
    val html = remember(youtubeId, sound, best) {
        // Always starts muted: sound (if wanted) comes on at the moment the picture appears,
        // so you never hear a trailer before you see it.
        val unmute = if (sound) "P.unMute();P.setVolume(70);" else ""
        val id = youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        // Best: the player is laid out at 4K so YouTube picks its sharpest stream; Auto: 720p-sized.
        val w = if (best) 3840 else 1280
        val h = if (best) 2160 else 720
        val vq = if (best) "hd2160" else "default"
        """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;overflow:hidden;width:100%;height:100%}
        /* Scaled down to cover the box like artwork, zoomed a little to crop away YouTube's title bar and logo. */
        #p{position:fixed;top:50%;left:50%;width:${w}px;height:${h}px;margin:-${h / 2}px 0 0 -${w / 2}px;pointer-events:none;border:0;transform-origin:50% 50%}
        /* Nothing can reach the player, so YouTube never shows its buttons. */
        #shield{position:fixed;left:0;top:0;right:0;bottom:0;z-index:5;background:transparent}</style></head>
        <body><div id="p"></div><div id="shield"></div>
        <script>
        var P=null;
        function fit(){var el=document.getElementById('p');if(!el)return;
          var k=Math.max(window.innerWidth/$w,window.innerHeight/$h)*1.32;el.style.transform='scale('+k+')';}
        window.addEventListener('resize',fit);
        </script>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        function onYouTubeIframeAPIReady(){
          P=new YT.Player('p',{videoId:'$id',width:'$w',height:'$h',
            playerVars:{autoplay:1,mute:1,controls:0,showinfo:0,cc_load_policy:0,loop:1,playlist:'$id',modestbranding:1,playsinline:1,rel:0,iv_load_policy:3,disablekb:1,fs:0,start:4,vq:'$vq'},
            events:{
              onReady:function(e){fit();e.target.mute();try{e.target.setPlaybackQuality('$vq');}catch(x){}e.target.playVideo();},
              onStateChange:function(e){
                // Show only while really playing, and only after YouTube's start-up overlay has gone.
                if(e.data==1){ if(!window.seen){window.seen=true;setTimeout(function(){${unmute}Zapp.playing();},2600);} else {Zapp.playing();} }
                if(e.data==0||e.data==2||e.data==3){Zapp.stopped();}
              },
              onError:function(){Zapp.stopped();}
            }});
          fit();
        }
        </script></body></html>
        """.trimIndent()
    }
    // The fade is applied to the web view itself and dropped once fully shown, so the video isn't
    // drawn through an extra layer (which can put the sound out of step with the picture).
    Box(modifier) {
        AndroidView(
            update = { it.alpha = alpha },
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.domStorageEnabled = true
                    // The desktop player: with controls off it shows no buttons at all (the phone
                    // player still pops up play/pause and skip arrows), and it offers 4K streams.
                    settings.userAgentString = DESKTOP_UA
                    settings.useWideViewPort = false
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun playing() {
                                post { playing = true }
                            }

                            @JavascriptInterface
                            fun stopped() {
                                post { playing = false }
                            }
                        },
                        "Zapp",
                    )
                    loadDataWithBaseURL("https://zapp.tv/", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** What's covering the page, so trailers underneath stop (and never play over a film). */
object TrailerGate {
    var appVisible by mutableStateOf(true)
    /** The player or the sources screen is open. */
    var playing by mutableStateOf(false)
    /** A details page is open over Home, Movies or Series. */
    var detailOpen by mutableStateOf(false)
}

/** Shows [TrailerBackground] once the same title has been on screen for a moment. [onDetail]: the details page's own trailer. */
@Composable
fun DelayedTrailer(
    youtubeId: String?,
    key: Any?,
    modifier: Modifier = Modifier,
    onDetail: Boolean = false,
    enabled: Boolean = true,
    sound: Boolean = FocusStyle.trailerSound,
    onShowing: (Boolean) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var show by remember(key) { mutableStateOf(false) }
    // Never behind something full screen (What's new, Rate films).
    val allowed = enabled && TrailerGate.appVisible && !TrailerGate.playing && !OverlayFocus.open && (onDetail || !TrailerGate.detailOpen)
    // Find the trailer's streams straight away, so it starts the moment the wait is over.
    LaunchedEffect(youtubeId) {
        if (youtubeId != null && FocusStyle.trailers) (context.applicationContext as com.zapp.tv.ZappApp).youtube.prefetch(youtubeId, trailerHeightCap())
    }
    LaunchedEffect(key, youtubeId, FocusStyle.trailers, FocusStyle.trailerDelay, allowed) {
        show = false
        if (youtubeId == null || !FocusStyle.trailers || !allowed) return@LaunchedEffect
        delay(FocusStyle.trailerDelay * 1_000L)
        show = true
    }
    // "Showing" means the trailer is actually on screen (its first frame), not just asked for.
    var playing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(show) { if (!show) playing = false }
    LaunchedEffect(show, playing) { onShowing(show && playing) }
    if (show && youtubeId != null) TrailerBackground(youtubeId, modifier, sound = sound, onPlaying = { playing = true })
}

/**
 * A soft wash of colour from a picture, filling the screen: the artwork shrunk right down and
 * stretched back up (so it's blurred on every TV), plus a real blur where Android can, then dimmed.
 */
@Composable
fun BlurredBackdrop(url: String?, modifier: Modifier = Modifier, dim: Float = 0.45f) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.fillMaxSize()) {
        androidx.compose.animation.Crossfade(targetState = url, animationSpec = tween(800), label = "blurBg") { u ->
            if (u != null) {
                coil.compose.AsyncImage(
                    model = remember(u) {
                        coil.request.ImageRequest.Builder(context).data(u).size(48, 27).crossfade(false).build()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // A touch larger so the soft edges stay off screen.
                            scaleX = 1.15f
                            scaleY = 1.15f
                        }
                        .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(40.dp) else Modifier),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}
