package com.zapp.tv.data

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A trailer (or theme tune) as plain streams Zapp's own player can play: the best video this TV
 * can decode, up to [maxHeight], and its audio separately; or one HLS link. Links expire, so they're
 * kept only until shortly before then.
 */
data class YtSource(val video: String?, val audio: String?, val hls: Boolean, val height: Int, val expires: Long)

/**
 * Finds the streams behind a YouTube video by asking YouTube's player service the way its own apps
 * do, so trailers play in Zapp's player (sharp, in sync, no YouTube buttons) instead of a web page.
 * YouTube changes this from time to time; when it stops working, the web player is used instead.
 */
class YouTube(private val http: OkHttpClient) {
    private val client = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    /** What happened last time (for Settings, Updates and about, Test trailers). */
    @Volatile var lastReport: String = "Nothing tried yet."
        private set

    /** The player couldn't play what was found (so the web player took over). */
    fun note(problem: String) {
        lastReport = "Found the streams, but they wouldn't play: $problem"
    }

    private val cache = ConcurrentHashMap<String, YtSource>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap<String, CompletableDeferred<YtSource?>>()

    /** After YouTube says "too many requests", leave it alone for a while. */
    @Volatile private var quietUntil = 0L

    /** One way of asking: how a particular YouTube app introduces itself. */
    private class Caller(val name: String, val id: Int, val version: String, val userAgent: String, val extra: Map<String, Any>)

