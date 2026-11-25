package com.zapp.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** What Zapp needs to fetch one file of a torrent through a debrid service. */
data class TorrentRef(val infoHash: String, val fileIdx: Int?, val filename: String?, val title: String) {
    val magnet: String get() = "magnet:?xt=urn:btih:$infoHash&dn=" + java.net.URLEncoder.encode(title.take(80), "UTF-8")
}

class DebridException(message: String) : Exception(message)

/** A debrid service turns a torrent it has (or fetches) into a normal link the player can stream. */
interface DebridService {
    val id: String
    val name: String
    val connected: Boolean
    suspend fun resolve(t: TorrentRef): String
    fun signOut()
}

private val VideoExt = Regex("\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm|wmv|mpg|mpeg)$", RegexOption.IGNORE_CASE)

/** Picks the file that was asked for: by name if the add-on gave one, else by position, else the biggest video. */
private fun <T> pickFile(files: List<T>, t: TorrentRef, name: (T) -> String, size: (T) -> Long, indexOf: (T) -> Int?): T? {
    if (files.isEmpty()) return null
    t.filename?.let { wanted -> files.firstOrNull { name(it).substringAfterLast('/').equals(wanted, ignoreCase = true) }?.let { return it } }
    t.fileIdx?.let { idx -> files.firstOrNull { indexOf(it) == idx }?.let { return it } }
    val videos = files.filter { VideoExt.containsMatchIn(name(it)) }
    return (videos.ifEmpty { files }).maxByOrNull { size(it) }
}

private fun OkHttpClient.text(request: Request): Pair<Int, String> =
    newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }

// ---------------------------------------------------------------------------------------------- Real-Debrid

/**
 * Real-Debrid with its device sign-in for open-source apps: the TV shows a code, you approve it on
 * real-debrid.com, and Zapp gets its own credentials. Tokens last an hour and are refreshed as needed.
 */
class RealDebrid(private val http: OkHttpClient, private val settings: Settings) : DebridService {
    override val id = "rd"
    override val name = "Real-Debrid"
    override val connected: Boolean get() = settings.secret("rdRefresh").isNotBlank()

    data class Device(val deviceCode: String, val userCode: String, val url: String, val directUrl: String, val interval: Int, val expiresIn: Int)

    private val oauth = "https://api.real-debrid.com/oauth/v2"
    private val rest = "https://api.real-debrid.com/rest/1.0"

    suspend fun startSignIn(): Device = withContext(Dispatchers.IO) {
        val (code, body) = http.text(Request.Builder().url("$oauth/device/code?client_id=$OPEN_SOURCE_CLIENT&new_credentials=yes").build())
        if (code != 200) throw DebridException("Real-Debrid answered with HTTP $code.")
        val o = JSONObject(body)
        Device(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            url = o.optString("verification_url", "https://real-debrid.com/device"),
            directUrl = o.optString("direct_verification_url").ifBlank { o.optString("verification_url", "https://real-debrid.com/device") },
            interval = o.optInt("interval", 5).coerceAtLeast(3),
            expiresIn = o.optInt("expires_in", 600),
        )
    }

    /** True once approved and signed in; false while still waiting. */
    suspend fun poll(device: Device): Boolean = withContext(Dispatchers.IO) {
        val (code, body) = http.text(Request.Builder().url("$oauth/device/credentials?client_id=$OPEN_SOURCE_CLIENT&code=${device.deviceCode}").build())
        if (code != 200) return@withContext false
        val creds = JSONObject(body)
        val clientId = creds.optString("client_id")
        val secret = creds.optString("client_secret")
        if (clientId.isBlank() || secret.isBlank()) return@withContext false
        val tokens = token(clientId, secret, device.deviceCode) ?: return@withContext false
        settings.setSecret("rdClientId", clientId)
        settings.setSecret("rdClientSecret", secret)
        saveTokens(tokens)
        true
    }

    private fun token(clientId: String, secret: String, code: String): JSONObject? {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", secret)
            .add("code", code)
            .add("grant_type", "http://oauth.net/grant_type/device/1.0")
            .build()
        val (status, body) = http.text(Request.Builder().url("$oauth/token").post(form).build())
        if (status != 200) return null
        return JSONObject(body).takeIf { it.has("access_token") }
    }

    private fun saveTokens(o: JSONObject) {
        settings.setSecret("rdAccess", o.getString("access_token"))
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { settings.setSecret("rdRefresh", it) }
        settings.setSecret("rdExpires", (System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000).toString())
    }

