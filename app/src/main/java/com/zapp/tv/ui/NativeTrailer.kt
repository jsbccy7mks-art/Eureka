package com.zapp.tv.ui

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.zapp.tv.ZappApp
import com.zapp.tv.data.YtChunksFactory
import com.zapp.tv.data.YtSource
import kotlinx.coroutines.delay

private const val YT_UA = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

fun trailerHeightCap(): Int = if (FocusStyle.trailerBest) 2160 else 1080

/**
 * A YouTube video played by Zapp's own player: it stays invisible until its first frame is
 * actually drawn, then fades in (with its sound rising, if wanted); it fills its box like artwork,
 * zooming in a little when the video has black bars of its own. Errors call [onFailed].
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NativeVideo(
    source: YtSource,
    modifier: Modifier = Modifier,
    sound: Boolean,
    volume: Float = 0.7f,
    loop: Boolean = true,
    startMs: Long = 0L,
    paused: Boolean = false,
    onFailed: () -> Unit = {},
    onEnded: () -> Unit = {},
    onPlayer: (ExoPlayer?) -> Unit = {},
    /** The picture (or, for audio, the sound) has actually started. */
    onPlaying: () -> Unit = {},
    /** Sound only (theme tunes): no picture is decoded or drawn, even from a video stream. */
    audioOnly: Boolean = false,
) {
    val context = LocalContext.current
    val failed by rememberUpdatedState(onFailed)
    val ended by rememberUpdatedState(onEnded)
    val player = remember(source) {
        val factory = DefaultMediaSourceFactory(YtChunksFactory(YT_UA))
        // Start as soon as there's half a second to play (trailers and tunes, not films).
        val quick = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 30_000, 500, 1_500)
            .build()
        ExoPlayer.Builder(context).setMediaSourceFactory(factory).setLoadControl(quick).build().apply {
            if (audioOnly) {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true)
                    .setMaxVideoSize(256, 144)
                    .build()
            }
            val main = source.video ?: source.audio!!
            val item = MediaItem.Builder().setUri(main).apply { if (source.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            if (source.video != null && source.audio != null && !source.hls) {
                setMediaSource(MergingMediaSource(factory.createMediaSource(item), factory.createMediaSource(MediaItem.fromUri(source.audio))))
            } else {
                setMediaSource(factory.createMediaSource(item))
            }
            this.volume = 0f
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            if (startMs > 0) seekTo(startMs)
            playWhenReady = true
            prepare()
        }
    }
    var shown by remember(player) { mutableStateOf(false) }
    var videoAspect by remember(player) { mutableFloatStateOf(16f / 9f) }
    var zoom by remember(player) { mutableFloatStateOf(1f) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player) {
        onPlayer(player)
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Audio only (theme tunes): "shown" once it's ready to play.
                if (state == Player.STATE_READY && (audioOnly || source.video == null)) shown = true
                if (state == Player.STATE_ENDED) ended()
            }
            override fun onPlayerError(error: PlaybackException) {
                (context.applicationContext as ZappApp).youtube.note(error.errorCodeName + (error.cause?.message?.let { " ($it)" } ?: ""))
                failed()
            }
        }
        player.addListener(l)
        onDispose {
            onPlayer(null)
            player.removeListener(l)
            player.release()
        }
    }
    LaunchedEffect(paused, player) { player.playWhenReady = !paused }

    // Fade in on the first frame; sound rises with it.
    val alpha = remember(player) { Animatable(0f) }
    val playingNow by rememberUpdatedState(onPlaying)
    LaunchedEffect(shown) {
        if (shown) {
            playingNow()
            alpha.animateTo(1f, tween(450))
        }
    }
    LaunchedEffect(shown, sound, volume) {
        if (!shown) return@LaunchedEffect
        val target = if (sound) volume.coerceIn(0f, 1f) else 0f
        val from = player.volume
        val steps = 12
        for (i in 1..steps) {
            player.volume = from + (target - from) * i / steps
            delay(60)
        }
    }

    // Black bars inside the video: sample a few frames and zoom just enough to hide them.
    LaunchedEffect(shown, texture) {
        val tv = texture ?: return@LaunchedEffect
        if (!shown) return@LaunchedEffect
        repeat(3) {
            delay(1_500)
            val bars = runCatching { letterbox(tv) }.getOrDefault(0f)
            val z = if (bars >= 0.04f) (1f / (1f - 2f * bars)).coerceAtMost(1.45f) else 1f
            if (z > zoom + 0.01f) {
                val anim = Animatable(zoom)
                anim.animateTo(z, tween(400)) { zoom = value }
            }
        }
    }

    if (audioOnly || source.video == null) return // sound only: nothing to draw

    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        // Cover the box (like artwork), keeping the video's shape.
        val boxAspect = if (maxHeight.value > 0) maxWidth / maxHeight else 16f / 9f
        val (w, h) = if (videoAspect > boxAspect) (maxHeight * videoAspect) to maxHeight else maxWidth to (maxWidth / videoAspect)
        AndroidView(
            modifier = Modifier.requiredSize(w, h),
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    tv.isFocusable = false
                    tv.alpha = 0f
                    player.setVideoTextureView(tv)
                    texture = tv
                }
            },
            update = { tv ->
                tv.alpha = alpha.value
                tv.scaleX = zoom
                tv.scaleY = zoom
            },
            onRelease = { tv -> player.clearVideoTextureView(tv) },
        )
    }
}

