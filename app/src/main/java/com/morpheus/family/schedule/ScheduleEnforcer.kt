package com.morpheus.family.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.morpheus.family.admin.DeviceRestrictionsManager
import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.AppUsage
import com.morpheus.family.data.BlockedApp
import com.morpheus.family.data.ChildStatus
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.enforcement.AppEnforcer
import com.morpheus.family.enforcement.UsageTracker
import com.morpheus.family.location.LocationReporter
import com.morpheus.family.receiver.ScheduleAlarmReceiver
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.time.TrustedTimeProvider
import com.morpheus.family.vpn.BlockingVpnService
import kotlinx.coroutines.runBlocking

/**
 * The brain of the child-side enforcement. Uses [TrustedTimeProvider] (not the
 * tamperable system clock) to decide whether to block, applies the internet
 * schedule, per-app rules and device restrictions, then arms an exact alarm for
 * the next boundary. Fails closed: if time can't be trusted or looks tampered,
 * it blocks and alerts the parent.
 */
object ScheduleEnforcer {

    /** Re-evaluate all policy and enforce. Safe to call often. */
    fun apply(context: Context) {
        val prefs = Prefs(context)
        val schedule = runBlocking { prefs.schedule() }
        val appPolicy = runBlocking { prefs.appPolicy() }

        val t = TrustedTimeProvider.now(context)
        var blocked = schedule.isBlockedAt(t.millis)

        // Homework/focus mode also blocks internet.
        if (appPolicy.homeworkActive(t.millis)) blocked = true

        // Fail closed: a tampered wall clock means block now and warn the parent.
        if (t.tampered) {
            blocked = true
            runCatching {
                val pairId = runBlocking { prefs.pairedId() } ?: ""
                RemoteRepository.reportAlert(context, pairId, "time_tamper", System.currentTimeMillis())
            }
        }

        if (blocked) {
            if (vpnConsentGranted(context)) BlockingVpnService.block(context)
        } else {
            BlockingVpnService.unblock(context)
        }

        // Per-app blocking + device restrictions.
        runCatching { AppEnforcer.apply(context, appPolicy, t.millis) }
        runCatching { DeviceRestrictionsManager.apply(context, appPolicy.restrictions) }

        armNextBoundary(context, schedule, t.millis)
    }

    /** Refresh trusted network time, enforce, then upload telemetry. */
    suspend fun syncAndApply(context: Context) {
        runCatching { TrustedTimeProvider.sync(context) }
        apply(context)
        runCatching { uploadTelemetry(context) }
    }

    /** Child -> parent: push current location (+geofence) and a usage summary. */
    private suspend fun uploadTelemetry(context: Context) {
        val prefs = Prefs(context)
        val pairId = prefs.pairedId() ?: return
        if (pairId.isBlank()) return
        val appPolicy = prefs.appPolicy()
        val now = System.currentTimeMillis()

        // Claim membership (security) and report a heartbeat (online status).
        RemoteRepository.joinMembership(context, pairId)
        RemoteRepository.reportHeartbeat(context, pairId, now)

        LocationReporter.reportOnce(context, pairId, appPolicy.geofence, now)

        if (UsageTracker.hasUsageAccess(context)) {
            val usage = UsageTracker.topAppsToday(context)
            if (usage.isNotEmpty()) {
                val json = usage.joinToString(",", "{", "}") { (pkg, min) -> "\"$pkg\":$min" }
                RemoteRepository.reportUsage(context, pairId, json, now)
            }
        }

        uploadStatus(context)
    }