    private fun accessToken(): String {
        val expires = settings.secret("rdExpires").toLongOrNull() ?: 0
        if (settings.secret("rdAccess").isNotBlank() && System.currentTimeMillis() < expires) return settings.secret("rdAccess")
        val o = token(settings.secret("rdClientId"), settings.secret("rdClientSecret"), settings.secret("rdRefresh"))
            ?: throw DebridException("Real-Debrid signed Zapp out. Connect it again in Settings, Accounts and sync.")
        saveTokens(o)
        return settings.secret("rdAccess")
    }

    private fun call(path: String, form: FormBody? = null): Pair<Int, String> {
        val b = Request.Builder().url("$rest$path").header("Authorization", "Bearer ${accessToken()}")
        return http.text(if (form != null) b.post(form).build() else b.build())
    }

    override suspend fun resolve(t: TorrentRef): String = withContext(Dispatchers.IO) {
        val (c1, added) = call("/torrents/addMagnet", FormBody.Builder().add("magnet", t.magnet).build())
        if (c1 !in 200..299) throw DebridException(rdError(added, "Real-Debrid didn't take this source."))
        val torrentId = JSONObject(added).getString("id")

        var info = JSONObject()
        // Wait for Real-Debrid to read the file list, then choose the file.
        for (i in 0 until 20) {
            info = JSONObject(call("/torrents/info/$torrentId").second)
            val status = info.optString("status")
            if (status == "waiting_files_selection" || status == "downloaded" || status == "downloading" || status == "queued") break
            if (status in setOf("error", "magnet_error", "virus", "dead")) throw DebridException("Real-Debrid couldn't open this source.")
            delay(1_000)
        }
        if (info.optString("status") == "waiting_files_selection") {
            val files = info.optJSONArray("files") ?: JSONArray()
            val list = (0 until files.length()).map { files.getJSONObject(it) }
            val chosen = pickFile(list, t, { it.optString("path") }, { it.optLong("bytes") }, { it.optInt("id") - 1 })
                ?: throw DebridException("Real-Debrid found no video in this source.")
            call("/torrents/selectFiles/$torrentId", FormBody.Builder().add("files", chosen.optInt("id").toString()).build())
        }
        for (i in 0 until 8) {
            info = JSONObject(call("/torrents/info/$torrentId").second)
            if (info.optString("status") == "downloaded") break
            delay(1_500)
        }
        if (info.optString("status") != "downloaded") {
            throw DebridException("Real-Debrid doesn't have this one ready. It's downloading it now; try again later or pick another source.")
        }
        val link = info.optJSONArray("links")?.optString(0)?.takeIf { it.isNotBlank() }
            ?: throw DebridException("Real-Debrid has this source, but no video link came back.")
        val (c2, unrestricted) = call("/unrestrict/link", FormBody.Builder().add("link", link).build())
        if (c2 !in 200..299) throw DebridException(rdError(unrestricted, "Real-Debrid couldn't prepare the link."))
        JSONObject(unrestricted).optString("download").takeIf { it.startsWith("http") }
            ?: throw DebridException("Real-Debrid couldn't prepare the link.")
    }

    private fun rdError(body: String, fallback: String): String = try {
        val o = JSONObject(body)
        when (o.optInt("error_code")) {
            8, 9 -> "Real-Debrid signed Zapp out. Connect it again in Settings, Accounts and sync."
            20, 21 -> "Real-Debrid needs a premium account for this."
            else -> o.optString("error").takeIf { it.isNotBlank() }?.let { "Real-Debrid: ${it.replace('_', ' ')}" } ?: fallback
        }
    } catch (e: Exception) {
        fallback
    }

    override fun signOut() {
        listOf("rdAccess", "rdRefresh", "rdExpires", "rdClientId", "rdClientSecret").forEach { settings.setSecret(it, "") }
    }

    companion object {
        /** Real-Debrid's published client id for open-source apps. */
        const val OPEN_SOURCE_CLIENT = "X245A4XAIBGVM"
    }
}

// ---------------------------------------------------------------------------------------------- AllDebrid

/** AllDebrid with its PIN sign-in: the TV shows a PIN and a link, you approve it, Zapp gets an API key. */
class AllDebrid(private val http: OkHttpClient, private val settings: Settings) : DebridService {
    override val id = "ad"
    override val name = "AllDebrid"
    override val connected: Boolean get() = settings.secret("adKey").isNotBlank()

    data class Pin(val pin: String, val check: String, val url: String, val expiresIn: Int)

    private val base = "https://api.alldebrid.com"
    private val agent = "zapptv"

