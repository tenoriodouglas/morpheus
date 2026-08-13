package com.morpheus.family.enforcement

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
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
}
