package com.zapp.tv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Checks the project's GitHub releases for a newer build and installs it.
 * Each successful build is published as release "build-N", where N is also the app's version code.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    data class Release(val build: Int, val apkUrl: String, val notes: String, val sizeBytes: Long)

    enum class InstallResult { Started, NeedsPermission, NoInstaller }

    /** Returns the latest release, or throws with a readable message. */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (response.code == 404) error("No releases have been published yet.")
            if (!response.isSuccessful) error("GitHub answered with HTTP ${response.code}. Try again later.")
            response.body?.string() ?: error("GitHub sent an empty answer.")
        }
        val json = JSONObject(body)
        val build = json.optString("tag_name").removePrefix("build-").toIntOrNull()
            ?: error("The latest release isn't a Axon build.")
        val assets = json.optJSONArray("assets")
        var url: String? = null
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == APK_NAME) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                }
            }
        }
        Release(
            build = build,
            apkUrl = url ?: error("The latest release has no APK attached."),
            notes = json.optString("body"),
            sizeBytes = size,
        )
    }

    /** Downloads the APK into the app's cache, reporting progress from 0 to 1. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "zapp-tv-${release.build}.apk")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The download failed with HTTP ${response.code}.")
            val body = response.body ?: error("The download was empty.")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        target
    }

    /** Hands the APK to the system installer, which asks the user to confirm. */
    fun install(file: File): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                // Some TVs (Fire TV) have no such screen; the message in Settings explains where to look.
            }
            return InstallResult.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            InstallResult.Started
        } catch (e: ActivityNotFoundException) {
            InstallResult.NoInstaller
        }
    }

    companion object {
        const val REPO = "jsbccy7mks-art/Eureka"
        const val APK_NAME = "zapp-tv.apk"
    }
}
