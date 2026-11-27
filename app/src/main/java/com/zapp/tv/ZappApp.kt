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
        runCatching {
            val info = android.app.ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
            com.zapp.tv.ui.TrailerDevice.totalMemory = info.totalMem
        }
    }

    /** The app's shared web client (for small extra fetches, like subtitle files). */
    val sharedHttp: OkHttpClient get() = httpClient

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // big playlists and guides take a while
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val database: AppDatabase by lazy { AppDatabase.build(this) }

    val mediaDao get() = database.mediaDao()

    val repository: PlaylistRepository by lazy { PlaylistRepository(database, httpClient) }

    val media: MediaRepository by lazy { MediaRepository(database, httpClient, settings, cacheDir) }
    val stremio: com.zapp.tv.data.StremioSync by lazy { com.zapp.tv.data.StremioSync(database, httpClient, settings) }
    val nuvio: com.zapp.tv.data.NuvioSync by lazy { com.zapp.tv.data.NuvioSync(database, httpClient, settings) }
    val mdblist: com.zapp.tv.data.MdbList by lazy { com.zapp.tv.data.MdbList(httpClient, settings, tmdb) }
    val ratings: com.zapp.tv.data.Ratings by lazy { com.zapp.tv.data.Ratings(settings) }
    val sports: com.zapp.tv.data.Sports by lazy { com.zapp.tv.data.Sports(httpClient, settings) }
    val themes: com.zapp.tv.data.Themes by lazy { com.zapp.tv.data.Themes(httpClient) }
    val themePreviews: com.zapp.tv.data.ThemePreviews by lazy { com.zapp.tv.data.ThemePreviews(httpClient) }
    val sourceStats: com.zapp.tv.data.SourceStats by lazy { com.zapp.tv.data.SourceStats(this) }
    val tmdb: com.zapp.tv.data.Tmdb by lazy { com.zapp.tv.data.Tmdb(httpClient, settings) }
    val youtube: com.zapp.tv.data.YouTube by lazy { com.zapp.tv.data.YouTube(httpClient) }
    val introDb: com.zapp.tv.data.IntroDb by lazy { com.zapp.tv.data.IntroDb(httpClient) }
    val profiles: com.zapp.tv.data.ProfileStore by lazy { com.zapp.tv.data.ProfileStore(database.profileDao(), settings) }

    val settings: Settings by lazy { Settings(this) }

    val updater: Updater by lazy { Updater(this, httpClient) }

    val debrid: com.zapp.tv.data.Debrid by lazy { com.zapp.tv.data.Debrid(httpClient, settings) }

    val simkl: com.zapp.tv.data.SimklSync by lazy {
        com.zapp.tv.data.SimklSync(database, com.zapp.tv.data.SimklClient(httpClient, settings), settings)
    }
}
