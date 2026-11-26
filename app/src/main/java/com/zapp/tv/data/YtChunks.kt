package com.zapp.tv.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Reads YouTube's video and audio files in pieces of a few megabytes, each asked for by its byte
 * range in the link. YouTube slows right down (or cuts off) one long download of a whole file, but
 * serves pieces asked for this way at full speed. Anything else is read normally.
 */
@UnstableApi
class YtChunksFactory(private val userAgent: String) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YtChunks(upstream)
    }
}

@UnstableApi
private class YtChunks(private val upstream: DataSource) : DataSource {
    private companion object {
        const val PIECE = 4L * 1024 * 1024
    }

    private var spec: DataSpec? = null
    private var pieced = false
    private var total = C.LENGTH_UNSET.toLong()
    /** Where the reader is in the file, and where the request it made ends. */
    private var position = 0L
    private var stopAt = 0L
    private var pieceEnd = -1L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val uri = dataSpec.uri
        val length = uri.getQueryParameter("clen")?.toLongOrNull()
        pieced = uri.host?.endsWith("googlevideo.com") == true && length != null && uri.getQueryParameter("range") == null
        if (!pieced) return upstream.open(dataSpec)
        total = length!!
        position = dataSpec.position
        stopAt = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(total, position + dataSpec.length) else total
        openPiece()
        return stopAt - dataSpec.position
    }

    private fun openPiece() {
        val end = minOf(stopAt, position + PIECE) - 1
        pieceEnd = end
        val uri = spec!!.uri.buildUpon().appendQueryParameter("range", "$position-$end").build()
        upstream.open(DataSpec.Builder().setUri(uri).setHttpRequestHeaders(spec!!.httpRequestHeaders).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!pieced) return upstream.read(buffer, offset, length)
        if (position >= stopAt) return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            // This piece is done: on to the next.
            upstream.close()
            if (position >= stopAt) return C.RESULT_END_OF_INPUT
            openPiece()
            n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        position += n
        return n
    }

    override fun getUri(): Uri? = if (pieced) spec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
