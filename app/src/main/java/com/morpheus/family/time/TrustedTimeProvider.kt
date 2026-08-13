package com.morpheus.family.time

import android.content.Context
import android.os.SystemClock
import com.morpheus.family.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Tamper-resistant clock.
 *
 * The device wall clock ([System.currentTimeMillis]) can be changed by the user
 * to defeat a time-based block. Instead we anchor to a trusted network time
 * (the HTTPS `Date` response header) and advance it with
 * [SystemClock.elapsedRealtime] — a monotonic counter the user cannot fast-
 * forward. This yields correct time online *and* offline (until reboot, which
 * resets the monotonic counter and forces a re-sync).
 *
 * [now] also reports whether the time is currently trustworthy and whether the
 * wall clock looks tampered, so the enforcer can fail closed (block on doubt).
 */
object TrustedTimeProvider {

    data class TrustedNow(val millis: Long, val trusted: Boolean, val tampered: Boolean)

    private data class Anchor(val utcMillis: Long, val elapsed: Long)

    @Volatile private var anchor: Anchor? = null
    @Volatile private var loaded = false

    private const val TAMPER_THRESHOLD_MS = 5 * 60 * 1000L   // 5 min drift = suspicious
    private const val MAX_ANCHOR_AGE_MS = 12 * 60 * 60 * 1000L // re-sync at least twice a day

    private val TIME_URLS = listOf(
        "https://www.google.com",
        "https://cloudflare.com",
    )

    /**
     * Load a persisted anchor once per process. The stored anchor is only valid
     * if the monotonic clock hasn't gone backwards (which would mean a reboot).
     */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val stored = runCatching { runBlocking { Prefs(context).timeAnchor() } }.getOrNull() ?: return
        val (utc, elapsed) = stored
        if (utc > 0L && SystemClock.elapsedRealtime() >= elapsed) {
            anchor = Anchor(utc, elapsed)
        }
    }

    /** Current best estimate of real time, with trust/tamper flags. */
    fun now(context: Context): TrustedNow {
        ensureLoaded(context)
        val a = anchor
        val nowElapsed = SystemClock.elapsedRealtime()
        if (a != null && nowElapsed >= a.elapsed) {
            val age = nowElapsed - a.elapsed
            val projected = a.utcMillis + age
            val fresh = age <= MAX_ANCHOR_AGE_MS
            val tampered = abs(System.currentTimeMillis() - projected) > TAMPER_THRESHOLD_MS
            return TrustedNow(projected, trusted = fresh, tampered = tampered)
        }
        // No valid anchor (never synced, or rebooted): fall back to system time.
        return TrustedNow(System.currentTimeMillis(), trusted = false, tampered = false)
    }

    /**
     * Fetch trusted network time and set the anchor. Returns true on success.
     * Runs on IO; safe to call whenever the network is available.
     */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        for (url in TIME_URLS) {
            val utc = fetchServerDate(url)
            if (utc > 0) {
                val elapsed = SystemClock.elapsedRealtime()
                anchor = Anchor(utcMillis = utc, elapsed = elapsed)
                loaded = true
                runCatching { Prefs(context).setTimeAnchor(utc, elapsed) }
                return@withContext true
            }
        }
        false
    }

    private fun fetchServerDate(urlString: String): Long {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = false
            }
            try {
                conn.connect()
                conn.getHeaderFieldDate("Date", 0L)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(0L)
    }
}
