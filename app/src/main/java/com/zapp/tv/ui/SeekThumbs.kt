package com.zapp.tv.ui

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors

/**
 * Little pictures for the seek bar: frames read from the film itself, around wherever you're
 * seeking to (every ten seconds), one at a time in the background and kept for a while.
 * Streams that can't be read this way (HLS) simply show no pictures.
 */
class SeekThumbs(private val url: String, private val headers: Map<String, String>) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, ImageBitmap>(80)
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var wanted = -1L
    @Volatile private var broken = false
    @Volatile private var closed = false

    /** The nearest picture so far: where it's from, and the picture. */
    var shown by mutableStateOf<Pair<Long, ImageBitmap>?>(null)
        private set

    fun request(ms: Long) {
        if (broken || closed) return
        val key = (ms / 10_000L) * 10_000L
        cache.get(key)?.let { shown = key to it; return }
        if (wanted == key) return
        wanted = key
        worker.execute {
            if (closed || wanted != key) return@execute // already moved on
            try {
                val r = retriever ?: MediaMetadataRetriever().also { it.setDataSource(url, headers); retriever = it }
                val frame = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(key * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                } else {
                    r.getFrameAtTime(key * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (frame != null) {
                    val img = frame.asImageBitmap()
                    cache.put(key, img)
                    main.post { if (!closed) shown = key to img }
                }
            } catch (e: Exception) {
                broken = true
            } catch (e: Error) {
                broken = true
            }
        }
    }

    fun close() {
        closed = true
        worker.execute { runCatching { retriever?.release() } }
        worker.shutdown()
    }
}

/** Pictures for what [playback] is playing (films and episodes only). */
@Composable
fun rememberSeekThumbs(playback: Playback): SeekThumbs? {
    val uri = playback.player.currentMediaItem?.localConfiguration?.uri?.toString()
    val thumbs = remember(uri) {
        if (uri == null || !uri.startsWith("http") || uri.contains(".m3u8") || uri.contains("/hls/")) null
        else SeekThumbs(uri, playback.media?.headers.orEmpty())
    }
    DisposableEffect(thumbs) { onDispose { thumbs?.close() } }
    return thumbs
}