/** How much of the picture's height is a black bar at the top (and bottom), from 0 to about 0.2. */
private fun letterbox(tv: TextureView): Float {
    val bmp = tv.getBitmap(64, 36) ?: return 0f
    try {
        fun dark(y: Int): Boolean {
            var sum = 0
            for (x in 4 until 60 step 4) {
                val c = bmp.getPixel(x, y)
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
            }
            return sum / (14 * 3) < 18
        }
        var top = 0
        while (top < 10 && dark(top)) top++
        var bottom = 0
        while (bottom < 10 && dark(35 - bottom)) bottom++
        // Both edges dark means real bars (one dark edge is just a dark scene).
        return minOf(top, bottom) / 36f
    } finally {
        bmp.recycle()
    }
}

/** The YouTube trailer, from Zapp's player when it can be found, else from the YouTube web player. */
@Composable
fun TrailerBackground(youtubeId: String, modifier: Modifier = Modifier, sound: Boolean = false, startMs: Long = 4_000L, onPlaying: () -> Unit = {}) {
    val yt = (LocalContext.current.applicationContext as ZappApp).youtube
    val cap = trailerHeightCap()
    var source by remember(youtubeId, cap) { mutableStateOf(yt.cached(youtubeId, cap)) }
    var web by remember(youtubeId) { mutableStateOf(false) }
    LaunchedEffect(youtubeId, cap) {
        if (source == null) source = yt.resolve(youtubeId, cap)
        if (source == null) web = true
    }
    val s = source
    when {
        web -> WebTrailer(youtubeId, modifier, sound, onPlaying = onPlaying)
        s != null -> NativeVideo(s, modifier, sound = sound, startMs = startMs, onFailed = { web = true }, onPlaying = onPlaying)
    }
}

/**
 * A trailer full screen with sound, and simple controls: OK pauses, Left and Right go back and
 * forward ten seconds; a thin line shows where you are. Back closes it.
 */
@Composable
fun FullTrailer(youtubeId: String, onClose: () -> Unit) {
    val yt = (LocalContext.current.applicationContext as ZappApp).youtube
    val cap = trailerHeightCap()
    var source by remember(youtubeId) { mutableStateOf(yt.cached(youtubeId, cap)) }
    var web by remember(youtubeId) { mutableStateOf(false) }
    LaunchedEffect(youtubeId) {
        if (source == null) source = yt.resolve(youtubeId, cap)
        if (source == null) web = true
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var paused by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) }
    var barVisible by remember { mutableStateOf(true) }
    LaunchedEffect(poke, paused) {
        barVisible = true
        if (!paused) {
            delay(3_000)
            barVisible = false
        }
    }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            player?.let { pos = it.currentPosition; dur = it.duration.coerceAtLeast(0L) }
            delay(250)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); focus.tryRequest() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) return@onPreviewKeyEvent false // BackHandler closes it
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.DirectionCenter || e.key == Key.Enter
                poke++
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.Spacebar -> { paused = !paused; true }
                    Key.DirectionLeft, Key.MediaRewind -> { player?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }; true }
                    Key.DirectionRight, Key.MediaFastForward -> { player?.let { it.seekTo(it.currentPosition + 10_000) }; true }
                    else -> true
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        val s = source
        when {
            web -> WebTrailer(youtubeId, Modifier.fillMaxSize(), sound = true)
            s != null -> NativeVideo(
                s, Modifier.fillMaxSize(), sound = true, volume = 1f, loop = false, startMs = 0L, paused = paused,
                onFailed = { web = true }, onEnded = onClose, onPlayer = { player = it },
            )
            else -> ZappLoader(40.dp, Modifier.align(Alignment.Center))
        }
        if (!web && barVisible) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 56.dp, vertical = 30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (paused) "Paused" else "Trailer", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${formatClock(pos)} / ${formatClock(dur)}   ·   OK pause   ·   ◀ ▶ 10 s   ·   Back close", color = Color(0xB3FFFFFF), fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(8.dp))
                ProgressLine(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, Modifier.fillMaxWidth(), track = Color(0x33FFFFFF), fill = Color.White)
            }
        }
    }
}

/** A collection's theme tune, quietly, from Zapp's player when it can be found, else the web player. */
@Composable
fun ThemeMusic(youtubeId: String, volume: Int) {
    val yt = (LocalContext.current.applicationContext as ZappApp).youtube
    var source by remember(youtubeId) { mutableStateOf(yt.cachedAudio(youtubeId)) }
    var web by remember(youtubeId) { mutableStateOf(false) }
    LaunchedEffect(youtubeId) {
        if (source == null) source = yt.resolve(youtubeId, 0, audioOnly = true)
        if (source == null) web = true
    }
    val s = source
    when {
        web -> WebThemeMusic(youtubeId, volume)
        s != null -> NativeVideo(s, sound = true, volume = volume.coerceIn(5, 80) / 100f, loop = true, onFailed = { web = true }, audioOnly = true)
    }
}

/** A plain audio link (a theme preview), quietly, on a loop. */
@Composable
fun PreviewMusic(url: String, volume: Int, onPlaying: () -> Unit = {}, onFailed: () -> Unit = {}) {
    val source = remember(url) { YtSource(null, url, false, 0, Long.MAX_VALUE) }
    NativeVideo(source, sound = true, volume = volume.coerceIn(5, 80) / 100f, loop = true, audioOnly = true, onPlaying = onPlaying, onFailed = onFailed)
}
