package com.zapp.tv

import android.app.Application
import com.zapp.tv.addons.MediaRepository
import com.zapp.tv.data.AppDatabase
import com.zapp.tv.data.CrashReporter
import com.zapp.tv.data.PlaylistRepository
import com.zapp.tv.data.Settings
import com.zapp.tv.data.Updater
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ZappApp : Application(), coil.ImageLoaderFactory {
    /** Artwork fades in softly instead of popping in. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this).crossfade(260).build()

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this, "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
    }

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

    val media: MediaRepository by lazy { MediaRepository(database, httpClient, settings) }
    val stremio: com.zapp.tv.data.StremioSync by lazy { com.zapp.tv.data.StremioSync(database, httpClient, settings) }
    val profiles: com.zapp.tv.data.ProfileStore by lazy { com.zapp.tv.data.ProfileStore(database.profileDao(), settings) }

    val settings: Settings by lazy { Settings(this) }

    val updater: Updater by lazy { Updater(this, httpClient) }

    val debrid: com.zapp.tv.data.Debrid by lazy { com.zapp.tv.data.Debrid(httpClient, settings) }

    val simkl: com.zapp.tv.data.SimklSync by lazy {
        com.zapp.tv.data.SimklSync(database, com.zapp.tv.data.SimklClient(httpClient, settings), settings)
    }
}
