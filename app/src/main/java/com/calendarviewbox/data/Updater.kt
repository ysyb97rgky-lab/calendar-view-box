package com.calendarviewbox.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.calendarviewbox.InstallResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Messages from the system installer (e.g. a failed install), shown on the board. */
    val installerMessages = MutableStateFlow<String?>(null)

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

    /** Hands the APK to Android's installer. Android shows its own "Update this app?" prompt. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("CalendarViewBox.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags,
            )
            session.commit(callback.intentSender)
        }
    }
}
