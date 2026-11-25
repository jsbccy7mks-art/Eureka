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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    val t = rememberInfiniteTransition(label = "drift")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
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
@Composable
fun ClockAndAvatar(mvm: MediaViewModel) {
    val now = rememberNow(15_000)
    val fmt = rememberTimeFormat()
    val profiles = mvm.profiles.value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fmt.format(now), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ZappColors.Text)
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
fun TrailerBackground(youtubeId: String, modifier: Modifier = Modifier, sound: Boolean = false) {
    var playing by remember(youtubeId) { mutableStateOf(false) }
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
fun DelayedTrailer(youtubeId: String?, key: Any?, modifier: Modifier = Modifier, onDetail: Boolean = false) {
    var show by remember(key) { mutableStateOf(false) }
    val allowed = TrailerGate.appVisible && !TrailerGate.playing && (onDetail || !TrailerGate.detailOpen)
    LaunchedEffect(key, youtubeId, FocusStyle.trailers, FocusStyle.trailerDelay, allowed) {
        show = false
        if (youtubeId == null || !FocusStyle.trailers || !allowed) return@LaunchedEffect
        delay(FocusStyle.trailerDelay * 1_000L)
        show = true
    }
    if (show && youtubeId != null) TrailerBackground(youtubeId, modifier, sound = FocusStyle.trailerSound)
}
