package com.zapp.tv

import android.app.Application
import com.zapp.tv.data.AppDatabase
import com.zapp.tv.data.PlaylistRepository
import com.zapp.tv.data.Settings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ZappApp : Application() {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // big playlists and guides take a while
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val database: AppDatabase by lazy { AppDatabase.build(this) }

    val repository: PlaylistRepository by lazy { PlaylistRepository(database, httpClient) }

    val settings: Settings by lazy { Settings(this) }
}