    private fun get(path: String, params: Map<String, String> = emptyMap(), key: String? = null): JSONObject {
        val url = "$base$path".toHttpUrl().newBuilder().addQueryParameter("agent", agent)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .apply { if (key != null) addQueryParameter("apikey", key) }
            .build()
        val req = Request.Builder().url(url).apply { if (key != null) header("Authorization", "Bearer $key") }.build()
        val (_, body) = http.text(req)
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw DebridException("AllDebrid sent something unexpected.")
        }
        if (o.optString("status") != "success") {
            val err = o.optJSONObject("error")
            val code = err?.optString("code").orEmpty()
            throw DebridException(
                when {
                    code.startsWith("AUTH") -> "AllDebrid signed Zapp out. Connect it again in Settings, Accounts and sync."
                    code == "MUST_BE_PREMIUM" -> "AllDebrid needs a premium account for this."
                    code.contains("NO_SERVER") -> "AllDebrid doesn't allow this network (VPN or server address)."
                    else -> err?.optString("message")?.takeIf { it.isNotBlank() }?.let { "AllDebrid: $it" } ?: "AllDebrid refused that."
                },
            )
        }
        return o.optJSONObject("data") ?: JSONObject()
    }

    suspend fun startSignIn(): Pin = withContext(Dispatchers.IO) {
        val d = get("/v4/pin/get")
        Pin(d.getString("pin"), d.getString("check"), d.optString("user_url").ifBlank { "https://alldebrid.com/pin/" }, d.optInt("expires_in", 600))
    }

    suspend fun poll(pin: Pin): Boolean = withContext(Dispatchers.IO) {
        val d = try {
            get("/v4/pin/check", mapOf("check" to pin.check, "pin" to pin.pin))
        } catch (e: DebridException) {
            return@withContext false
        }
        if (!d.optBoolean("activated")) return@withContext false
        val key = d.optString("apikey")
        if (key.isBlank()) return@withContext false
        settings.setSecret("adKey", key)
        true
    }

    override suspend fun resolve(t: TorrentRef): String = withContext(Dispatchers.IO) {
        val key = settings.secret("adKey").ifBlank { throw DebridException("AllDebrid isn't connected.") }
        val up = get("/v4/magnet/upload", mapOf("magnets[]" to t.magnet), key)
        val magnet = up.optJSONArray("magnets")?.optJSONObject(0) ?: throw DebridException("AllDebrid didn't take this source.")
        magnet.optJSONObject("error")?.let { throw DebridException("AllDebrid: ${it.optString("message")}") }
        val id = magnet.optLong("id")
        var links: JSONArray? = null
        for (i in 0 until 8) {
            val st = get("/v4/magnet/status", mapOf("id" to id.toString()), key)
            val m = st.optJSONObject("magnets") ?: st.optJSONArray("magnets")?.optJSONObject(0)
            if (m?.optInt("statusCode") == 4) {
                links = m.optJSONArray("links")
                break
            }
            if ((m?.optInt("statusCode") ?: 0) > 4) throw DebridException("AllDebrid couldn't prepare this source.")
            delay(1_500)
        }
        val list = links ?: throw DebridException("AllDebrid doesn't have this one ready. It's downloading it now; try again later or pick another source.")
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        val chosen = pickFile(items, t, { it.optString("filename") }, { it.optLong("size") }, { items.indexOf(it) })
            ?: throw DebridException("AllDebrid found no video in this source.")
        val unlocked = get("/v4/link/unlock", mapOf("link" to chosen.optString("link")), key)
        unlocked.optString("link").takeIf { it.startsWith("http") } ?: throw DebridException("AllDebrid couldn't open this source.")
    }

    override fun signOut() = settings.setSecret("adKey", "")
}

// ---------------------------------------------------------------------------------------------- Premiumize

/** Premiumize with your API key (from premiumize.me/account), pasted on the phone page. Cached sources only. */
class Premiumize(private val http: OkHttpClient, private val settings: Settings) : DebridService {
    override val id = "pm"
    override val name = "Premiumize"
    override val connected: Boolean get() = settings.secret("pmKey").isNotBlank()

