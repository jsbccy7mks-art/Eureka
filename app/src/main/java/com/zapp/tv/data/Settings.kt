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

    // ---- subtitles for films and series (their own, separate from live TV)
    private val vodSubtitlesOnState = mutableStateOf(prefs.getBoolean("vodSubtitlesOn", true))
    /** Films and series: subtitles on when there are some in your language. */
    var vodSubtitlesOn: Boolean
        get() = vodSubtitlesOnState.value
        set(value) {
            vodSubtitlesOnState.value = value
            prefs.edit().putBoolean("vodSubtitlesOn", value).apply()
        }
    private val vodSubFontState = intState("vodSubFont", 0)
    /** 0 Default, 1 Sans, 2 Serif, 3 Condensed, 4 Typewriter. */
    var vodSubFont: Int
        get() = vodSubFontState.intValue
        set(value) { vodSubFontState.intValue = value; prefs.edit().putInt("vodSubFont", value).apply() }
    private val vodSubBoldState = mutableStateOf(prefs.getBoolean("vodSubBold", false))
    var vodSubBold: Boolean
        get() = vodSubBoldState.value
        set(value) { vodSubBoldState.value = value; prefs.edit().putBoolean("vodSubBold", value).apply() }
    private val vodSubStyleState = intState("vodSubStyle", 0)
    /** 0 drop shadow, 1 outline, 2 outline and shadow, 3 dark box, 4 plain. */
    var vodSubStyle: Int
        get() = vodSubStyleState.intValue
        set(value) { vodSubStyleState.intValue = value; prefs.edit().putInt("vodSubStyle", value).apply() }
    private val vodSubSizeState = intState("vodSubSize", prefs.getInt("subtitleSize", 1))
    /** 0 small, 1 medium, 2 large, 3 extra large. */
    var vodSubSize: Int
        get() = vodSubSizeState.intValue
        set(value) { vodSubSizeState.intValue = value; prefs.edit().putInt("vodSubSize", value).apply() }
    private val vodSubRaisedState = mutableStateOf(prefs.getBoolean("vodSubRaised", false))
    var vodSubRaised: Boolean
        get() = vodSubRaisedState.value
        set(value) { vodSubRaisedState.value = value; prefs.edit().putBoolean("vodSubRaised", value).apply() }

    /** Subtitle timing per film or episode, in ms (positive: later). */
    fun subtitleOffset(videoId: String): Long =
        runCatching { org.json.JSONObject(prefs.getString("subOffsets", "{}") ?: "{}").optLong(videoId, 0) }.getOrDefault(0)
    fun setSubtitleOffset(videoId: String, ms: Long) {
        val o = runCatching { org.json.JSONObject(prefs.getString("subOffsets", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        if (ms == 0L) o.remove(videoId) else o.put(videoId, ms)
        // Keep the last 200 titles.
        while (o.length() > 200) o.remove(o.keys().next())
        prefs.edit().putString("subOffsets", o.toString()).apply()
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

    /** Library: "all", "movie" or "series", and the sort ("added", "title", "year", "watched"). */
    var libraryTab: String
        get() = prefs.getString(acct("libraryTab"), "all") ?: "all"
        set(value) = prefs.edit().putString(acct("libraryTab"), value).apply()
    var librarySort: String
        get() = prefs.getString(acct("librarySort"), "added") ?: "added"
        set(value) = prefs.edit().putString(acct("librarySort"), value).apply()

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

    // ---- account settings of a given profile (for work that must not follow a profile switch midway)
    private fun acctFor(profile: Long, key: String): String = if (profile == 1L) key else "${key}_p$profile"
    fun acctStrFor(profile: Long, key: String): String {
        accountTick.intValue
        return prefs.getString(acctFor(profile, key), "") ?: ""
    }
    fun setAcctStrFor(profile: Long, key: String, value: String) {
        prefs.edit().putString(acctFor(profile, key), value).apply()
        accountTick.intValue++
    }
    fun acctLongFor(profile: Long, key: String): Long {
        accountTick.intValue
        return prefs.getLong(acctFor(profile, key), 0)
    }
    fun setAcctLongFor(profile: Long, key: String, value: Long) {
        prefs.edit().putLong(acctFor(profile, key), value).apply()
        accountTick.intValue++
    }
    fun acctSecretFor(profile: Long, key: String): String {
        accountTick.intValue
        return secret(acctFor(profile, key))
    }
    fun setAcctSecretFor(profile: Long, key: String, value: String) {
        setSecret(acctFor(profile, key), value)
        accountTick.intValue++
    }

    /** A random id for this install, sent with Nuvio sync changes so they can be told apart from other devices'. */
    val installId: String
        get() = prefs.getString("installId", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("installId", it).apply()
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

    private val kidsLimitTick = mutableIntStateOf(0)

    /** A kids profile's age limit: 0 up to U, 1 up to PG, 2 up to 12. */
    fun kidsLimit(profileId: Long): Int {
        kidsLimitTick.intValue
        return prefs.getInt("kidsLimit:$profileId", 1)
    }

    fun setKidsLimit(profileId: Long, value: Int) {
        prefs.edit().putInt("kidsLimit:$profileId", value.coerceIn(0, 2)).apply()
        kidsLimitTick.intValue++
    }

    /** Discover: films (0), series (1) or both (2). */
    var discoverKind: Int
        get() = prefs.getInt("discoverKind", 0)
        set(value) = prefs.edit().putInt("discoverKind", value).apply()

    private val seekrOnState = mutableStateOf(prefs.getBoolean("seekrOn", true))

    /** Seek-bar pictures from Seekr (when there's a key). */
    var seekrOn: Boolean
        get() = seekrOnState.value
        set(value) { seekrOnState.value = value; prefs.edit().putBoolean("seekrOn", value).apply() }

    private val trailerQualityState = mutableIntStateOf(prefs.getInt("trailerQuality", if (prefs.getBoolean("trailerBest", true)) 2 else 0))
    /** Trailer picture: 0 up to 1080p, 1 up to 1440p, 2 up to 4K (each only where this TV can play it). */
    var trailerQuality: Int
        get() = trailerQualityState.intValue
        set(value) {
            trailerQualityState.intValue = value
            prefs.edit().putInt("trailerQuality", value).putBoolean("trailerBest", value > 0).apply()
            com.zapp.tv.ui.FocusStyle.trailerQuality = value
            com.zapp.tv.ui.FocusStyle.trailerBest = value > 0
        }

    private val trailerBestState = mutableStateOf(prefs.getBoolean("trailerBest", true))
    /** Trailers in the best quality YouTube has (up to 4K); off lets YouTube choose for the screen. */
    var trailerBest: Boolean
        get() = trailerBestState.value
        set(value) {
            trailerBestState.value = value
            prefs.edit().putBoolean("trailerBest", value).apply()
            com.zapp.tv.ui.FocusStyle.trailerBest = value
        }

    private val prepareLinksState = mutableStateOf(prefs.getBoolean("prepareLinks", true))
    /** From a details page, get the autoplay source's debrid link ready in advance. */
    var prepareLinks: Boolean
        get() = prepareLinksState.value
        set(value) {
            prepareLinksState.value = value
            prefs.edit().putBoolean("prepareLinks", value).apply()
        }

    private val libraryCollectionsState = mutableStateOf(prefs.getBoolean("libraryCollections", true))
    /** Library shows a Collections tab (franchises of films you've saved or watched). */
    var libraryCollections: Boolean
        get() = libraryCollectionsState.value
        set(value) { libraryCollectionsState.value = value; prefs.edit().putBoolean("libraryCollections", value).apply() }

    private val themeMusicState = mutableStateOf(prefs.getBoolean("themeMusic", true))
    /** Collections play their theme tune quietly. */
    var themeMusic: Boolean
        get() = themeMusicState.value
        set(value) { themeMusicState.value = value; prefs.edit().putBoolean("themeMusic", value).apply() }
    private val themeVolumeState = mutableIntStateOf(prefs.getInt("themeVolume", 30))
    /** 10 to 60 (percent). */
    var themeVolume: Int
        get() = themeVolumeState.intValue
        set(value) { themeVolumeState.intValue = value; prefs.edit().putInt("themeVolume", value).apply() }

    /** The version that last ran here (to show "What's new" once after an update). */
    var lastVersionSeen: String
        get() = prefs.getString("lastVersionSeen", "") ?: ""
        set(value) { prefs.edit().putString("lastVersionSeen", value).apply() }

    /** Theme tunes you chose for collections (JSON: id → title, artist, url, source). */
    var themeChoices: String
        get() = prefs.getString("themeChoices", "") ?: ""
        set(value) { prefs.edit().putString("themeChoices", value).apply() }
    /** Collections page: shelf order and which are hidden (JSON), pinned and removed collections. */
    var collectionLayout: String
        get() = prefs.getString(acct("collectionLayout"), "") ?: ""
        set(value) { prefs.edit().putString(acct("collectionLayout"), value).apply() }
    var pinnedCollections: String
        get() = prefs.getString(acct("pinnedCollections"), "") ?: ""
        set(value) { prefs.edit().putString(acct("pinnedCollections"), value).apply() }
    var removedCollections: String
        get() = prefs.getString(acct("removedCollections"), "") ?: ""
        set(value) { prefs.edit().putString(acct("removedCollections"), value).apply() }

    private val showFpsState = mutableStateOf(prefs.getBoolean("showFps", false))
    /** A small frames-per-second counter at the top of the screen. */
    var showFps: Boolean
        get() = showFpsState.value
        set(value) { showFpsState.value = value; prefs.edit().putBoolean("showFps", value).apply() }

    /** New settings you've already looked at (titles, one per line). */
    var seenSettings: String
        get() = prefs.getString("seenSettings", "") ?: ""
        set(value) { prefs.edit().putString("seenSettings", value).apply() }

    /** The catalogue of collections found on TMDB (JSON, shared by all profiles), and when it was made. */
    var collectionCatalogue: String
        get() = prefs.getString("collectionCatalogue", "") ?: ""
        set(value) { prefs.edit().putString("collectionCatalogue", value).apply() }
    var collectionCatalogueAt: Long
        get() = prefs.getLong("collectionCatalogueAt", 0L)
        set(value) { prefs.edit().putLong("collectionCatalogueAt", value).apply() }

    /** Your Dislike / Like / Love ratings, this profile (JSON). */
    var ratingsJson: String
        get() = prefs.getString(acct("ratings"), "") ?: ""
        set(value) { prefs.edit().putString(acct("ratings"), value).apply() }

    /** Teams this profile follows (JSON). */
    var sportsTeamsJson: String
        get() = prefs.getString(acct("sportsTeams"), "") ?: ""
        set(value) { prefs.edit().putString(acct("sportsTeams"), value).apply(); accountTick.intValue++ }

    private val postPlaySuggestState = mutableStateOf(prefs.getBoolean("postPlaySuggest", true))
    /** When a film (or the latest episode) ends: shrink it and suggest something to watch next. */
    var postPlaySuggest: Boolean
        get() = postPlaySuggestState.value
        set(value) { postPlaySuggestState.value = value; prefs.edit().putBoolean("postPlaySuggest", value).apply() }

    private val liveRewindState = mutableStateOf(prefs.getBoolean("liveRewind", true))
    /** Keep the last five minutes of the channel you're watching, to pause and rewind. */
    var liveRewind: Boolean
        get() = liveRewindState.value
        set(value) { liveRewindState.value = value; prefs.edit().putBoolean("liveRewind", value).apply() }

    private val frameRateModeState = mutableIntStateOf(prefs.getInt("frameRateMode", if (prefs.getBoolean("matchFrameRate", true)) 2 else 0))
    /** Frame rate matching: 0 off, 1 at the start, 2 at the start and back when you stop. */
    var frameRateMode: Int
        get() = frameRateModeState.intValue
        set(value) {
            frameRateModeState.intValue = value
            prefs.edit().putInt("frameRateMode", value).putBoolean("matchFrameRate", value != 0).apply()
        }
    /** Switch the TV to the video's frame rate (no judder). */
    var matchFrameRate: Boolean
        get() = frameRateMode != 0
        set(value) { frameRateMode = if (value) 2 else 0 }

    private val matchResolutionState = mutableStateOf(prefs.getBoolean("matchResolution", false))
    /** Switch the TV to the video's resolution while playing. */
    var matchResolution: Boolean
        get() = matchResolutionState.value
        set(value) {
            matchResolutionState.value = value
            prefs.edit().putBoolean("matchResolution", value).apply()
        }

    private val startupSoundState = mutableStateOf(prefs.getBoolean("startupSound", true))
    /** A short chime when Zapp opens. */
    var startupSound: Boolean
        get() = startupSoundState.value
        set(value) {
            startupSoundState.value = value
            prefs.edit().putBoolean("startupSound", value).apply()
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

    // ---- the look (Appearance)
    private val trueBlackState = mutableStateOf(prefs.getBoolean("trueBlack", false))
    var trueBlack: Boolean
        get() = trueBlackState.value
        set(value) { trueBlackState.value = value; prefs.edit().putBoolean("trueBlack", value).apply(); com.zapp.tv.ui.FocusStyle.trueBlack = value }
    private val titleFontState = mutableIntStateOf(prefs.getInt("titleFont", 0))
    var titleFont: Int
        get() = titleFontState.intValue
        set(value) { titleFontState.intValue = value; prefs.edit().putInt("titleFont", value).apply(); com.zapp.tv.ui.FocusStyle.titleFont = value }
    private val cardStyleState = mutableIntStateOf(prefs.getInt("cardStyle", 0))
    var cardStyle: Int
        get() = cardStyleState.intValue
        set(value) { cardStyleState.intValue = value; prefs.edit().putInt("cardStyle", value).apply(); com.zapp.tv.ui.FocusStyle.cardStyle = value }
    private val cardTitlesState = mutableStateOf(prefs.getBoolean("cardTitles", false))
    var cardTitles: Boolean
        get() = cardTitlesState.value
        set(value) { cardTitlesState.value = value; prefs.edit().putBoolean("cardTitles", value).apply(); com.zapp.tv.ui.FocusStyle.cardTitles = value }
    private val glossState = mutableStateOf(prefs.getBoolean("gloss", true))
    var gloss: Boolean
        get() = glossState.value
        set(value) { glossState.value = value; prefs.edit().putBoolean("gloss", value).apply(); com.zapp.tv.ui.FocusStyle.gloss = value }
    private val seasonalTouchesState = mutableStateOf(prefs.getBoolean("seasonalTouches", true))
    /** Snow, a warm glow and gold edges on the seasonal row (Christmas and other times of year). */
    var seasonalTouches: Boolean
        get() = seasonalTouchesState.value
        set(value) { seasonalTouchesState.value = value; prefs.edit().putBoolean("seasonalTouches", value).apply() }
    private val spoilerShieldState = mutableStateOf(prefs.getBoolean("spoilerShield", false))
    /** Blurs the pictures and hides the stories of episodes you haven't watched yet. */
    var spoilerShield: Boolean
        get() = spoilerShieldState.value
        set(value) { spoilerShieldState.value = value; prefs.edit().putBoolean("spoilerShield", value).apply() }
    private val glowLevelState = mutableIntStateOf(prefs.getInt("glowLevel", 1))
    var glowLevel: Int
        get() = glowLevelState.intValue
        set(value) { glowLevelState.intValue = value; prefs.edit().putInt("glowLevel", value).apply(); com.zapp.tv.ui.FocusStyle.glowLevel = value }

    init {
        com.zapp.tv.ui.FocusStyle.ringWidth = ringWidthFor(prefs.getInt("focusRing", 1))
        com.zapp.tv.ui.FocusStyle.glow = prefs.getBoolean("focusGlow", true)
        com.zapp.tv.ui.FocusStyle.artGlow = prefs.getBoolean("artGlow", true)
        com.zapp.tv.ui.FocusStyle.trailers = prefs.getBoolean("trailers", true)
        com.zapp.tv.ui.FocusStyle.drift = prefs.getBoolean("drift", true)
        com.zapp.tv.ui.FocusStyle.trailerDelay = prefs.getInt("trailerDelay", 4)
        com.zapp.tv.ui.FocusStyle.trailerSound = prefs.getBoolean("trailerSound", false)
        com.zapp.tv.ui.FocusStyle.trailerBest = prefs.getBoolean("trailerBest", true)
        com.zapp.tv.ui.FocusStyle.trailerQuality = trailerQuality
        com.zapp.tv.ui.FocusStyle.trueBlack = prefs.getBoolean("trueBlack", false)
        com.zapp.tv.ui.FocusStyle.titleFont = prefs.getInt("titleFont", 0)
        com.zapp.tv.ui.FocusStyle.cardStyle = prefs.getInt("cardStyle", 0)
        com.zapp.tv.ui.FocusStyle.cardTitles = prefs.getBoolean("cardTitles", false)
        com.zapp.tv.ui.FocusStyle.glowLevel = prefs.getInt("glowLevel", 1)
        com.zapp.tv.ui.FocusStyle.gloss = prefs.getBoolean("gloss", true)
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

    // ---------------------------------------------------------------- live TV lists

    private val channelSortState = intState("channelSort", 0)
    /** How channel lists are ordered: 0 by number, 1 by name, 2 your own order. */
    var channelSort: Int
        get() = channelSortState.intValue
        set(value) {
            channelSortState.intValue = value
            prefs.edit().putInt("channelSort", value).apply()
        }

    private val startLastChannelState = mutableStateOf(prefs.getBoolean("startLastChannel", false))
    /** Opening Axon plays the last channel you watched, full screen. */
    var startLastChannel: Boolean
        get() = startLastChannelState.value
        set(value) {
            startLastChannelState.value = value
            prefs.edit().putBoolean("startLastChannel", value).apply()
        }

    /** Programme reminders, as JSON (channel ids and names only, never stream links). */
    var remindersJson: String
        get() = prefs.getString("reminders", "") ?: ""
        set(value) {
            prefs.edit().putString("reminders", value).apply()
        }

    private val currentPlaylistState = mutableLongStateOf(prefs.getLong("currentPlaylist", -1L))
    var currentPlaylistId: Long
        get() = currentPlaylistState.longValue
        set(value) {
            currentPlaylistState.longValue = value
            prefs.edit().putLong("currentPlaylist", value).apply()
        }
}
