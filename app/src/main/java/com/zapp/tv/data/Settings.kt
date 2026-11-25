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

    private val audioLanguageState = stringState("audioLanguage", "")
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

    private val subtitleLanguageState = stringState("subtitleLanguage", "")
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

    private val autoPlayFirstState = mutableStateOf(prefs.getBoolean("autoPlayFirst", false))
    /** Start the first suitable source straight away instead of showing the list. */
    var autoPlayFirst: Boolean
        get() = autoPlayFirstState.value
        set(value) {
            autoPlayFirstState.value = value
            prefs.edit().putBoolean("autoPlayFirst", value).apply()
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