    override suspend fun resolve(t: TorrentRef): String = withContext(Dispatchers.IO) {
        val key = settings.secret("pmKey").ifBlank { throw DebridException("Premiumize isn't connected.") }
        val req = Request.Builder()
            .url("https://www.premiumize.me/api/transfer/directdl")
            .header("Authorization", "Bearer $key")
            .post(FormBody.Builder().add("src", t.magnet).build())
            .build()
        val (code, body) = http.text(req)
        if (code == 401) throw DebridException("Premiumize didn't accept your API key. Add it again from your phone.")
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw DebridException("Premiumize sent something unexpected.")
        }
        if (o.optString("status") != "success") {
            throw DebridException(
                o.optString("message").takeIf { it.isNotBlank() }?.let { "Premiumize: $it" }
                    ?: "Premiumize doesn't have this one ready. Pick another source.",
            )
        }
        val content = o.optJSONArray("content") ?: JSONArray()
        val files = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
        val chosen = pickFile(files, t, { it.optString("path") }, { it.optLong("size") }, { files.indexOf(it) })
            ?: throw DebridException("Premiumize found no video in this source.")
        chosen.optString("link").takeIf { it.startsWith("http") } ?: throw DebridException("Premiumize couldn't open this source.")
    }

    override fun signOut() = settings.setSecret("pmKey", "")
}

// ---------------------------------------------------------------------------------------------- TorBox

/** TorBox with your API key (from torbox.app/settings), pasted on the phone page. */
class TorBox(private val http: OkHttpClient, private val settings: Settings) : DebridService {
    override val id = "tb"
    override val name = "TorBox"
    override val connected: Boolean get() = settings.secret("tbKey").isNotBlank()

    private val base = "https://api.torbox.app/v1/api"

    private fun parse(code: Int, body: String): JSONObject {
        if (code == 401 || code == 403) throw DebridException("TorBox didn't accept your API key. Add it again from your phone.")
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw DebridException("TorBox sent something unexpected.")
        }
        if (!o.optBoolean("success")) {
            throw DebridException(o.optString("detail").takeIf { it.isNotBlank() }?.let { "TorBox: $it" } ?: "TorBox refused that.")
        }
        return o
    }

    override suspend fun resolve(t: TorrentRef): String = withContext(Dispatchers.IO) {
        val key = settings.secret("tbKey").ifBlank { throw DebridException("TorBox isn't connected.") }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("magnet", t.magnet).build()
        val (c1, b1) = http.text(Request.Builder().url("$base/torrents/createtorrent").header("Authorization", "Bearer $key").post(form).build())
        val torrentId = parse(c1, b1).optJSONObject("data")?.optLong("torrent_id")?.takeIf { it > 0 }
            ?: throw DebridException("TorBox didn't take this source.")

        var files: List<JSONObject> = emptyList()
        var ready = false
        for (i in 0 until 8) {
            val (c, b) = http.text(Request.Builder().url("$base/torrents/mylist?id=$torrentId&bypass_cache=true").header("Authorization", "Bearer $key").build())
            val data = parse(c, b).optJSONObject("data")
            val arr = data?.optJSONArray("files")
            files = if (arr != null) (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } else emptyList()
            ready = data?.optBoolean("download_finished") == true || data?.optBoolean("cached") == true
            if (ready && files.isNotEmpty()) break
            delay(1_500)
        }
        if (!ready) throw DebridException("TorBox doesn't have this one ready. It's downloading it now; try again later or pick another source.")
        val chosen = pickFile(files, t, { it.optString("name") }, { it.optLong("size") }, { files.indexOf(it) })
            ?: throw DebridException("TorBox found no video in this source.")
        val url = "$base/torrents/requestdl".toHttpUrl().newBuilder()
            .addQueryParameter("token", key)
            .addQueryParameter("torrent_id", torrentId.toString())
            .addQueryParameter("file_id", chosen.optLong("id").toString())
            .build()
        val (c2, b2) = http.text(Request.Builder().url(url).build())
        parse(c2, b2).optString("data").takeIf { it.startsWith("http") } ?: throw DebridException("TorBox couldn't open this source.")
    }

    override fun signOut() = settings.setSecret("tbKey", "")
}

/** All debrid services, tried in this order when a torrent source is chosen. */
class Debrid(http: OkHttpClient, settings: Settings) {
    val realDebrid = RealDebrid(http, settings)
    val allDebrid = AllDebrid(http, settings)
    val premiumize = Premiumize(http, settings)
    val torBox = TorBox(http, settings)
    val all: List<DebridService> = listOf(realDebrid, allDebrid, premiumize, torBox)

    val connected: List<DebridService> get() = all.filter { it.connected }

    /** Tries each connected service in turn; the first that returns a link wins. */
    suspend fun resolve(t: TorrentRef): Pair<DebridService, String> {
        val services = connected
        if (services.isEmpty()) throw DebridException("Connect a debrid service in Settings, Accounts and sync to play torrent sources.")
        var last: Exception? = null
        for (s in services) {
            try {
                return s to s.resolve(t)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: DebridException("Couldn't play this source.")
    }
}
