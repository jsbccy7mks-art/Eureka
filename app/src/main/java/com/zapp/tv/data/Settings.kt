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
            applyTheme()
        }

    /** Custom theme: two swatch numbers (see Palettes.Swatches). */
    var customColors: Pair<Int, Int>
        get() = customState.value
        set(value) {
            customState.value = value
            prefs.edit().putInt("custom1", value.first).putInt("custom2", value.second).apply()
            applyTheme()
        }
    private val customState = mutableStateOf(prefs.getInt("custom1", 0) to prefs.getInt("custom2", 7))

    private fun applyTheme() {
        val id = prefs.getString("theme", "aurora")
        com.zapp.tv.ui.ZappColors.palette = if (id == "custom") {
            com.zapp.tv.ui.Palettes.custom(prefs.getInt("custom1", 0), prefs.getInt("custom2", 7))
        } else {
            com.zapp.tv.ui.Palettes.byId(id)
        }
    }

    init {
        applyTheme()
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

    // ---- accounts belong to the profile in use (the first profile keeps the original keys)
    private val accountTick = mutableIntStateOf(0)

    /** The storage key for an account setting of the profile in use. */
    fun acct(key: String): String = if (profileId == 1L) key else "${key}_p$profileId"
    private fun acctString(key: String): String {
        accountTick.intValue
        return prefs.getString(acct(key), "") ?: ""
    }
    private fun setAcctString(key: String, value: String) {
        prefs.edit().putString(acct(key), value).apply()
        accountTick.intValue++
    }

    /** Email of this profile's Stremio account (blank when signed out). */
    var stremioUser: String
        get() = acctString("stremioUser")
        set(value) = setAcctString("stremioUser", value)

    var stremioLastSync: Long
        get() = prefs.getLong(acct("stremioLastSync"), 0)
        set(value) = prefs.edit().putLong(acct("stremioLastSync"), value).apply()

    var recentSearches: List<String>
        get() = (prefs.getString(acct("recentSearches"), "") ?: "").split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(acct("recentSearches"), value.joinToString("\n")).apply()

    /** This profile's encrypted account secret (Stremio sign-in key and so on). */
    fun acctSecret(key: String): String {
        accountTick.intValue
        return secret(acct(key))
    }
    fun setAcctSecret(key: String, value: String) {
        setSecret(acct(key), value)
        accountTick.intValue++
    }

    private val artGlowState = mutableStateOf(prefs.getBoolean("artGlow", true))
    /** Tint the background glow with the colour of the title you're on. */
    var artGlow: Boolean
        get() = artGlowState.value
        set(value) {
            artGlowState.value = value
            prefs.edit().putBoolean("artGlow", value).apply()
            com.zapp.tv.ui.FocusStyle.artGlow = value
        }

    private val driftState = mutableStateOf(prefs.getBoolean("drift", true))
    /** Slow zoom and pan on artwork. */
    var drift: Boolean
        get() = driftState.value
        set(value) {
            driftState.value = value
            prefs.edit().putBoolean("drift", value).apply()
            com.zapp.tv.ui.FocusStyle.drift = value
        }

    private val trailerDelayState = mutableIntStateOf(prefs.getInt("trailerDelay", 4))
    /** Seconds before a trailer starts: 2, 4, 8 or 15. */
    var trailerDelay: Int
        get() = trailerDelayState.intValue
        set(value) {
            trailerDelayState.intValue = value
            prefs.edit().putInt("trailerDelay", value).apply()
            com.zapp.tv.ui.FocusStyle.trailerDelay = value
        }

    private val trailerSoundState = mutableStateOf(prefs.getBoolean("trailerSound", false))
    var trailerSound: Boolean
        get() = trailerSoundState.value
        set(value) {
            trailerSoundState.value = value
            prefs.edit().putBoolean("trailerSound", value).apply()
            com.zapp.tv.ui.FocusStyle.trailerSound = value
        }

    private val sameSourceState = mutableStateOf(prefs.getBoolean("sameSource", true))
    /** Resuming (or the next episode) tries the source you used last time first. */
    var sameSource: Boolean
        get() = sameSourceState.value
        set(value) {
            sameSourceState.value = value
            prefs.edit().putBoolean("sameSource", value).apply()
        }

    /** The last source per title, as JSON (per profile). */
    var lastSourcesJson: String
        get() = prefs.getString(acct("lastSources"), "") ?: ""
        set(value) = prefs.edit().putString(acct("lastSources"), value).apply()

    private val skipIntroState = mutableIntStateOf(prefs.getInt("skipIntro", 1))
    /** Intros: 0 leave, 1 show a Skip button, 2 skip by themselves. */
    var skipIntro: Int
        get() = skipIntroState.intValue
        set(value) {
            skipIntroState.intValue = value
            prefs.edit().putInt("skipIntro", value).apply()
        }

    private val skipRecapState = mutableIntStateOf(prefs.getInt("skipRecap", 1))
    /** Recaps: 0 leave, 1 show a Skip button, 2 skip by themselves. */
    var skipRecap: Int
        get() = skipRecapState.intValue
        set(value) {
            skipRecapState.intValue = value
            prefs.edit().putInt("skipRecap", value).apply()
        }

    private val trailersState = mutableStateOf(prefs.getBoolean("trailers", true))
    /** Muted trailers behind the header on Home and details. */
    var trailers: Boolean
        get() = trailersState.value
        set(value) {
            trailersState.value = value
            prefs.edit().putBoolean("trailers", value).apply()
            com.zapp.tv.ui.FocusStyle.trailers = value
        }

    private fun ringWidthFor(v: Int) = when (v) { 0 -> 1.5f; 2 -> 3.5f; else -> 2.5f }

    init {
        com.zapp.tv.ui.FocusStyle.ringWidth = ringWidthFor(prefs.getInt("focusRing", 1))
        com.zapp.tv.ui.FocusStyle.glow = prefs.getBoolean("focusGlow", true)
        com.zapp.tv.ui.FocusStyle.artGlow = prefs.getBoolean("artGlow", true)
        com.zapp.tv.ui.FocusStyle.trailers = prefs.getBoolean("trailers", true)
        com.zapp.tv.ui.FocusStyle.drift = prefs.getBoolean("drift", true)
        com.zapp.tv.ui.FocusStyle.trailerDelay = prefs.getInt("trailerDelay", 4)
        com.zapp.tv.ui.FocusStyle.trailerSound = prefs.getBoolean("trailerSound", false)
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

    private val profileState = mutableLongStateOf(prefs.getLong("profileId", 1L))
    /** The profile in use. Progress, library and Home rows follow it. */
    var profileId: Long
        get() = profileState.longValue
        set(value) {
            profileState.longValue = value
            prefs.edit().putLong("profileId", value).apply()
            accountTick.intValue++
            homeRowsState.value = prefs.getString(homeRowsKey(value), "") ?: ""
        }

    private fun homeRowsKey(profile: Long) = if (profile == 1L) "homeRows" else "homeRows_$profile"

    private val homeRowsState = mutableStateOf(prefs.getString(homeRowsKey(prefs.getLong("profileId", 1L)), "") ?: "")
    /** This profile's Home rows as a JSON list of {"id": ..., "on": ...}, in display order. Empty means defaults. */
    var homeRowsJson: String
        get() = homeRowsState.value
        set(value) {
            homeRowsState.value = value
            prefs.edit().putString(homeRowsKey(profileId), value).apply()
        }

    /** Ask "Who's watching?" when the app opens (when there's more than one profile). */
    var askProfileOnStart: Boolean
        get() = askProfileState.value
        set(value) {
            askProfileState.value = value
            prefs.edit().putBoolean("askProfile", value).apply()
        }
    private val askProfileState = mutableStateOf(prefs.getBoolean("askProfile", true))

    // ---------------------------------------------------------------- Simkl

    private val simklClientIdState = stringState("simklClientId", "")
    /** The Simkl app ID; from the phone page, or built in when the SIMKL_CLIENT_ID secret is set. */
    var simklClientId: String
        get() = simklClientIdState.value.ifBlank { com.zapp.tv.BuildConfig.SIMKL_CLIENT_ID }
        set(value) {
            simklClientIdState.value = value.trim()
            prefs.edit().putString("simklClientId", value.trim()).apply()
        }

    /** This profile's Simkl account. */
    var simklUser: String
        get() = acctString("simklUser")
        set(value) = setAcctString("simklUser", value)

    /** Access and refresh tokens, encrypted (per profile). */
    var simklAccess: String
        get() = acctSecret("simklAccess")
        set(value) = setAcctSecret("simklAccess", value)
    var simklRefresh: String
        get() = acctSecret("simklRefresh")
        set(value) = setAcctSecret("simklRefresh", value)

    var simklActivities: String
        get() = acctString("simklActivities")
        set(value) = setAcctString("simklActivities", value)

    var simklLastSync: Long
        get() {
            accountTick.intValue
            return prefs.getLong(acct("simklLastSync"), 0L)
        }
        set(value) {
            prefs.edit().putLong(acct("simklLastSync"), value).apply()
            accountTick.intValue++
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
