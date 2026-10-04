package com.calendarviewbox.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val build: Int, val apkUrl: String)

/**
 * Checks the GitHub Releases page for a newer build and installs it in place.
 * Builds are tagged "build-N", and N is also the app's versionCode.
 */
object Updater {

    suspend fun latest(repo: String): UpdateInfo? = withContext(Dispatchers.IO) {
        if (repo.isBlank()) return@withContext null
        val (code, body) = httpRequest(
            "GET",
            "https://api.github.com/repos/$repo/releases/latest",
            mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "CalendarViewBox"),
        )
        if (code !in 200..299) throw HttpStatusException(code, "Update check failed ($code)")
        val o = JSONObject(body)
        val build = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
        val assets = o.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?: return@withContext null
        UpdateInfo(build, apk.optString("browser_download_url"))
    }

    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CalendarViewBox")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code, "Download failed ($code)")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 10 != lastShown) {
                                lastShown = pct / 10
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Opens Android's own "Do you want to update this app?" screen, the same one used when
     * installing from the browser. Some devices (including Boox) refuse installs made
     * directly through the installer API, but allow this route.
     */
    fun openSystemInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Last resort: let the browser download the APK, as on first install. */
    fun openInBrowser(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
