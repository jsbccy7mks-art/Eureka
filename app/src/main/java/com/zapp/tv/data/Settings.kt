package com.zapp.tv.data

import android.content.Context
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

    private val autoGuideState = mutableStateOf(prefs.getBoolean("autoGuide", true))
    var autoGuide: Boolean
        get() = autoGuideState.value
        set(value) {
            autoGuideState.value = value
            prefs.edit().putBoolean("autoGuide", value).apply()
        }

    private val currentPlaylistState = mutableLongStateOf(prefs.getLong("currentPlaylist", -1L))
    var currentPlaylistId: Long
        get() = currentPlaylistState.longValue
        set(value) {
            currentPlaylistState.longValue = value
            prefs.edit().putLong("currentPlaylist", value).apply()
        }
}
