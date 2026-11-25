package com.zapp.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny web server on the TV. The phone opens it (via the QR code) and sends playlist details,
 * so nothing has to be typed with the remote. Only answers requests carrying the code shown on screen.
 */
class SetupServer(private val context: Context, private val handler: Handler) {

    interface Handler {
        suspend fun playlistsForPhone(): JSONArray
        suspend fun addFromPhone(body: JSONObject): String
        fun crashReport(): String?
    }

    val token: String = (1..6).map { ALPHABET.random() }.joinToString("")

    var port by mutableIntStateOf(0)
        private set

    private var server: ServerSocket? = null
    private var job: Job? = null
    private var users = 0
    private val page: ByteArray by lazy {
        context.assets.open("setup.html").use { it.readBytes() }
    }

    fun start(scope: CoroutineScope) {
        users++
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val socket = open() ?: return@launch
            server = socket
            withContext(Dispatchers.Main) { port = socket.localPort }
            try {
                while (isActive) {
                    val client = socket.accept()
                    launch { handle(client) }
                }
            } catch (e: IOException) {
                // Socket closed by stop().
            } finally {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // Already closed.
                }
                withContext(NonCancellable + Dispatchers.Main) { port = 0 }
            }
        }
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        job?.cancel()
        try {
            server?.close()
        } catch (e: IOException) {
            // Already closed.
        }
        server = null
    }

    fun address(): String? {
        val ip = localIpv4() ?: return null
        val p = port
        if (p == 0) return null
        return "$ip:$p"
    }

    private fun open(): ServerSocket? {
        for (candidate in 8686..8696) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(candidate))
                return s
            } catch (e: IOException) {
                // Port taken, try the next one.
            }
        }
        return null
    }

    private suspend fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 20_000
                val input = BufferedInputStream(socket.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.first().split(" ")
                if (requestLine.size < 2) return
                val method = requestLine[0].uppercase()
                val target = requestLine[1]
                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val out = socket.getOutputStream()
                if (length > 64_000) {
                    respondJson(out, 413, error("Request too large."))
                    return
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }

                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))

                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        respond(out, 200, "text/html; charset=utf-8", page)

                    path == "/crash" -> {
                        if (query["t"]?.trim()?.uppercase() != token) {
                            respond(out, 403, "text/plain; charset=utf-8", "Scan the code on your TV again.".toByteArray())
                            return
                        }
                        respond(out, 200, "text/html; charset=utf-8", crashPage(handler.crashReport()).toByteArray(Charsets.UTF_8))
                    }

                    path == "/api/playlists" -> {
                        if (query["t"]?.trim()?.uppercase() != token) {
                            respondJson(out, 403, error("That code doesn't match. Check the code on your TV."))
                            return
                        }
                        when (method) {
                            "GET" -> respondJson(
                                out,
                                200,
                                JSONObject().put("ok", true).put("playlists", handler.playlistsForPhone()),
                            )

                            "POST" -> {
                                val json = try {
                                    JSONObject(String(body, Charsets.UTF_8))
                                } catch (e: Exception) {
                                    respondJson(out, 400, error("The request was not readable."))
                                    return
                                }
                                try {
                                    val message = handler.addFromPhone(json)
                                    respondJson(out, 200, JSONObject().put("ok", true).put("message", message))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    respondJson(out, 400, error(e.message ?: "The playlist could not be added."))
                                }
                            }

                            else -> respondJson(out, 405, error("Method not allowed."))
                        }
                    }

                    else -> respond(out, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                }
            }
        } catch (e: IOException) {
            // Phone went away mid-request; nothing to do.
        }
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    private fun crashPage(report: String?): String {
        val body = (report ?: "No crash report saved. Zapp hasn't closed unexpectedly since the report was last cleared.")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Zapp crash report</title>
<style>body{margin:0;padding:18px;background:#0A0E1A;color:#EDF1FA;font:15px -apple-system,Segoe UI,Roboto,sans-serif}
h1{font-size:20px}p{color:#94A0BD}pre{white-space:pre-wrap;word-break:break-word;background:#121A2C;padding:12px;border-radius:12px;font-size:12px}
button{width:100%;padding:14px;border:0;border-radius:12px;font:inherit;font-weight:700;color:#fff;background:linear-gradient(135deg,#7A5CFF,#2FD3F0)}</style>
</head><body><h1>Zapp crash report</h1><p>Copy this and paste it into your chat about the app. Logins have been removed.</p>
<button onclick="navigator.clipboard.writeText(document.getElementById('r').innerText).then(()=>{this.textContent='Copied'})">Copy report</button>
<pre id="r">$body</pre></body></html>"""
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buffer.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) return buffer.toString("UTF-8").trimEnd()
        }
        return null
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            try {
                map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (e: Exception) {
                // Ignore malformed parameters.
            }
        }
        return map
    }

    private fun respondJson(out: OutputStream, code: Int, json: JSONObject) =
        respond(out, code, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun localIpv4(): String? = try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            val addresses = interfaces.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
            (addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull())?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
