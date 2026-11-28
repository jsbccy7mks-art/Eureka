package com.zapp.tv.ui

import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.math.abs
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.zapp.tv.data.Channel
import com.zapp.tv.data.Settings
import com.zapp.tv.data.USER_AGENT
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One player for the whole app. The preview corner and fullscreen share it, so pressing OK on
 * a channel that is already previewing goes fullscreen instantly, without reconnecting.
 */
/** A film or episode being played (as opposed to a live channel). */
data class MediaInfo(
    /** The film id or "showId:season:episode". */
    val videoId: String,
    val metaId: String,
    val type: String,
    val title: String,
    val episodeLabel: String?,
    val poster: String?,
    val background: String?,
    val url: String,
    val headers: Map<String, String>,
    /** Subtitles from add-ons, added as extra tracks. */
    val subtitles: List<com.zapp.tv.addons.ExternalSubtitle> = emptyList(),
)

/** One audio or subtitle choice in the player's menus. */
data class TrackChoice(
    val label: String,
    val detail: String,
    val selected: Boolean,
    val group: Tracks.Group,
    val index: Int,
)

@OptIn(UnstableApi::class)
class Playback(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val resolve: (Channel) -> String,
) {
    private val context: Context get() = appContext

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    val player: ExoPlayer = createPlayer(context, settings.largeBuffer, dataSourceFactory)

    // ---- pause and rewind live TV (see Timeshift)
    private val timeshift = com.zapp.tv.data.Timeshift((context.applicationContext as com.zapp.tv.ZappApp).sharedHttp, context.cacheDir)
    /** Live TV is being kept, so it can be paused and rewound. */
    var timeshiftOn by mutableStateOf(false)
        private set
    /** The piece the current playback started from, and when that piece began (wall-clock). */
    private var tsFromPiece = 0
    private var tsWatch: Job? = null
    /** Channels that wouldn't play through the rolling copy: played directly from then on. */
    private val directOnly = HashSet<String>()

    /** The live channel's address: through the rolling copy when that's possible. */
    private fun liveSource(c: Channel): MediaItem {
        val url = resolve(c)
        if (settings.liveRewind && timeshift.suits(url) && !directOnly.contains(url)) {
            timeshiftOn = true
            tsFromPiece = 0
            val local = timeshift.start(url, mapOf("User-Agent" to USER_AGENT))
            // Safety net: if keeping the channel doesn't work (the provider won't send it this way),
            // play it directly as before, without rewind.
            tsWatch?.cancel()
            tsWatch = scope.launch {
                delay(9_000)
                if (channel == c && timeshiftOn && (timeshift.failed || timeshift.lastPiece < 0 || (player.playbackState == Player.STATE_IDLE && player.playerError != null))) {
                    directOnly.add(url)
                    timeshift.stop()
                    timeshiftOn = false
                    error = null
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            return MediaItem.Builder().setUri(local).setMimeType(MimeTypes.VIDEO_MP2T).build()
        }
        timeshift.stop()
        timeshiftOn = false
        return MediaItem.fromUri(url)
    }

    /** The wall-clock moment now on screen (live TV with rewind). */
    fun liveWatching(): Long = timeshift.pieceStart(tsFromPiece).takeIf { it > 0 }?.let { it + player.currentPosition } ?: System.currentTimeMillis()

    /** How far behind live the picture is, in ms (0 when live). */
    fun behindLive(): Long = if (!timeshiftOn) 0 else (System.currentTimeMillis() - liveWatching() - 4_000).coerceAtLeast(0)

    /** The oldest moment that can be rewound to (wall-clock). */
    fun liveOldest(): Long = timeshift.oldest

    /** Moves through the last five minutes; back to live when it reaches the present. */
    fun seekLive(deltaMs: Long) {
        if (!timeshiftOn) return
        val target = (liveWatching() + deltaMs).coerceAtLeast(timeshift.oldest)
        if (target >= System.currentTimeMillis() - 5_000) {
            goLive()
            return
        }
        val piece = timeshift.pieceAt(target)
        tsFromPiece = piece
        player.setMediaItem(MediaItem.Builder().setUri(timeshift.liveUrl(piece)).setMimeType(MimeTypes.VIDEO_MP2T).build())
        player.prepare()
        player.playWhenReady = true
    }

    fun goLive() {
        if (!timeshiftOn) return
        tsFromPiece = maxOf(0, timeshift.lastPiece)
        player.setMediaItem(MediaItem.Builder().setUri(timeshift.liveUrl(-1)).setMimeType(MimeTypes.VIDEO_MP2T).build())
        player.prepare()
        player.playWhenReady = true
    }

    /** The film or episode playing, or null for live TV. */
    var media by mutableStateOf<MediaInfo?>(null)
        private set

    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set

    // ---- subtitle timing (films and series)
    /** Positive: subtitles later; negative: earlier. */
    var subtitleOffsetMs by mutableStateOf(0L)
    /** What the subtitles showed, and when (for showing them later). */
    private val cueHistory = ArrayList<Pair<Long, List<Cue>>>()
    /** Add-on subtitles by their track label, so they can be read directly (for showing them earlier). */
    private var externalLabels: Map<String, String> = emptyMap()
    private val parsedSubs = java.util.concurrent.ConcurrentHashMap<String, List<SubCue>>()
    private var parsing: String? = null

    data class SubCue(val start: Long, val end: Long, val text: String)

    fun setSubtitleOffset(ms: Long) {
        subtitleOffsetMs = ms.coerceIn(-10_000, 10_000)
        media?.let { settings.setSubtitleOffset(it.videoId, subtitleOffsetMs) }
    }

    /** The add-on subtitle file in use, if one is selected. */
    private fun selectedExternalUrl(): String? {
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) g.getTrackFormat(i).label?.let { l -> externalLabels[l]?.let { return it } }
        }
        return null
    }

    /**
     * The subtitle text to show now, allowing for the timing offset (null: show the player's own).
     * Add-on subtitles are read directly, so they can move either way; subtitles inside the video
     * can be moved later.
     */
    fun subtitleTextNow(http: okhttp3.OkHttpClient, scope: CoroutineScope): List<String>? {
        val offset = subtitleOffsetMs
        if (offset == 0L) return null
        val pos = player.currentPosition - offset
        selectedExternalUrl()?.let { url ->
            val parsed = parsedSubs[url]
            if (parsed == null) {
                if (parsing != url) {
                    parsing = url
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val text = runCatching {
                            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
                        }.getOrNull()
                        if (text != null) parsedSubs[url] = parseSubs(text)
                    }
                }
            } else {
                return parsed.filter { pos >= it.start && pos < it.end }.map { it.text }
            }
        }
        if (offset > 0) {
            val entry = cueHistory.lastOrNull { it.first <= pos } ?: return emptyList()
            return entry.second.mapNotNull { c -> c.text?.toString()?.takeIf { it.isNotBlank() } }
        }
        return null
    }

    private fun parseSubs(text: String): List<SubCue> {
        val out = ArrayList<SubCue>()
        val time = Regex("""(\d{1,2}):(\d{2}):(\d{2})[.,](\d{3})|(\d{2}):(\d{2})[.,](\d{3})""")
        fun ms(m: MatchResult): Long {
            val g = m.groupValues
            return if (g[1].isNotEmpty()) g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + g[4].toLong()
            else g[5].toLong() * 60_000 + g[6].toLong() * 1000 + g[7].toLong()
        }
        for (b in text.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = b.trim().lines()
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) continue
            val times = time.findAll(lines[i]).toList()
            if (times.size < 2) continue
            val body = lines.drop(i + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").replace(Regex("\\{[^}]*\\}"), "").trim()
            if (body.isNotEmpty()) out.add(SubCue(ms(times[0]), ms(times[1]), body))
        }
        return out
    }

    var channel by mutableStateOf<Channel?>(null)
        private set
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var aspect by mutableFloatStateOf(16f / 9f)
    /** Height of the picture now playing, in pixels (0 until known). */
    var videoHeight by mutableIntStateOf(0)
    var videoWidth by mutableIntStateOf(0)
        private set

    /** How the picture fills the screen: 0 fit, 1 fill (crop the edges), 2 stretch. */
    var pictureMode by mutableIntStateOf(0)

    /**
     * Night mode: evens out the sound for late-night watching. Loud moments (explosions, music) are
     * held down and quiet ones (speech) brought up, so you can hear the dialogue without waking
     * the house. Android's dynamics processor (a compressor and a limiter) on the film's sound.
     * (It can't work while sound goes to an amplifier untouched, e.g. Dolby passthrough.)
     */
    var boost by mutableStateOf(false)
        private set
    private var night: android.media.audiofx.DynamicsProcessing? = null
    private var lift: android.media.audiofx.LoudnessEnhancer? = null

    fun toggleBoost(on: Boolean) {
        boost = on
        applyBoost()
    }

    private fun applyBoost() {
        runCatching { night?.release() }
        runCatching { lift?.release() }
        night = null
        lift = null
        if (!boost) return
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val channels = (player.audioFormat?.channelCount ?: 2).coerceIn(1, 8)
                val cfg = android.media.audiofx.DynamicsProcessing.Config.Builder(
                    android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channels,
                    false, 0, // no EQ before
                    true, 1, // one-band compressor
                    false, 0, // no EQ after
                    true, // a limiter at the end
                ).build()
                val dp = android.media.audiofx.DynamicsProcessing(0, session, cfg)
                for (ch in 0 until channels) {
                    val band = dp.getMbcBandByChannelIndex(ch, 0)
                    band.attackTime = 3f
                    band.releaseTime = 180f
                    band.ratio = 4f
                    band.threshold = -32f
                    band.kneeWidth = 6f
                    band.noiseGateThreshold = -90f
                    band.expanderRatio = 1f
                    band.preGain = 0f
                    band.postGain = 10f
                    dp.setMbcBandByChannelIndex(ch, 0, band)
                    val limiter = dp.getLimiterByChannelIndex(ch)
                    limiter.isEnabled = true
                    limiter.attackTime = 1f
                    limiter.releaseTime = 60f
                    limiter.ratio = 10f
                    limiter.threshold = -3f
                    limiter.postGain = 0f
                    dp.setLimiterByChannelIndex(ch, limiter)
                }
                dp.enabled = true
                night = dp
            } else {
                lift = android.media.audiofx.LoudnessEnhancer(session).apply {
                    setTargetGain(500)
                    enabled = true
                }
            }
        } catch (e: Exception) {
            night = null
            lift = null
        }
    }

    /** True while the fullscreen player owns the video output. */
    var fullscreen by mutableStateOf(false)

    private var retries = 0
    private var retryJob: Job? = null
    private var pendingPercent: Double? = null

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (boost) applyBoost()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) videoHeight = videoSize.height
            if (videoSize.width > 0) videoWidth = videoSize.width
            if (videoSize.height > 0 && videoSize.width > 0) {
                aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                retries = 0
                error = null
                val pct = pendingPercent
                val d = player.duration
                if (pct != null && d > 0 && d != C.TIME_UNSET) {
                    pendingPercent = null
                    player.seekTo((d * pct / 100).toLong())
                }
            }
        }

        override fun onTracksChanged(t: Tracks) {
            tracks = t
        }

        override fun onCues(cueGroup: CueGroup) {
            cues = cueGroup.cues
            if (media != null) {
                cueHistory.add(player.currentPosition to cueGroup.cues)
                while (cueHistory.size > 400) cueHistory.removeAt(0)
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

    // Counts watching time for Your month / Your year (every half minute while something plays).
    init {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (player.isPlaying && !fullscreenPreviewOnly()) {
                    runCatching { com.zapp.tv.data.WatchLog.add(appContext, if (media != null) "vod" else "live", channel?.name, 30) }
                }
            }
        }
    }

    /** A channel playing only as a small preview (Live TV corner, Home header) isn't counted as watching. */
    private fun fullscreenPreviewOnly(): Boolean = media == null && !fullscreen

    init {
        player.addListener(listener)
    }

    fun isOn(c: Channel?): Boolean = c != null && channel?.streamUrl == c.streamUrl

    /** What's actually playing, in short: "4K", "HDR10", "Dolby Atmos 5.1", "23.976 fps". */
    @OptIn(UnstableApi::class)
    fun techParts(): List<String> {
        val v = player.videoFormat
        val a = player.audioFormat
        val out = ArrayList<String>()
        val h = if (videoHeight > 0) videoHeight else v?.height ?: 0
        val w = if (videoWidth > 0) videoWidth else v?.width ?: 0
        // By width first: widescreen films have the black bars cropped off, so a 4K film is
        // 3840 wide but often only about 1600 tall (that used to read as "1080p").
        when {
            w >= 3200 || h >= 2000 -> out.add("4K")
            w >= 1800 || h >= 1000 -> out.add("1080p")
            w >= 1200 || h >= 700 -> out.add("720p")
            w > 0 || h > 0 -> out.add("SD")
        }
        if (v != null) {
            when {
                v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || v.codecs?.startsWith("dvh") == true || v.codecs?.startsWith("dva") == true -> out.add("Dolby Vision")
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> out.add("HDR10")
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> out.add("HLG")
            }
        }
        if (a != null) {
            val codec = when (a.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
                MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
                MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
                MimeTypes.AUDIO_AAC -> "AAC"
                else -> null
            }
            val ch = when (a.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                Format.NO_VALUE -> null
                else -> "${a.channelCount}ch"
            }
            listOfNotNull(codec, ch).joinToString(" ").takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        v?.frameRate?.takeIf { it > 1f }?.let { f ->
            out.add((if (abs(f - Math.round(f)) < 0.01f) "${Math.round(f)}" else String.format(java.util.Locale.UK, "%.3f", f)) + " fps")
        }
        return out
    }

    fun play(c: Channel) {
        FilmStartBuffer.film = false
        if (isOn(c) && error == null && player.playbackState != Player.STATE_IDLE) {
            channel = c
            return
        }
        retryJob?.cancel()
        retries = 0
        error = null
        buffering = true
        channel = c
        media = null
        cues = emptyList()
        applyPreferences()
        dataSourceFactory.setDefaultRequestProperties(emptyMap())
        player.setMediaItem(liveSource(c))
        player.prepare()
        player.playWhenReady = true
    }

    /** Plays a film or episode, optionally from where it was left. */
    fun playMedia(info: MediaInfo, startMs: Long, startPercent: Double? = null) {
        FilmStartBuffer.film = true
        pendingPercent = if (startMs <= 0 && startPercent != null && startPercent > 1 && startPercent < 95) startPercent else null
        retryJob?.cancel()
        retries = 0
        error = null
        buffering = true
        channel = null
        timeshift.stop()
        timeshiftOn = false
        media = info
        cues = emptyList()
        cueHistory.clear()
        subtitleOffsetMs = settings.subtitleOffset(info.videoId)
        externalLabels = info.subtitles.associate { s ->
            val lang = com.zapp.tv.addons.normalizeLang(s.lang)
            ((languageName(lang) ?: s.lang) + " · " + s.addonName) to s.url
        }
        applyPreferences(vod = true)
        dataSourceFactory.setDefaultRequestProperties(info.headers)
        val item = MediaItem.Builder()
            .setUri(info.url)
            .setSubtitleConfigurations(
                info.subtitles.map { s ->
                    val lang = com.zapp.tv.addons.normalizeLang(s.lang)
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                        .setMimeType(if (s.url.substringBefore('?').endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(lang.ifBlank { null })
                        .setLabel((languageName(lang) ?: s.lang) + " · " + s.addonName)
                        .build()
                },
            )
            .build()
        player.setMediaItem(item, startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        media?.let { m ->
            val at = player.currentPosition
            playMedia(m, at)
            return
        }
        val c = channel ?: return
        error = null
        retries = 0
        buffering = true
        player.setMediaItem(liveSource(c))
        player.prepare()
        player.playWhenReady = true
    }

    /** Audio language and subtitle defaults from Settings; clears any per-stream choices. */
    fun applyPreferences(vod: Boolean = false) {
        val b = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(settings.audioLanguage.ifEmpty { null })
        // Films and series have their own on/off, separate from live TV.
        if (if (vod) settings.vodSubtitlesOn else settings.subtitlesOn) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(settings.subtitleLanguage.ifEmpty { Locale.getDefault().language })
                .setSelectUndeterminedTextLanguage(true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        player.trackSelectionParameters = b.build()
    }

    fun choices(type: Int): List<TrackChoice> {
        val list = ArrayList<TrackChoice>()
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                list.add(
                    TrackChoice(
                        label = f.label?.takeIf { it.isNotBlank() } ?: languageName(f.language) ?: "Track ${list.size + 1}",
                        detail = if (type == C.TRACK_TYPE_AUDIO) audioDetail(f) else textDetail(f),
                        selected = group.isTrackSelected(i),
                        group = group,
                        index = i,
                    ),
                )
            }
        }
        // Your preferred language (English unless you changed it) comes first in the menus.
        val preferred = (if (type == C.TRACK_TYPE_AUDIO) settings.audioLanguage else settings.subtitleLanguage).ifEmpty { "en" }
        return list.sortedBy { c ->
            val lang = c.group.getTrackFormat(c.index).language?.lowercase().orEmpty()
            if (lang == preferred || lang.startsWith("$preferred-") || (preferred == "en" && lang == "eng")) 0 else 1
        }
    }

    val subtitlesShowing: Boolean
        get() = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }

    fun select(choice: TrackChoice) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(choice.group.type, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
            .build()
    }

    fun subtitlesOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        cues = emptyList()
    }

    /** Technical details for the stream info panel. */
    fun details(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        player.videoFormat?.let { v ->
            if (v.width > 0 && v.height > 0) rows.add("Resolution" to "${v.width} × ${v.height}")
            val fps = DisplayMatch.videoFps ?: v.frameRate.takeIf { it > 0 }
            if (fps != null) rows.add("Frame rate" to String.format(Locale.UK, "%.3f", fps).trimEnd('0').trimEnd('.') + " fps" + if (DisplayMatch.videoFpsMeasured) " (measured)" else "")
            rows.add("Video" to codecName(v.sampleMimeType, v.codecs))
            if (v.bitrate > 0) rows.add("Video bitrate" to "${v.bitrate / 1000} kbps")
        }
        player.audioFormat?.let { a ->
            rows.add("Audio" to audioDetail(a))
            if (a.sampleRate > 0) rows.add("Sample rate" to "${a.sampleRate / 1000.0} kHz".replace(".0 ", " "))
        }
        // What the TV itself is showing (to check frame rate matching).
        runCatching {
            (appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        }.getOrNull()?.let { m ->
            rows.add("TV" to "${m.physicalWidth} × ${m.physicalHeight} at " + String.format(Locale.UK, "%.3f", m.refreshRate).trimEnd('0').trimEnd('.') + " Hz")
        }
        val buffered = player.totalBufferedDuration
        if (buffered > 0) rows.add("Buffered" to String.format(Locale.UK, "%.1f s", buffered / 1000.0))
        player.videoDecoderCounters?.let { rows.add("Dropped frames" to it.droppedBufferCount.toString()) }
        if (rows.isEmpty()) rows.add("Status" to "Waiting for the stream")
        return rows
    }

    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun stop() {
        retryJob?.cancel()
        timeshift.stop()
        timeshiftOn = false
        player.stop()
        player.clearMediaItems()
        channel = null
        media = null
        buffering = false
        error = null
    }

    fun release() {
        retryJob?.cancel()
        runCatching { night?.release() }
        runCatching { lift?.release() }
        timeshift.stop()
        player.removeListener(listener)
        player.release()
    }

    companion object {
        fun languageName(code: String?): String? {
            val c = code?.trim()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
            val name = Locale.forLanguageTag(c).getDisplayLanguage(Locale.UK)
            return name.takeIf { it.isNotBlank() && it != c } ?: c.uppercase()
        }

        private fun codecName(mime: String?, codecs: String?): String = when (mime) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC (H.265)"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio"
            MimeTypes.AUDIO_OPUS -> "Opus"
            else -> codecs?.substringBefore('.')?.uppercase() ?: mime?.substringAfter('/')?.uppercase() ?: "Unknown"
        }

        private fun audioDetail(f: Format): String {
            val channels = when (f.channelCount) {
                1 -> "mono"
                2 -> "stereo"
                6 -> "5.1"
                8 -> "7.1"
                Format.NO_VALUE -> null
                else -> "${f.channelCount} channels"
            }
            return listOfNotNull(codecName(f.sampleMimeType, f.codecs), channels).joinToString(", ")
        }

        private fun textDetail(f: Format): String = when (f.sampleMimeType) {
            MimeTypes.APPLICATION_DVBSUBS -> "DVB subtitles"
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
            MimeTypes.TEXT_VTT -> "WebVTT"
            MimeTypes.APPLICATION_SUBRIP -> "SubRip"
            MimeTypes.APPLICATION_TTML -> "TTML"
            else -> "Subtitles"
        } + if ((f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) ", forced" else ""

        @OptIn(UnstableApi::class)
        private fun createPlayer(context: Context, largeBuffer: Boolean, dataSourceFactory: DefaultHttpDataSource.Factory): ExoPlayer {
            // Small start-up buffer so channels open quickly when zapping; a larger one for unsteady providers.
            val loadControl = if (largeBuffer) FilmStartBuffer(30_000, 90_000, 2_500, 6_000) else FilmStartBuffer(10_000, 30_000, 1_000, 2_500)
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


/**
 * Films and episodes wait for a little more video before they start (and after a stall) than live
 * channels do, so they don't stop to buffer straight after starting. Channels keep the quick start.
 * A DefaultLoadControl itself (not a wrapper), so everything else it does stays exactly as it was.
 */
@OptIn(UnstableApi::class)
private class FilmStartBuffer(minMs: Int, maxMs: Int, startMs: Int, rebufferMs: Int) : DefaultLoadControl(
    androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minMs,
    maxMs,
    startMs,
    rebufferMs,
    DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES,
    DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
    DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
    DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {
    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
        if (!super.shouldStartPlayback(parameters)) return false
        if (!film) return true
        val need = if (parameters.rebuffering) REBUFFER_US else START_US
        return parameters.bufferedDurationUs >= need
    }

    companion object {
        @Volatile var film = false
        private const val START_US = 3_000_000L
        private const val REBUFFER_US = 5_000_000L
    }
}
