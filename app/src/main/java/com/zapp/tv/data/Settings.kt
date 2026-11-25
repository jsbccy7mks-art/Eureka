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
