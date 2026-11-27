package com.zapp.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.seekr.previews.android.SeekrTrack

/**
 * Little pictures for the seek bar, from Seekr: ready-made, downloaded as small sheets, so
 * nothing is read from the film or decoded on the TV while you seek. Titles Seekr doesn't have
 * (or no Seekr key) simply show the time without a picture.
 */
class SeekThumbs(private val track: SeekrTrack, private val scope: CoroutineScope) {
    /** The picture for where you're seeking: the moment it's from, and the picture. */
    var shown by mutableStateOf<Pair<Long, ImageBitmap>?>(null)
        private set

    private var job: Job? = null
    private var wanted = -1L

    fun request(ms: Long) {
        if (ms == wanted) return
        wanted = ms
        // Only the latest position matters: an older request still downloading is dropped.
        job?.cancel()
        job = scope.launch {
            val t = runCatching { track.thumbnailFor(ms) }.getOrNull() ?: return@launch
            // Past the middle of a picture's stretch, the next one is closer to where you are.
            val pick = if (ms - t.cueStartMs > t.cueEndMs - ms) runCatching { track.thumbnailFor(t.cueEndMs) }.getOrNull() ?: t else t
            shown = pick.cueStartMs to pick.bitmap.asImageBitmap()
        }
    }

    /** Fetches the sheets around [ms] ahead of time, so the first pictures are instant. */
    fun warm(ms: Long) {
        scope.launch { runCatching { track.warm(ms, limit = 3) } }
    }
}

/** Seek-bar pictures for what [playback] is playing, once Seekr has found them (null otherwise). */
@Composable
fun rememberSeekThumbs(playback: Playback, mvm: MediaViewModel): SeekThumbs? {
    val info = playback.media ?: return null
    val scope = rememberCoroutineScope()
    val thumbs by produceState<SeekThumbs?>(null, info.videoId, mvm.seekr.enabled) {
        value = null
        if (!mvm.seekr.enabled || info.videoId.startsWith("catchup:")) return@produceState
        // Seekr matches its pictures to the file's length, so wait until the player knows it.
        var d = playback.player.duration
        while (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) {
            delay(500)
            d = playback.player.duration
        }
        val track = mvm.seekr.track(info.videoId, info.type, d, playback.player.currentPosition) ?: return@produceState
        value = SeekThumbs(track, scope).also { it.warm(playback.player.currentPosition) }
    }
    return thumbs
}
