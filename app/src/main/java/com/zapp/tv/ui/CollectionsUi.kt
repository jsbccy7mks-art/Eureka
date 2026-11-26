package com.zapp.tv.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.zapp.tv.addons.MetaPreview
import kotlin.math.abs

/** A franchise as a wide card: its backdrop, its name, and how many of its films are yours. */
@Composable
fun CollectionCard(
    c: MediaViewModel.CollectionSummary,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(width).aspectRatio(WideAspect),
        onFocusChange = { if (it) onFocus() },
        shape = RoundedCornerShape(12.dp),
        focusScale = 1f,
        restColor = ZappColors.Glass,
        focusColor = ZappColors.Glass,
        ring = true,
        ringCorner = 12.dp,
    ) {
        Artwork(c.backdrop ?: c.poster, c.name)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text("COLLECTION", color = ZappColors.Brand2, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(2.dp))
            MovieTitle(c.name, 17.sp, maxLines = 1)
        }
    }
}

/**
 * A franchise as a showcase: the page takes on the colours of its artwork, its theme tune plays
 * softly (if ThemerrDB has it), and the films stand in release order as a cover-flow of large
 * posters, the one you're on lifted forward with a reflection beneath, the others turned away.
 */
@Composable
fun CollectionScreen(
    mvm: MediaViewModel,
    collectionId: Long,
    focusTick: Int,
    musicAllowed: Boolean,
    onOpen: (MetaPreview) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val collection by produceState<com.zapp.tv.data.TmdbCollection?>(null, collectionId) { value = mvm.collection(collectionId) }
    val imdb by produceState(emptyMap<Long, String>(), collection) { collection?.let { value = mvm.collectionImdbIds(it) } }
    val theme by produceState<String?>(null, collectionId) { value = mvm.collectionTheme(collectionId) }
    val c = collection
    var index by remember(collectionId) { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }

    // The page takes the artwork's colour (glow, sidebar).
    val tint = rememberArtColor(c?.poster ?: c?.backdrop)
    LaunchedEffect(tint) { ArtTint.color = tint }
    DisposableEffect(Unit) { onDispose { ArtTint.color = null } }

    Box(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .background(ZappColors.Bg),
    ) {
        // A blurred wash of the collection's colours, then the focused film's backdrop drifting over it.
        val part = c?.parts?.getOrNull(index)
        BlurredBackdrop(c?.backdrop ?: c?.poster, dim = 0.5f)
        Crossfade(targetState = part?.item?.backdrop ?: c?.backdrop, animationSpec = tween(700), label = "colBg", modifier = Modifier.fillMaxSize()) { u ->
            if (u != null) {
                AsyncImage(model = hiRes(u), contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.42f, modifier = Modifier.fillMaxSize().drift())
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to ZappColors.Bg.copy(alpha = 0.55f),
                        0.45f to (tint ?: ZappColors.Bg).copy(alpha = 0.18f),
                        1f to ZappColors.Bg,
                    ),
                ),
        )
        AmbientGlow(0.7f, tint = tint)

        if (c == null) {
            ZappLoader(44.dp, Modifier.align(Alignment.Center))
            return@Box
        }
        if (musicAllowed) theme?.let { ThemeMusic(it, mvm.settings.themeVolume) }

        val watched = WatchedMarks.ids
        val seen = c.parts.count { p -> imdb[p.item.id]?.let { it in watched } == true }
        val nextIndex = c.parts.indexOfFirst { p -> imdb[p.item.id]?.let { it !in watched } ?: true }
        val years = c.parts.mapNotNull { it.item.year }.let { y -> if (y.isEmpty()) null else if (y.first() == y.last()) y.first() else "${y.first()}–${y.last()}" }

        Column(Modifier.fillMaxSize()) {
            // ---- the franchise
            Column(Modifier.padding(start = 64.dp, end = 64.dp, top = 40.dp)) {
                Text(
                    listOfNotNull("COLLECTION", "${c.parts.size} FILMS", years, if (imdb.isNotEmpty()) "$seen WATCHED" else null).joinToString("   ·   "),
                    color = ZappColors.Brand2.copy(alpha = 0.95f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.2.sp,
                )
                Spacer(Modifier.height(8.dp))
                MovieTitle(c.name, 44.sp, maxLines = 1)
                c.overview?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = ZappColors.Dim, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 640.dp))
                }
            }

            // ---- the showcase
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val posterW = 150.dp
                val centre = (maxWidth - posterW) / 2
                val listState = rememberLazyListState()
                LaunchedEffect(focusTick, c.id) {
                    kotlinx.coroutines.delay(150)
                    first.tryRequest()
                }
                PivotScrolling(centre) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = centre),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(c.parts, key = { _, p -> p.item.id }) { i, p ->
                            val d = (i - index).toFloat()
                            val rot by animateFloatAsState((-d * 28f).coerceIn(-55f, 55f), tween(380), label = "rot")
                            val scale by animateFloatAsState(if (d == 0f) 1.18f else (1f - 0.08f * abs(d)).coerceAtLeast(0.72f), tween(380), label = "scale")
                            val fade by animateFloatAsState((1f - 0.2f * abs(d)).coerceAtLeast(0.3f), tween(380), label = "fade")
                            val id = imdb[p.item.id]
                            val density = LocalDensity.current.density
                            Column(
                                Modifier
                                    .width(posterW)
                                    .graphicsLayer {
                                        rotationY = rot
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = fade
                                        cameraDistance = 14f * density
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                FocusSurface(
                                    onClick = {
                                        val imdbId = id ?: return@FocusSurface
                                        onOpen(MetaPreview(imdbId, "movie", p.item.name, p.item.poster, "poster", p.item.backdrop, null, p.overview, p.item.year, null, emptyList()))
                                    },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                                    focusRequester = if (i == (if (nextIndex >= 0) nextIndex else 0)) first else null,
                                    onFocusChange = { if (it) index = i },
                                    shape = RoundedCornerShape(10.dp),
                                    focusScale = 1f,
                                    restColor = ZappColors.Glass,
                                    focusColor = ZappColors.Glass,
                                    ring = true,
                                    ringCorner = 10.dp,
                                ) {
                                    Artwork(p.item.poster, p.item.name)
                                    if (id != null && id in watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(7.dp))
                                }
                                // A soft reflection underneath.
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)), blendMode = BlendMode.DstIn)
                                        },
                                ) {
                                    AsyncImage(
                                        model = p.item.poster,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.BottomCenter,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .graphicsLayer { scaleY = -1f }
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- the timeline and the film you're on
            Column(Modifier.padding(start = 64.dp, end = 64.dp, bottom = 34.dp)) {
                Timeline(c.parts.size, index, c.parts.map { p -> imdb[p.item.id]?.let { it in watched } == true }, nextIndex)
                Spacer(Modifier.height(14.dp))
                AnimatedContent(targetState = index, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(120)) }, label = "colInfo") { i ->
                    val p = c.parts.getOrNull(i)
                    if (p != null) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = ZappColors.Brand2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Text(p.item.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p.item.year?.let {
                                    Spacer(Modifier.width(10.dp))
                                    Text(it, color = ZappColors.Dim, fontSize = 13.sp)
                                }
                                if (i == nextIndex && imdb.isNotEmpty()) {
                                    Spacer(Modifier.width(10.dp))
                                    Tag("Next to watch", highlight = true)
                                }
                            }
                            p.overview?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, color = ZappColors.Dim, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A line of dots, one per film: watched ones filled, the one you're on large, the next one ringed. */
@Composable
private fun Timeline(count: Int, focused: Int, watched: List<Boolean>, next: Int) {
    if (count <= 0) return
    val a = ZappColors.Brand1
    val b = ZappColors.Brand2
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val y = size.height / 2
        val step = if (count > 1) size.width / (count - 1) else 0f
        drawLine(Brush.horizontalGradient(listOf(a.copy(alpha = 0.6f), b.copy(alpha = 0.6f))), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
        for (i in 0 until count) {
            val x = if (count > 1) i * step else size.width / 2
            val c = Offset(x, y)
            when {
                i == focused -> {
                    drawCircle(Color.White, 6.dp.toPx(), c)
                    drawCircle(b, 3.dp.toPx(), c)
                }
                watched.getOrNull(i) == true -> drawCircle(Brush.linearGradient(listOf(a, b)), 4.dp.toPx(), c)
                i == next -> drawCircle(Color.White, 4.5.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                else -> drawCircle(Color(0x66FFFFFF), 3.dp.toPx(), c)
            }
        }
    }
}

/**
 * A theme tune, audio only, through YouTube (unseen): fades in to [volume] percent and loops;
 * it stops when this leaves the screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ThemeMusic(youtubeId: String, volume: Int) {
    val id = youtubeId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    val vol = volume.coerceIn(5, 80)
    val html = remember(id, vol) {
        """
        <html><body style="margin:0;background:#000">
        <div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var P=null;
        function onYouTubeIframeAPIReady(){
          P=new YT.Player('p',{videoId:'$id',width:'320',height:'180',
            playerVars:{autoplay:1,mute:1,controls:0,loop:1,playlist:'$id',playsinline:1,rel:0,iv_load_policy:3,disablekb:1,fs:0},
            events:{
              onReady:function(e){e.target.mute();e.target.playVideo();},
              onStateChange:function(e){
                if(e.data==1 && !window.up){window.up=true;
                  e.target.setVolume(0);e.target.unMute();
                  var v=0;var t=setInterval(function(){v+=2;if(v>=$vol){v=$vol;clearInterval(t);}e.target.setVolume(v);},120);
                }
              }
            }});
        }
        </script></body></html>
        """.trimIndent()
    }
    Box(Modifier.size(1.dp).graphicsLayer { alpha = 0f }) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(320, 180)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    webChromeClient = WebChromeClient()
                    loadDataWithBaseURL("https://zapp.tv/", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
