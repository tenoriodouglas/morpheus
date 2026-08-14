package com.morpheus.family.enforcement

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import com.morpheus.family.data.KnownApps
import java.util.Calendar

/**
 * Reads per-app foreground time for *today* via [UsageStatsManager]. Requires the
 * user to grant "Usage access" (a special permission granted in Settings, not a
 * runtime permission). When not granted, all values are 0 so limits simply don't
 * trigger — they never fire spuriously.
 */
object UsageTracker {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION") // checkOpNoThrow works from API 19; minSdk is 26
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Foreground minutes used by [packageName] since local midnight. */
    fun todayMinutes(context: Context, packageName: String): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), now)
        }.getOrNull() ?: return 0
        val totalMs = stats.filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
        return (totalMs / 60000L).toInt()
    }

    /** Top [n] apps by foreground minutes since local midnight. */
    fun topAppsToday(context: Context, n: Int = 8): List<Pair<String, Int>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), now)
        }.getOrNull() ?: return emptyList()
        return stats
            .groupBy { it.packageName }
            .mapValues { (_, list) -> (list.sumOf { it.totalTimeInForeground } / 60000L).toInt() }
            .filterValues { it > 0 }
            .entries.sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    /** Total foreground minutes across all apps since local midnight. */
    fun totalTodayMinutes(context: Context): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), now)
        }.getOrNull() ?: return 0
        val totalMs = stats.sumOf { it.totalTimeInForeground }
        return (totalMs / 60000L).toInt()
    }

    /**
     * The package currently in the foreground and when it got there, or null if
     * nothing is (screen off, launcher, or no usage access). Derived by replaying
     * the recent foreground/background events, which is the only way to read the
     * current app without an accessibility service.
     */
    fun currentForegroundApp(context: Context): Pair<String, Long>? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = runCatching {
            usm.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
        }.getOrNull() ?: return null

        var pkg: String? = null
        var since = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION") // MOVE_TO_* == ACTIVITY_RESUMED/PAUSED; works on minSdk 26
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    pkg = event.packageName
                    since = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName == pkg) {
                    pkg = null
                    since = 0L
                }
            }
        }
        return pkg?.let { it to since }
    }

    /** Whether the screen is currently on (does not reveal what's displayed). */
    fun isScreenOn(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isInteractive }.getOrDefault(false)
    }

    /**
     * Best-effort friendly name for a package. Falls back to the curated list and
     * then the raw package id: on API 30+ package visibility hides most apps, and
     * we deliberately avoid the QUERY_ALL_PACKAGES permission.
     */
    fun labelFor(context: Context, packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { KnownApps.labelFor(packageName) }
            .ifBlank { packageName }
    }

    private const val FOREGROUND_LOOKBACK_MS = 2L * 60 * 60 * 1000 // 2h of events
}
