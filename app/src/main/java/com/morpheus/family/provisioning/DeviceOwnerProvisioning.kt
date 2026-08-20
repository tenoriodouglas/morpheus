package com.morpheus.family.provisioning

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Builds the JSON payload for a **Device Owner provisioning QR**.
 *
 * The child device is factory-reset; on the setup wizard's welcome screen the
 * user taps 6 times to open a QR scanner and scans this code. Android then
 * downloads the APK from [apkDownloadUrl] (verifying it against
 * [signatureChecksum]) and installs Morpheus as Device Owner — no PC/ADB needed.
 *
 * Requirements:
 *  - [apkDownloadUrl] must serve the **signed release APK** whose signing key
 *    matches [signatureChecksum].
 *  - [PROVISIONING_PACKAGE] must equal that APK's applicationId.
 */
object DeviceOwnerProvisioning {

    // The published applicationId. Must match the APK served by apkDownloadUrl.
    const val PROVISIONING_PACKAGE = "com.morpheus.family"
    private const val ADMIN_RECEIVER_CLASS =
        "com.morpheus.family.admin.MorpheusDeviceAdminReceiver"

    // Fixed, login-free location of the signed release APK used for provisioning,
    // and a small manifest that pairs that URL with the APK's signing checksum.
    // Both are published by CI on every Play release (see .github/workflows/android.yml),
    // so the parent app needs no manual URL/checksum entry.
    const val DEFAULT_APK_URL =
        "https://github.com/tenoriodouglas/morpheus/releases/download/apk-latest/morpheus-release.apk"
    private const val MANIFEST_URL =
        "https://github.com/tenoriodouglas/morpheus/releases/download/apk-latest/provisioning.json"

    /**
     * Fetch the published provisioning manifest and return (apkUrl, checksum).
     * The checksum here is the signing checksum of the exact hosted APK — always
     * correct regardless of which build (debug/release) the parent app is running,
     * unlike [signatureChecksum] which reflects the running app's own signature.
     * Returns null when offline or the manifest is missing/malformed.
     */
    suspend fun fetchManifest(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val body = runCatching {
            val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext null
        runCatching {
            val obj = Json.parseToJsonElement(body).jsonObject
            val url = obj["apkUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val chk = obj["checksum"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            if (url != null && chk != null) url to chk else null
        }.getOrNull()
    }

    private const val K_COMPONENT = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
    private const val K_CHECKSUM = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
    private const val K_DOWNLOAD = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"
    private const val K_LEAVE_SYSTEM_APPS = "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"
    private const val K_SKIP_ENCRYPTION = "android.app.extra.PROVISIONING_SKIP_ENCRYPTION"
    private const val K_WIFI_SSID = "android.app.extra.PROVISIONING_WIFI_SSID"
    private const val K_WIFI_PASSWORD = "android.app.extra.PROVISIONING_WIFI_PASSWORD"
    private const val K_WIFI_SECURITY = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"

    private fun component(): String =
        ComponentName(PROVISIONING_PACKAGE, ADMIN_RECEIVER_CLASS).flattenToString()

    /**
     * The URL-safe, unpadded base64 SHA-256 of the running app's signing
     * certificate — the value Android expects in the QR. Correct only when this
     * app is the **release** build signed with the same key as the hosted APK.
     */
    fun signatureChecksum(context: Context): String {
        val pm = context.packageManager
        val pkg = context.packageName
        val certBytes: ByteArray = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                (info.signingInfo?.apkContentsSigners ?: emptyArray()).first().toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures!!.first().toByteArray()
            }
        }.getOrElse { return "" }
        val sha = MessageDigest.getInstance("SHA-256").digest(certBytes)
        return Base64.encodeToString(sha, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Build the QR JSON payload. Wi-Fi fields are optional (blank = omit). */
    fun buildQrPayload(
        apkDownloadUrl: String,
        signatureChecksum: String,
        wifiSsid: String = "",
        wifiPassword: String = "",
    ): String = buildJsonObject {
        put(K_COMPONENT, component())
        put(K_CHECKSUM, signatureChecksum)
        put(K_DOWNLOAD, apkDownloadUrl)
        put(K_LEAVE_SYSTEM_APPS, true)
        put(K_SKIP_ENCRYPTION, false)
        if (wifiSsid.isNotBlank()) {
            put(K_WIFI_SSID, wifiSsid)
            put(K_WIFI_SECURITY, if (wifiPassword.isNotBlank()) "WPA" else "NONE")
            if (wifiPassword.isNotBlank()) put(K_WIFI_PASSWORD, wifiPassword)
        }
    }.toString()
}
