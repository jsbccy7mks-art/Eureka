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
    val html = remember(youtubeId, sound) {
        val muteFlag = if (sound) 0 else 1
        val onReady = if (sound) "e.target.unMute();e.target.setVolume(70);" else "e.target.mute();"
        val id = youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;background:transparent;overflow:hidden;height:100%}
        #p{position:absolute;top:-18%;left:-18%;width:136%;height:136%;pointer-events:none}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        function onYouTubeIframeAPIReady(){
          new YT.Player('p',{videoId:'$id',width:'100%',height:'100%',
            playerVars:{autoplay:1,mute:$muteFlag,controls:0,loop:1,playlist:'$id',modestbranding:1,playsinline:1,rel:0,iv_load_policy:3,disablekb:1,fs:0,start:4},
            events:{
              onReady:function(e){${onReady}e.target.playVideo();},
              onStateChange:function(e){ if(e.data==1){Zapp.playing();} if(e.data==0){Zapp.stopped();} },
              onError:function(){Zapp.stopped();}
            }});
        }
        </script></body></html>
        """.trimIndent()
    }
    Box(modifier.graphicsLayer { this.alpha = alpha }) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.domStorageEnabled = true
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

/** Shows [TrailerBackground] once the same title has been on screen for a moment. */
@Composable
fun DelayedTrailer(youtubeId: String?, key: Any?, modifier: Modifier = Modifier) {
    var show by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, youtubeId, FocusStyle.trailers, FocusStyle.trailerDelay) {
        show = false
        if (youtubeId == null || !FocusStyle.trailers) return@LaunchedEffect
        delay(FocusStyle.trailerDelay * 1_000L)
        show = true
    }
    if (show && youtubeId != null) TrailerBackground(youtubeId, modifier, sound = FocusStyle.trailerSound)
}