    /**
     * Child -> parent: the live transparency snapshot (screen time, foreground
     * app, blocked apps). Called every minute by the guardian service so the
     * parent's dashboard reflects "now". Cheap and best-effort.
     */
    suspend fun uploadStatus(context: Context) {
        val prefs = Prefs(context)
        val pairId = prefs.pairedId() ?: return
        if (pairId.isBlank()) return

        val schedule = prefs.schedule()
        val appPolicy = prefs.appPolicy()
        val t = TrustedTimeProvider.now(context)
        val now = System.currentTimeMillis()

        val hasUsage = UsageTracker.hasUsageAccess(context)
        val current = if (hasUsage) UsageTracker.currentForegroundApp(context) else null
        val top = if (hasUsage) UsageTracker.topAppsToday(context, 10) else emptyList()

        val status = ChildStatus(
            at = now,
            screenOn = UsageTracker.isScreenOn(context),
            currentApp = current?.first.orEmpty(),
            currentAppLabel = current?.first?.let { UsageTracker.labelFor(context, it) }.orEmpty(),
            currentAppSince = current?.second ?: 0L,
            totalMinutesToday = if (hasUsage) UsageTracker.totalTodayMinutes(context) else 0,
            topApps = top.map { (pkg, min) -> AppUsage(pkg, UsageTracker.labelFor(context, pkg), min) },
            blockedApps = blockedAppsNow(context, appPolicy, t.millis),
            internetBlocked = schedule.isBlockedAt(t.millis) ||
                appPolicy.homeworkActive(t.millis) || t.tampered,
            homeworkActive = appPolicy.homeworkActive(t.millis),
            budgetMinutes = appPolicy.dailyScreenBudgetMinutes,
            usageAccessGranted = hasUsage,
        )
        RemoteRepository.reportHeartbeat(context, pairId, now)
        RemoteRepository.reportStatus(context, pairId, ChildStatus.encode(status), now)
    }

    /** Which managed apps are blocked right now, and why — mirrors [AppEnforcer.apply]. */
    private fun blockedAppsNow(
        context: Context,
        policy: AppPolicy,
        nowMillis: Long,
    ): List<BlockedApp> {
        if (policy.apps.isEmpty()) return emptyList()
        val budgetExceeded = policy.dailyScreenBudgetMinutes > 0 &&
            UsageTracker.totalTodayMinutes(context) >= policy.dailyScreenBudgetMinutes
        val homework = policy.homeworkActive(nowMillis)

        return policy.apps.mapNotNull { rule ->
            val reason = when {
                homework && rule.packageName !in policy.studyApps -> "homework"
                budgetExceeded -> "budget"
                policy.isAppBlockedByWindow(rule.packageName, nowMillis) -> "schedule"
                rule.dailyLimitMinutes > 0 &&
                    UsageTracker.todayMinutes(context, rule.packageName) >= rule.dailyLimitMinutes -> "limit"
                else -> null
            } ?: return@mapNotNull null
            BlockedApp(
                pkg = rule.packageName,
                label = rule.label.ifBlank { UsageTracker.labelFor(context, rule.packageName) },
                reason = reason,
            )
        }
    }

    /** True once the child has approved the VPN (or Device Owner pre-granted it). */
    fun vpnConsentGranted(context: Context): Boolean = VpnService.prepare(context) == null

    /** Cancel the pending boundary alarm (used when protection is released). */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { am.cancel(pi) }
    }

    private fun armNextBoundary(context: Context, schedule: Schedule, now: Long) {
        val next = nextBoundary(schedule, now) ?: (now + DEFAULT_RECHECK_MS)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_CODE,
            Intent(context, ScheduleAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // canScheduleExactAlarms() exists only on API 31+; before that, exact
        // alarms need no special permission, so schedule them directly.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }
    }

    /**
     * The next instant (> [now], within a week) at which [Schedule.isBlockedAt]
     * flips. Computed by walking minute boundaries so it always agrees with the
     * predicate that actually drives blocking.
     */
    fun nextBoundary(schedule: Schedule, now: Long): Long? {
        val current = schedule.isBlockedAt(now)
        // Align to the next whole minute, then step minute by minute for a week.
        var t = now - (now % 60_000L) + 60_000L
        val limit = now + 7L * 24 * 60 * 60 * 1000
        while (t <= limit) {
            if (schedule.isBlockedAt(t) != current) return t
            t += 60_000L
        }
        return null
    }

    private const val REQ_CODE = 7100
    private const val DEFAULT_RECHECK_MS = 60L * 60 * 1000 // hourly safety net
}