    private val callers = listOf(
        Caller(
            "ANDROID_VR", 28, "1.60.19",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
        ),
        Caller(
            "IOS", 5, "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        ),
    )

    fun cached(videoId: String, maxHeight: Int): YtSource? =
        cache["$videoId|$maxHeight|false"]?.takeIf { it.expires > System.currentTimeMillis() + 60_000 }

    fun cachedAudio(videoId: String): YtSource? =
        cache["$videoId|0|true"]?.takeIf { it.expires > System.currentTimeMillis() + 60_000 }

    /** Looks it up in the background so it's ready by the time it's wanted. */
    suspend fun prefetch(videoId: String, maxHeight: Int, audioOnly: Boolean = false) {
        runCatching { resolve(videoId, maxHeight, audioOnly) }
    }

    /** The streams for [videoId], or null if they can't be found (the web player is used then). */
    suspend fun resolve(videoId: String, maxHeight: Int, audioOnly: Boolean = false): YtSource? {
        val id = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(11)
        if (id.length != 11) return null
        val key = "$id|$maxHeight|$audioOnly"
        cache[key]?.takeIf { it.expires > System.currentTimeMillis() + 60_000 }?.let { return it }
        misses[key]?.let { if (System.currentTimeMillis() - it < 30 * 60_000L) return null }
        if (System.currentTimeMillis() < quietUntil) return null
        // Asked for twice at once (Home, then the details page): share the one answer.
        val mine = CompletableDeferred<YtSource?>()
        val other = running.putIfAbsent(key, mine)
        if (other != null) return other.await()
        return try {
            val s = withContext(Dispatchers.IO) { find(id, maxHeight, audioOnly) }
            if (s != null) cache[key] = s else misses[key] = System.currentTimeMillis()
            mine.complete(s)
            s
        } catch (e: Exception) {
            mine.complete(null)
            null
        } finally {
            running.remove(key, mine)
        }
    }

    /** Asks as each app at once and takes the first good answer (in the callers' order of preference). */
    private fun find(id: String, maxHeight: Int, audioOnly: Boolean): YtSource? {
        val started = System.currentTimeMillis()
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(callers.size)
        try {
            val jobs = callers.map { c ->
                pool.submit(java.util.concurrent.Callable<YtSource?> {
                    val o = ask(c, id)
                    if (o == null) { notes.add("${c.name}: no answer"); return@Callable null }
                    val st = o.optJSONObject("playabilityStatus")
                    if (st?.optString("status") != "OK") {
                        notes.add("${c.name}: ${st?.optString("status")} ${st?.optString("reason").orEmpty()}".trim())
                        return@Callable null
                    }
                    val sd = o.optJSONObject("streamingData")
                    if (sd == null) { notes.add("${c.name}: no streams"); return@Callable null }
                    val expires = System.currentTimeMillis() + (sd.optString("expiresInSeconds").toLongOrNull() ?: 18_000L) * 1000L - 10 * 60_000L
                    // The iPhone answer's separate files need an extra token to download; its HLS stream doesn't.
                    pick(sd, maxHeight, audioOnly, expires, hlsOnly = c.name == "IOS").also { if (it == null) notes.add("${c.name}: nothing playable") }
                })
            }
            for ((i, j) in jobs.withIndex()) {
                val s = runCatching { j.get(9, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val secs = "%.1f".format(java.util.Locale.UK, (System.currentTimeMillis() - started) / 1000.0)
                lastReport = "Axon's player: " + (if (s.hls) "HLS stream" else if (audioOnly) "audio" else "${s.height}p video and audio") + " from ${callers[i].name} in $secs s." +
                    (if (notes.isNotEmpty()) " (" + notes.joinToString("; ") + ")" else "")
                return s
            }
            lastReport = "Couldn't get the streams (the web player is used instead). " + notes.joinToString("; ")
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** A quick check from Settings: a well-known video, then a theme tune. */
    suspend fun test(): String {
        cache.clear()
        misses.clear()
        quietUntil = 0
        val v = resolve("dQw4w9WgXcQ", 1080)
        val vr = lastReport + (v?.let { " " + reach(it) } ?: "")
        val a = resolve("yB-c85V8Zsg", 0, audioOnly = true)
        val ar = lastReport + (a?.let { " " + reach(it) } ?: "")
        return "Trailer: $vr\nTheme tune: $ar"
    }

    /** Whether the first bytes of each file actually come down (the player's first step). */
    private suspend fun reach(s: YtSource): String = withContext(Dispatchers.IO) {
        fun probe(label: String, url: String?): String? {
            url ?: return null
            val u = if (url.contains("googlevideo.com") && !s.hls) "$url&range=0-1023" else url
            val code = runCatching {
                client.newCall(Request.Builder().url(u).header("User-Agent", callers[0].userAgent).build()).execute().use { it.code.toString() }
            }.getOrElse { "error (${it.javaClass.simpleName})" }
            return "$label $code"
        }
        "Download check: " + listOfNotNull(probe(if (s.hls) "stream" else "video", s.video), probe("audio", s.audio)).joinToString(", ") + "."
    }

    private fun ask(c: Caller, id: String): JSONObject? {
        val clientInfo = JSONObject().put("clientName", c.name).put("clientVersion", c.version).put("hl", "en").put("gl", "GB")
        c.extra.forEach { (k, v) -> clientInfo.put(k, v) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientInfo))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.userAgent)
            .header("X-YouTube-Client-Name", c.id.toString())
            .header("X-YouTube-Client-Version", c.version)
            .header("Origin", "https://www.youtube.com")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (r.code == 429) quietUntil = System.currentTimeMillis() + 5 * 60_000L
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun pick(sd: JSONObject, maxHeight: Int, audioOnly: Boolean, expires: Long, hlsOnly: Boolean = false): YtSource? {
        if (hlsOnly) {
            val h = sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") } ?: return null
            return YtSource(h, null, true, if (audioOnly) 0 else maxHeight, expires)
        }
        val adaptive = sd.optJSONArray("adaptiveFormats").objects().filter { it.optString("url").startsWith("http") }
        val audio = adaptive
            .filter { it.optString("mimeType").startsWith("audio/") }
            // The original-language track where there are several; AAC before Opus; then the best.
            .sortedWith(
                compareByDescending<JSONObject> { it.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") != false }
                    .thenByDescending { it.optString("mimeType").startsWith("audio/mp4") }
                    .thenByDescending { it.optInt("bitrate") },
            )
            .firstOrNull()?.optString("url")
        if (audioOnly) return audio?.let { YtSource(null, it, false, 0, expires) }

        val video = adaptive
            .filter { it.optString("mimeType").startsWith("video/") }
            // Within the 16:9 box of the cap (a 4:3 or 21:9 video is judged by its larger side too).
            .filter { it.optInt("height") in 1..maxHeight && it.optInt("width") <= maxHeight * 16 / 9 + 8 }
            // HDR can't be shown behind the page (and some boxes fail on it outright); YouTube always
            // has an ordinary version too.
            .filter { !isHdr(it) }
            .filter { decodes(it.optString("mimeType"), it.optInt("width"), it.optInt("height"), it.optInt("fps")) }
            .sortedWith(
                compareByDescending<JSONObject> { it.optInt("height") }
                    .thenByDescending { it.optInt("fps") }
                    // At the same size, H.264 is the easiest on older boxes.
                    .thenByDescending { it.optString("mimeType").contains("avc1") }
                    .thenByDescending { it.optInt("bitrate") },
            )
            .firstOrNull()
        val hls = sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") }
        return when {
            video != null && audio != null -> YtSource(fastest(video.optString("url")), fastest(audio), false, video.optInt("height"), expires)
            hls != null -> YtSource(hls, null, true, maxHeight, expires)
            else -> sd.optJSONArray("formats").objects().filter { it.optString("url").startsWith("http") }
                .maxByOrNull { it.optInt("height") }
                ?.let { YtSource(it.optString("url"), null, false, it.optInt("height"), expires) }
        }
    }

    // ------------------------------------------------------------ what this TV can decode

    private val decoderCache = ConcurrentHashMap<String, Boolean>()

    private fun isHdr(f: JSONObject): Boolean {
        val transfer = f.optJSONObject("colorInfo")?.optString("transferCharacteristics").orEmpty()
        return transfer.contains("SMPTEST2084") || transfer.contains("ARIB_STD_B67") || f.optString("qualityLabel").contains("HDR", true)
    }

    /**
     * Whether this TV has a decoder for the stream exactly as it is (codec, profile and level, size
     * and frame rate), asked the way the player asks. Big videos need a hardware decoder.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun decodes(mime: String, width: Int, height: Int, fps: Int): Boolean {
        val codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1)?.trim().orEmpty()
        val type = when {
            mime.contains("avc1") -> "video/avc"
            mime.contains("vp9") || mime.contains("vp09") -> "video/x-vnd.on2.vp9"
            mime.contains("av01") -> "video/av01"
            else -> return false
        }
        return decoderCache.getOrPut("$codecs|$type|$width|$height|$fps") {
            runCatching {
                val format = androidx.media3.common.Format.Builder()
                    .setSampleMimeType(type)
                    .setCodecs(codecs.ifBlank { null })
                    .apply {
                        if (width > 0 && height > 0) setWidth(width).setHeight(height)
                        if (fps > 0) setFrameRate(fps.toFloat())
                    }
                    .build()
                androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(type, false, false).any { info ->
                    // Software decoders can't keep up with big videos on a TV box.
                    if (height > 720 && !info.hardwareAccelerated) return@any false
                    info.isFormatSupported(format)
                }
            }.getOrDefault(type == "video/avc")
        }
    }

    /**
     * YouTube lists other servers holding the same file (the "mn" part of the link). Some are slow
     * or refuse; try them all at once for a moment and use the first that answers.
     */
    private fun fastest(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = uri.host ?: return url
        val servers = Regex("[?&]mn=([^&]+)").find(url)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val candidates = LinkedHashSet<String>().apply { add(url) }
        servers.forEachIndexed { i, server ->
            val alt = host.replaceFirst(Regex("^rr\\d+---"), "rr${i + 1}---").replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), server)
            if (alt != host) add(url.replaceFirst(host, alt))
        }
        val probe = client.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val done = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(candidates.size)
        try {
            candidates.forEach { c ->
                pool.submit {
                    val ok = runCatching {
                        probe.newCall(Request.Builder().url(c).header("User-Agent", callers[0].userAgent).header("Range", "bytes=0-0").build())
                            .execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                    if (ok && found.compareAndSet(null, c)) done.countDown()
                }
            }
            done.await(2_500, TimeUnit.MILLISECONDS)
            return found.get() ?: url
        } finally {
            pool.shutdownNow()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
