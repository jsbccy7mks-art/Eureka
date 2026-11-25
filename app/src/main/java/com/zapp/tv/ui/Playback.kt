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
    context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val resolve: (Channel) -> String,
) {

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    val player: ExoPlayer = createPlayer(context, settings.largeBuffer, dataSourceFactory)

    /** The film or episode playing, or null for live TV. */
    var media by mutableStateOf<MediaInfo?>(null)
        private set

    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set

    var channel by mutableStateOf<Channel?>(null)
        private set
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var aspect by mutableFloatStateOf(16f / 9f)
    /** Height of the picture now playing, in pixels (0 until known). */
    var videoHeight by mutableIntStateOf(0)
        private set

    /** How the picture fills the screen: 0 fit, 1 fill (crop the edges), 2 stretch. */
    var pictureMode by mutableIntStateOf(0)

    /** Louder, clearer speech for quiet films (Android's loudness enhancer). */
    var boost by mutableStateOf(false)
        private set
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    fun toggleBoost(on: Boolean) {
        boost = on
        applyBoost()
    }

    private fun applyBoost() {
        try {
            enhancer?.release()
            enhancer = null
            if (!boost) return
            val session = player.audioSessionId
            if (session == C.AUDIO_SESSION_ID_UNSET) return
            enhancer = android.media.audiofx.LoudnessEnhancer(session).apply {
                setTargetGain(900)
                enabled = true
            }
        } catch (e: Exception) {
            enhancer = null
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

    init {
        player.addListener(listener)
    }

    fun isOn(c: Channel?): Boolean = c != null && channel?.streamUrl == c.streamUrl

    fun play(c: Channel) {
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
        player.setMediaItem(MediaItem.fromUri(resolve(c)))
        player.prepare()
        player.playWhenReady = true
    }

    /** Plays a film or episode, optionally from where it was left. */
    fun playMedia(info: MediaInfo, startMs: Long, startPercent: Double? = null) {
        pendingPercent = if (startMs <= 0 && startPercent != null && startPercent > 1 && startPercent < 95) startPercent else null
        retryJob?.cancel()
        retries = 0
        error = null
        buffering = true
        channel = null
        media = info
        cues = emptyList()
        applyPreferences()
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
        player.setMediaItem(MediaItem.fromUri(resolve(c)))
        player.prepare()
        player.playWhenReady = true
    }

    /** Audio language and subtitle defaults from Settings; clears any per-stream choices. */
    fun applyPreferences() {
        val b = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(settings.audioLanguage.ifEmpty { null })
        if (settings.subtitlesOn) {
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
            if (v.frameRate > 0) rows.add("Frame rate" to String.format(Locale.UK, "%.2f fps", v.frameRate).replace(".00", ""))
            rows.add("Video" to codecName(v.sampleMimeType, v.codecs))
            if (v.bitrate > 0) rows.add("Video bitrate" to "${v.bitrate / 1000} kbps")
        }
        player.audioFormat?.let { a ->
            rows.add("Audio" to audioDetail(a))
            if (a.sampleRate > 0) rows.add("Sample rate" to "${a.sampleRate / 1000.0} kHz".replace(".0 ", " "))
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
        player.stop()
        player.clearMediaItems()
        channel = null
        media = null
        buffering = false
        error = null
    }

    fun release() {
        retryJob?.cancel()
        try {
            enhancer?.release()
        } catch (e: Exception) {
            // Already gone.
        }
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
            val loadControl = DefaultLoadControl.Builder()
                .apply {
                    if (largeBuffer) setBufferDurationsMs(30_000, 90_000, 2_500, 6_000)
                    else setBufferDurationsMs(10_000, 30_000, 1_000, 2_500)
                }
                .build()
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
