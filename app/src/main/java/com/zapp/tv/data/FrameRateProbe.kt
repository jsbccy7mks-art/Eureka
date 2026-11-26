package com.zapp.tv.data

import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs

/** What a video file says (or shows) about its picture: frames per second, and its size. */
data class VideoShape(val fps: Float, val width: Int, val height: Int, val measured: Boolean)

/**
 * Finds a film's real frame rate before it plays, so the TV can be switched first (no stutter at
 * the start, no judder from a mismatched refresh rate). Reads only the start of the file: its stated
 * rate where it has one, and the timing of its first frames, which also tells 23.976 from 24.
 * Done ahead from the details page when a source is got ready; remembered per link.
 */
object FrameRateProbe {
    private val found = ConcurrentHashMap<String, VideoShape>()
    private val running = ConcurrentHashMap<String, CompletableDeferred<VideoShape?>>()

    private const val NTSC_FILM = 24000f / 1001f

    private fun key(url: String) = url.substringBefore('#')

    fun cached(url: String): VideoShape? = found[key(url)]

    /** Looks at [url]'s first frames (at most [timeoutMs]); null if it can't be read. */
    suspend fun probe(url: String, headers: Map<String, String>, timeoutMs: Long = 12_000): VideoShape? {
        if (!url.startsWith("http")) return null
        val k = key(url)
        found[k]?.let { return it }
        val mine = CompletableDeferred<VideoShape?>()
        val other = running.putIfAbsent(k, mine)
        if (other != null) return withTimeoutOrNull(timeoutMs) { other.await() }
        // MediaExtractor can't be interrupted, so it runs on its own thread and is simply left to
        // finish if it takes too long; the answer is kept for next time either way.
        thread(name = "zapp-fps", isDaemon = true) {
            val r = runCatching { read(url, headers) }.getOrNull()
            if (r != null) found[k] = r
            mine.complete(r)
            running.remove(k, mine)
        }
        return withTimeoutOrNull(timeoutMs) { mine.await() }
    }

    private fun read(url: String, headers: Map<String, String>): VideoShape? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(url, headers)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return null
            val f = ex.getTrackFormat(track)
            val w = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
            val h = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
            val stated = runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                ?: runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
            // The first 150 or so frames' times (in decode order: sorted before measuring).
            ex.selectTrack(track)
            val times = ArrayList<Long>()
            var n = 0
            while (n < 160) {
                val t = ex.sampleTime
                if (t < 0) break
                times.add(t)
                if (!ex.advance()) break
                n++
            }
            val measured = averageFrameUs(times)?.let { us -> snap(1_000_000f / us, us) }
            val fps = when {
                measured != null && valid(measured) -> measured
                stated != null && valid(stated) -> snap(stated, 1_000_000f / stated)
                else -> return null
            }
            return VideoShape(fps, w, h, measured != null && valid(measured))
        } finally {
            runCatching { ex.release() }
        }
    }

    /** The average gap between frames, ignoring the first few and the last few (B-frames). */
    private fun averageFrameUs(times: List<Long>): Float? {
        if (times.size < 40) return null
        val s = times.sorted()
        val from = 5
        val to = s.size - 6
        val gaps = to - from
        if (gaps < 30) return null
        return ((s[to] - s[from]).toFloat() / gaps).takeIf { it > 0f }
    }

    private fun valid(fps: Float) = fps.isFinite() && fps in 10f..121f

    /** To the nearest standard rate; near 24, the frame length decides 23.976 or 24. */
    fun snap(fps: Float, frameUs: Float): Float {
        if (fps in 23.5f..24.5f) {
            val a = abs(frameUs - 1_000_000f / NTSC_FILM)
            val b = abs(frameUs - 1_000_000f / 24f)
            if (minOf(a, b) <= 120f) return if (a <= b) NTSC_FILM else 24f
        }
        return when (fps) {
            in 23.90f..23.988f -> NTSC_FILM
            in 23.988f..24.1f -> 24f
            in 24.9f..25.1f -> 25f
            in 29.90f..29.985f -> 30000f / 1001f
            in 29.985f..30.1f -> 30f
            in 47.9f..48.1f -> 48f
            in 49.9f..50.1f -> 50f
            in 59.90f..59.97f -> 60000f / 1001f
            in 59.97f..60.1f -> 60f
            else -> fps
        }
    }
}
