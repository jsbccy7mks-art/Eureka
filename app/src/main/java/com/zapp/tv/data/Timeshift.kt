package com.zapp.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Pause and rewind live TV (up to [KEEP_MS], five minutes).
 *
 * Zapp opens the channel once, as usual, and keeps a rolling copy of it on the box in short
 * pieces (about two seconds each). The player watches the channel through Zapp itself (a small
 * server on this TV only), reading those pieces in order: right at the newest one normally, so it's
 * live, or from further back after a rewind. Pausing simply stops reading while the copy carries on.
 *
 * Pieces older than five minutes are deleted as new ones arrive; everything is deleted when you
 * change channel or leave the player. Only one channel is kept at a time.
 * Only plain streams (MPEG-TS, as most IPTV channels are) are kept; others play directly.
 */
class Timeshift(private val http: OkHttpClient, cacheRoot: File) {
    companion object {
        const val KEEP_MS = 5 * 60_000L
        private const val PIECE_MS = 2_000L
        private const val PACKET = 188
    }

    private val dir = File(cacheRoot, "timeshift").apply {
        deleteRecursively() // leftovers from an unexpected exit
        mkdirs()
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    /** Changes each time a channel starts, so old readers stop. */
    @Volatile private var session = 0

    /** When each piece started (wall-clock ms), and which pieces are finished. */
    private val starts = ConcurrentHashMap<Int, Long>()
    private val done = ConcurrentHashMap.newKeySet<Int>()
    @Volatile var firstPiece = 0
        private set
    @Volatile var lastPiece = -1
        private set
    @Volatile var running = false
        private set
    @Volatile var failed = false
        private set

    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0

    private fun file(i: Int) = File(dir, "p$i.ts")

    /** Whether this stream can be kept (plain TS; not HLS/DASH playlists). */
    fun suits(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return !(path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd") || path.contains("/hls/"))
    }

    /** Starts keeping [url]; returns the local address to play from (the live edge). */
    fun start(url: String, headers: Map<String, String> = emptyMap()): String {
        stop()
        ensureServer()
        val s = ++session
        running = true
        failed = false
        firstPiece = 0
        lastPiece = -1
        starts.clear()
        done.clear()
        job = scope.launch { record(s, url, headers) }
        return liveUrl(-1)
    }

    /** Local address that reads from piece [from] onwards (-1: from the newest). */
    fun liveUrl(from: Int): String = "http://127.0.0.1:$port/live.ts?s=$session&from=$from"

    fun stop() {
        session++
        running = false
        job?.cancel()
        job = null
        dir.listFiles()?.forEach { it.delete() }
        starts.clear()
        done.clear()
        lastPiece = -1
    }

    // ------------------------------------------------------------------ timeline

    /** Ms since the oldest kept moment, for where piece [i] begins. */
    fun pieceStart(i: Int): Long = starts[i] ?: 0L

    /** The oldest and newest moments kept (wall-clock ms). */
    val oldest: Long get() = starts[firstPiece] ?: System.currentTimeMillis()

    /** The piece that contains wall-clock moment [t]. */
    fun pieceAt(t: Long): Int {
        var best = firstPiece
        for (i in firstPiece..lastPiece) {
            val st = starts[i] ?: continue
            if (st <= t) best = i else break
        }
        return best
    }

    // ------------------------------------------------------------------ recording

    private suspend fun record(s: Int, url: String, headers: Map<String, String>) {
        var piece = 0
        var attempts = 0
        while (session == s && scope.isActive) {
            try {
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                http.newBuilder().readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val input = r.body?.byteStream() ?: throw java.io.IOException("No data")
                    attempts = 0
                    val buf = ByteArray(64 * 1024)
                    var carry = ByteArray(0)
                    var out = java.io.FileOutputStream(file(piece))
                    starts[piece] = System.currentTimeMillis()
                    lastPiece = piece
                    var pieceBegan = System.currentTimeMillis()
                    try {
                        while (session == s) {
                            val n = input.read(buf)
                            if (n < 0) break
                            // Pieces are cut on whole TS packets.
                            val data = if (carry.isEmpty()) buf.copyOf(n) else carry + buf.copyOf(n)
                            val whole = data.size - data.size % PACKET
                            out.write(data, 0, whole)
                            carry = data.copyOfRange(whole, data.size)
                            val now = System.currentTimeMillis()
                            if (now - pieceBegan >= PIECE_MS) {
                                out.close()
                                done.add(piece)
                                piece++
                                out = java.io.FileOutputStream(file(piece))
                                starts[piece] = now
                                lastPiece = piece
                                pieceBegan = now
                                trim(now)
                            }
                        }
                    } finally {
                        out.close()
                        done.add(piece)
                        piece++
                    }
                }
            } catch (e: Exception) {
                if (session != s) return
                attempts++
                if (attempts > 5) {
                    failed = true
                    running = false
                    return
                }
                delay(1_000L * attempts)
            }
        }
    }

    /** Deletes pieces older than five minutes. */
    private fun trim(now: Long) {
        while (firstPiece < lastPiece) {
            val st = starts[firstPiece + 1] ?: break
            if (now - st <= KEEP_MS) break
            file(firstPiece).delete()
            starts.remove(firstPiece)
            done.remove(firstPiece)
            firstPiece++
        }
    }

    // ------------------------------------------------------------------ the local server

    private fun ensureServer() {
        if (server != null) return
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread(name = "zapp-timeshift", isDaemon = true) {
            while (!ss.isClosed) {
                val c = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(c) }
            }
        }
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            try {
                val reader = sock.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return
                // Read and ignore the rest of the request headers.
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val path = line.split(' ').getOrNull(1) ?: return
                val q = path.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val s = q["s"]?.toIntOrNull() ?: return
                val out = sock.getOutputStream()
                if (s != session || !path.startsWith("/live.ts")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                // Wait for the first piece.
                var waited = 0
                while (lastPiece < 0 && s == session && waited < 15_000) {
                    Thread.sleep(50)
                    waited += 50
                }
                val from = q["from"]?.toIntOrNull() ?: -1
                var i = if (from < 0) maxOf(firstPiece, lastPiece) else from.coerceIn(firstPiece, maxOf(firstPiece, lastPiece))
                var offset = 0L
                val buf = ByteArray(64 * 1024)
                while (s == session) {
                    if (i < firstPiece) {
                        i = firstPiece
                        offset = 0
                    }
                    val f = file(i)
                    if (!f.exists()) {
                        Thread.sleep(50)
                        continue
                    }
                    RandomAccessFile(f, "r").use { raf ->
                        raf.seek(offset)
                        val n = raf.read(buf)
                        if (n > 0) {
                            out.write(buf, 0, n)
                            offset += n
                        } else if (i in done && raf.length() <= offset) {
                            i++
                            offset = 0
                        } else {
                            Thread.sleep(40)
                        }
                    }
                }
            } catch (e: Exception) {
                // The player closed the connection (new position, channel change): nothing to do.
            }
        }
    }
}
