package com.morpheus.family.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import com.morpheus.family.receiver.ScheduleAlarmReceiver
import com.morpheus.family.vpn.BlockingVpnService
import kotlinx.coroutines.runBlocking

/**
 * The brain of the child-side enforcement. Given the current [Schedule] it
 * turns the blocking VPN on or off, then arms an exact alarm for the next moment
 * the decision will change — so enforcement is event-driven and battery-cheap
 * rather than a busy poll.
 */
object ScheduleEnforcer {

    /** Re-evaluate the schedule and (un)block accordingly. Safe to call often. */
    fun apply(context: Context, now: Long = System.currentTimeMillis()) {
        val schedule = runBlocking { Prefs(context).schedule() }
        val blocked = schedule.isBlockedAt(now)

        if (blocked) {
            if (vpnConsentGranted(context)) {
                BlockingVpnService.block(context)
            }
            // If consent is not yet granted, the child UI surfaces the prompt.
        } else {
            BlockingVpnService.unblock(context)
        }

        armNextBoundary(context, schedule, now)
    }

    /** True once the child has approved the VPN (or Device Owner pre-granted it). */
    fun vpnConsentGranted(context: Context): Boolean = VpnService.prepare(context) == null

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
