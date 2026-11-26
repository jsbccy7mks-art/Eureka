package com.zapp.tv.data

import android.media.MediaCodecList
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
    private val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

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

    /** Looks it up in the background so it's ready by the time it's wanted. */
    suspend fun prefetch(videoId: String, maxHeight: Int) {
        runCatching { resolve(videoId, maxHeight) }
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

    private fun find(id: String, maxHeight: Int, audioOnly: Boolean): YtSource? {
        for (c in callers) {
            val o = ask(c, id) ?: continue
            if (o.optJSONObject("playabilityStatus")?.optString("status") != "OK") continue
            val sd = o.optJSONObject("streamingData") ?: continue
            val expires = System.currentTimeMillis() + (sd.optString("expiresInSeconds").toLongOrNull() ?: 18_000L) * 1000L - 10 * 60_000L
            pick(sd, maxHeight, audioOnly, expires)?.let { return it }
        }
        return null
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
                if (r.code == 429) quietUntil = System.currentTimeMillis() + 10 * 60_000L
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun pick(sd: JSONObject, maxHeight: Int, audioOnly: Boolean, expires: Long): YtSource? {
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
            .filter { it.optInt("height") in 1..maxHeight }
            .filter { decodes(it.optString("mimeType"), it.optInt("width"), it.optInt("height")) }
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
            video != null && audio != null -> YtSource(video.optString("url"), audio, false, video.optInt("height"), expires)
            hls != null -> YtSource(hls, null, true, maxHeight, expires)
            else -> sd.optJSONArray("formats").objects().filter { it.optString("url").startsWith("http") }
                .maxByOrNull { it.optInt("height") }
                ?.let { YtSource(it.optString("url"), null, false, it.optInt("height"), expires) }
        }
    }

    // ------------------------------------------------------------ what this TV can decode

    private val decoderCache = ConcurrentHashMap<String, Boolean>()

    private fun decodes(mime: String, width: Int, height: Int): Boolean {
        val type = when {
            mime.contains("avc1") -> "video/avc"
            mime.contains("vp9") || mime.contains("vp09") -> "video/x-vnd.on2.vp9"
            mime.contains("av01") -> "video/av01"
            else -> return false
        }
        val key = "$type|$width|$height"
        return decoderCache.getOrPut(key) {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    if (info.isEncoder || type !in info.supportedTypes) return@any false
                    // Software decoders can't keep up with big videos on a TV box.
                    if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated && height > 720) return@any false
                    val caps = info.getCapabilitiesForType(type).videoCapabilities ?: return@any false
                    width <= 0 || height <= 0 || caps.isSizeSupported(width, height)
                }
            }.getOrDefault(type == "video/avc")
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
