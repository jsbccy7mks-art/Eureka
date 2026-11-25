package com.zapp.tv.ui

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.zapp.tv.data.Channel
import com.zapp.tv.data.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One player for the whole app. The preview corner and fullscreen share it, so pressing OK on
 * a channel that is already previewing goes fullscreen instantly, without reconnecting.
 */
class Playback(
    context: Context,
    private val scope: CoroutineScope,
    private val resolve: (Channel) -> String,
) {

    val player: ExoPlayer = createPlayer(context)

    var channel by mutableStateOf<Channel?>(null)
        private set
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var aspect by mutableFloatStateOf(16f / 9f)
        private set

    /** True while the fullscreen player owns the video output. */
    var fullscreen by mutableStateOf(false)

    private var retries = 0
    private var retryJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0 && videoSize.width > 0) {
                aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                retries = 0
                error = null
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            if (retries < 3) {
                retries += 1
                val attempt = retries
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(1_000L * attempt)
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            } else {
                buffering = false
                error = "This channel isn't playing right now."
            }
        }
    }

    init {
        player.addListener(listener)
    }

    fun isOn(c: Channel?): Boolean = c != null && channel?.streamUrl == c.streamUrl

    fun play(c: Channel) {
        if (isOn(c) && error == null && player.playbackState != Player.STATE_IDLE) {
            channel = c
            return
        }
        retryJob?.cancel()
        retries = 0
        error = null
        buffering = true
        channel = c
        player.setMediaItem(MediaItem.fromUri(resolve(c)))
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        val c = channel ?: return
        error = null
        retries = 0
        buffering = true
        player.setMediaItem(MediaItem.fromUri(resolve(c)))
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun stop() {
        retryJob?.cancel()
        player.stop()
        player.clearMediaItems()
        channel = null
        buffering = false
        error = null
    }

    fun release() {
        retryJob?.cancel()
        player.removeListener(listener)
        player.release()
    }

    companion object {
        @OptIn(UnstableApi::class)
        private fun createPlayer(context: Context): ExoPlayer {
            // Small start-up buffer so channels open quickly when zapping.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_000, 2_500)
                .build()
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
            return ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
                .build()
        }
    }
}

/** Small video window (TextureView, so it can have rounded corners and overlays). */
@Composable
fun PreviewVideo(playback: Playback, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> TextureView(ctx) },
        update = { view ->
            if (!playback.fullscreen) playback.player.setVideoTextureView(view)
        },
        onRelease = { view -> playback.player.clearVideoTextureView(view) },
        modifier = modifier,
    )
}

/** Fullscreen video (SurfaceView, the most efficient path for big pictures). */
@Composable
fun FullscreenVideo(playback: Playback, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> SurfaceView(ctx).also { playback.player.setVideoSurfaceView(it) } },
        onRelease = { view -> playback.player.clearVideoSurfaceView(view) },
        modifier = modifier,
    )
}
