package com.zapp.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/** Small persisted preferences, readable as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("zapp", Context.MODE_PRIVATE)

    private val previewState = mutableStateOf(prefs.getBoolean("preview", true))
    var preview: Boolean
        get() = previewState.value
        set(value) {
            previewState.value = value
            prefs.edit().putBoolean("preview", value).apply()
        }

    /** Hours between automatic guide updates; 0 turns them off. Defaults from the 0.2 on/off switch. */
    private val guideHoursState = mutableIntStateOf(
        if (prefs.contains("guideHours")) prefs.getInt("guideHours", 12)
        else if (prefs.getBoolean("autoGuide", true)) 12 else 0,
    )
    var guideHours: Int
        get() = guideHoursState.intValue
        set(value) {
            guideHoursState.intValue = value
            prefs.edit().putInt("guideHours", value).apply()
        }

    /** Guide grid: show only programmes on right now (used from Stage 2). */
    private val showLiveNowOnlyState = mutableStateOf(prefs.getBoolean("guideLiveOnly", false))
    var guideLiveOnly: Boolean
        get() = showLiveNowOnlyState.value
        set(value) {
            showLiveNowOnlyState.value = value
            prefs.edit().putBoolean("guideLiveOnly", value).apply()
        }

    // ---------------------------------------------------------------- playback

    private fun stringState(key: String, default: String) = mutableStateOf(prefs.getString(key, default) ?: default)
    private fun intState(key: String, default: Int) = mutableIntStateOf(prefs.getInt(key, default))

    init {
        // 0.9.1: English became the preferred audio and subtitle language (unless one was chosen).
        if (!prefs.getBoolean("englishDefault091", false)) {
            val e = prefs.edit().putBoolean("englishDefault091", true)
            if (prefs.getString("audioLanguage", "").isNullOrEmpty()) e.putString("audioLanguage", "en")
            if (prefs.getString("subtitleLanguage", "").isNullOrEmpty()) e.putString("subtitleLanguage", "en")
            e.commit()
        }
    }

    private val audioLanguageState = stringState("audioLanguage", "en")
    /** Preferred audio language code, or "" for the TV's own language. */
    var audioLanguage: String
        get() = audioLanguageState.value
        set(value) {
            audioLanguageState.value = value
            prefs.edit().putString("audioLanguage", value).apply()
        }

    private val largeBufferState = mutableStateOf(prefs.getBoolean("largeBuffer", false))
    /** Bigger buffer for unsteady connections; takes effect the next time Zapp starts. */
    var largeBuffer: Boolean
        get() = largeBufferState.value
        set(value) {
            largeBufferState.value = value
            prefs.edit().putBoolean("largeBuffer", value).apply()
        }

    // ---------------------------------------------------------------- subtitles

    private val subtitlesOnState = mutableStateOf(prefs.getBoolean("subtitlesOn", false))
    /** Turn subtitles on automatically when a stream has them. */
    var subtitlesOn: Boolean
        get() = subtitlesOnState.value
        set(value) {
            subtitlesOnState.value = value
            prefs.edit().putBoolean("subtitlesOn", value).apply()
        }

    private val subtitleLanguageState = stringState("subtitleLanguage", "en")
    var subtitleLanguage: String
        get() = subtitleLanguageState.value
        set(value) {
            subtitleLanguageState.value = value
            prefs.edit().putString("subtitleLanguage", value).apply()
        }

    private val subtitleSizeState = intState("subtitleSize", 1)
    /** 0 small, 1 medium, 2 large, 3 extra large. */
    var subtitleSize: Int
        get() = subtitleSizeState.intValue
        set(value) {
            subtitleSizeState.intValue = value
            prefs.edit().putInt("subtitleSize", value).apply()
        }

    private val subtitleStyleState = intState("subtitleStyle", 0)
    /** 0 white with shadow, 1 white on dark box, 2 yellow with shadow. */
    var subtitleStyle: Int
        get() = subtitleStyleState.intValue
        set(value) {
            subtitleStyleState.intValue = value
            prefs.edit().putInt("subtitleStyle", value).apply()
        }

    private val subtitleRaisedState = mutableStateOf(prefs.getBoolean("subtitleRaised", false))
    var subtitleRaised: Boolean
        get() = subtitleRaisedState.value
        set(value) {
            subtitleRaisedState.value = value
            prefs.edit().putBoolean("subtitleRaised", value).apply()
        }

    // ---------------------------------------------------------------- films and series

    private val autoPlayFirstState = mutableStateOf(
        // 0.9.0 turned autoplay on for everyone once; after that it's your choice.
        if (!prefs.getBoolean("autoplayOn090", false)) {
            prefs.edit().putBoolean("autoplayOn090", true).putBoolean("autoPlayFirst", true).apply()
            true
        } else {
            prefs.getBoolean("autoPlayFirst", true)
        },
    )
    /** Start the first suitable source straight away instead of showing the list. */
    var autoPlayFirst: Boolean
        get() = autoPlayFirstState.value
        set(value) {
            autoPlayFirstState.value = value
            prefs.edit().putBoolean("autoPlayFirst", value).apply()
        }

    private val welcomeSkippedState = mutableStateOf(prefs.getBoolean("welcomeSkipped", false))
    /** Chose "Only films and series" on the welcome screen (no playlist needed). */
    var welcomeSkipped: Boolean
        get() = welcomeSkippedState.value
        set(value) {
            welcomeSkippedState.value = value
            prefs.edit().putBoolean("welcomeSkipped", value).apply()
        }

    private val themeState = mutableStateOf(prefs.getString("theme", "aurora") ?: "aurora")
    /** Colour theme id (see Palettes). Applying it recolours the whole app at once. */
    var theme: String
        get() = themeState.value
        set(value) {
            themeState.value = value
            prefs.edit().putString("theme", value).apply()
            com.zapp.tv.ui.ZappColors.palette = com.zapp.tv.ui.Palettes.byId(value)
        }

    init {
        com.zapp.tv.ui.ZappColors.palette = com.zapp.tv.ui.Palettes.byId(prefs.getString("theme", "aurora"))
    }

    private val focusRingState = mutableIntStateOf(prefs.getInt("focusRing", 1))
    /** Focus ring on cards: 0 thin, 1 medium, 2 thick. */
    var focusRing: Int
        get() = focusRingState.intValue
        set(value) {
            focusRingState.intValue = value
            prefs.edit().putInt("focusRing", value).apply()
            com.zapp.tv.ui.FocusStyle.ringWidth = ringWidthFor(value)
        }

    private val focusGlowState = mutableStateOf(prefs.getBoolean("focusGlow", true))
    /** A soft pulsing glow around the focused card. */
    var focusGlow: Boolean
        get() = focusGlowState.value
        set(value) {
            focusGlowState.value = value
            prefs.edit().putBoolean("focusGlow", value).apply()
            com.zapp.tv.ui.FocusStyle.glow = value
        }

    private fun ringWidthFor(v: Int) = when (v) { 0 -> 1.5f; 2 -> 3.5f; else -> 2.5f }

    init {
        com.zapp.tv.ui.FocusStyle.ringWidth = ringWidthFor(prefs.getInt("focusRing", 1))
        com.zapp.tv.ui.FocusStyle.glow = prefs.getBoolean("focusGlow", true)
    }

    private val cachedOnlyState = mutableStateOf(prefs.getBoolean("cachedOnly", false))
    /** Only list sources that can play straight away (ready on your debrid service or add-on). */
    var cachedOnly: Boolean
        get() = cachedOnlyState.value
        set(value) {
            cachedOnlyState.value = value
            prefs.edit().putBoolean("cachedOnly", value).apply()
        }

    private val maxQualityState = intState("maxQuality", 0)
    /** 0 any, 1 up to 1080p, 2 up to 720p. */
    var maxQuality: Int
        get() = maxQualityState.intValue
        set(value) {
            maxQualityState.intValue = value
            prefs.edit().putInt("maxQuality", value).apply()
        }

    private val bestFirstState = mutableStateOf(prefs.getBoolean("bestFirst", true))
    /** List the best quality first instead of keeping each add-on's own order. */
    var bestFirst: Boolean
        get() = bestFirstState.value
        set(value) {
            bestFirstState.value = value
            prefs.edit().putBoolean("bestFirst", value).apply()
        }

    private val autoNextEpisodeState = mutableStateOf(prefs.getBoolean("autoNextEpisode", true))
    var autoNextEpisode: Boolean
        get() = autoNextEpisodeState.value
        set(value) {
            autoNextEpisodeState.value = value
            prefs.edit().putBoolean("autoNextEpisode", value).apply()
        }

    private val homeRowsState = stringState("homeRows", "")
    /** Home screen rows as a JSON list of {"id": ..., "on": ...}, in display order. Empty means defaults. */
    var homeRowsJson: String
        get() = homeRowsState.value
        set(value) {
            homeRowsState.value = value
            prefs.edit().putString("homeRows", value).apply()
        }

    // ---------------------------------------------------------------- Simkl

    private val simklClientIdState = stringState("simklClientId", "")
    /** The Simkl app ID; from the phone page, or built in when the SIMKL_CLIENT_ID secret is set. */
    var simklClientId: String
        get() = simklClientIdState.value.ifBlank { com.zapp.tv.BuildConfig.SIMKL_CLIENT_ID }
        set(value) {
            simklClientIdState.value = value.trim()
            prefs.edit().putString("simklClientId", value.trim()).apply()
        }

    private val simklUserState = stringState("simklUser", "")
    var simklUser: String
        get() = simklUserState.value
        set(value) {
            simklUserState.value = value
            prefs.edit().putString("simklUser", value).apply()
        }

    /** Access and refresh tokens, encrypted. */
    var simklAccess: String
        get() = Secrets.decrypt(prefs.getString("simklAccess", "")).orEmpty()
        set(value) {
            prefs.edit().putString("simklAccess", Secrets.encrypt(value) ?: "").apply()
        }
    var simklRefresh: String
        get() = Secrets.decrypt(prefs.getString("simklRefresh", "")).orEmpty()
        set(value) {
            prefs.edit().putString("simklRefresh", Secrets.encrypt(value) ?: "").apply()
        }

    var simklActivities: String
        get() = prefs.getString("simklActivities", "") ?: ""
        set(value) {
            prefs.edit().putString("simklActivities", value).apply()
        }

    private val simklLastSyncState = mutableLongStateOf(prefs.getLong("simklLastSync", 0L))
    var simklLastSync: Long
        get() = simklLastSyncState.longValue
        set(value) {
            simklLastSyncState.longValue = value
            prefs.edit().putLong("simklLastSync", value).apply()
        }

    // ---------------------------------------------------------------- debrid services (all stored encrypted)

    private val debridTick = mutableIntStateOf(0)

    /** Changes whenever a debrid sign-in changes, so screens can refresh. */
    val debridVersion: Int get() = debridTick.intValue

    fun secret(key: String): String = Secrets.decrypt(prefs.getString(key, "")).orEmpty()

    fun setSecret(key: String, value: String) {
        prefs.edit().putString(key, if (value.isEmpty()) "" else Secrets.encrypt(value) ?: "").apply()
        debridTick.intValue++
    }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(value) {
            prefs.edit().putLong("lastUpdateCheck", value).apply()
        }

    private val currentPlaylistState = mutableLongStateOf(prefs.getLong("currentPlaylist", -1L))
    var currentPlaylistId: Long
        get() = currentPlaylistState.longValue
        set(value) {
            currentPlaylistState.longValue = value
            prefs.edit().putLong("currentPlaylist", value).apply()
        }
}
