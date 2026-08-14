package com.morpheus.family.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.morpheus.family.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What the CI publishes next to the rolling debug APK. */
@Serializable
private data class LatestRelease(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
)

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/**
 * Self-update for the debug build downloaded from GitHub (the Play build uses
 * In-App Updates instead). Reads a small manifest published beside the rolling
 * APK, and if it names a higher versionCode than this build, downloads the APK
 * and hands it to the system installer.
 *
 * Guarded by [BuildConfig.DEBUG]: on the Play/release build every entry point is
 * a no-op, and the install permission + FileProvider only exist in the debug
 * manifest, so nothing here ships to the store.
 */
object GithubUpdater {

    private const val MANIFEST_URL =
        "https://github.com/tenoriodouglas/morpheus/releases/download/apk-latest/latest.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** The newer release if one exists, else null. Safe to call on any build. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) return@withContext null
        val body = runCatching { fetch(MANIFEST_URL) }.getOrNull() ?: return@withContext null
        val latest = runCatching { json.decodeFromString<LatestRelease>(body) }.getOrNull()
            ?: return@withContext null
        if (latest.versionCode > BuildConfig.VERSION_CODE && latest.apkUrl.isNotBlank()) {
            UpdateInfo(latest.versionCode, latest.versionName, latest.apkUrl)
        } else {
            null
        }
    }

    /** Whether the OS will let us install APKs (Android 8+ gates this per-app). */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Send the user to grant "install unknown apps" for this app. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Download [info]'s APK and launch the installer. Returns false if the
     * download failed; the install prompt itself is handled by the system.
     */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Boolean {
        val apk = withContext(Dispatchers.IO) {
            runCatching { download(context, info.apkUrl) }.getOrNull()
        } ?: return false

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        return true
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "morpheus-update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        conn.inputStream.use { input -> out.outputStream.use { input.copyTo(it) } }
        return out
    }
}
