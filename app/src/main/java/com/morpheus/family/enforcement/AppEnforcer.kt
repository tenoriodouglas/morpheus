package com.morpheus.family.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.morpheus.family.admin.MorpheusDeviceAdminReceiver
import com.morpheus.family.data.AppPolicy

/**
 * Decides which managed apps must be blocked right now and enforces it.
 *
 * Enforcement path:
 *  - **Device Owner** (strong): suspends/unsuspends packages precisely via
 *    [DevicePolicyManager.setPackagesSuspended] — the app can't even be opened.
 *
 * [blockedNow] is also exposed for any future foreground-based fallback blocker.
 */
object AppEnforcer {

    /** Packages that should be blocked at the last [apply]. */
    @Volatile
    var blockedNow: Set<String> = emptySet()
        private set

    fun apply(context: Context, policy: AppPolicy, nowMillis: Long) {
        val managed = policy.apps.map { it.packageName }
        if (managed.isEmpty()) {
            blockedNow = emptySet()
            return
        }

        val budgetExceeded = policy.dailyScreenBudgetMinutes > 0 &&
            UsageTracker.totalTodayMinutes(context) >= policy.dailyScreenBudgetMinutes
        val homework = policy.homeworkActive(nowMillis)

        val blocked = policy.apps.filter { rule ->
            // Homework/focus mode blocks every managed app (unless it's a study app).
            (homework && rule.packageName !in policy.studyApps) ||
                budgetExceeded ||
                policy.isAppBlockedByWindow(rule.packageName, nowMillis) ||
                (rule.dailyLimitMinutes > 0 &&
                    UsageTracker.todayMinutes(context, rule.packageName) >= rule.dailyLimitMinutes)
        }.map { it.packageName }.toSet()

        blockedNow = blocked

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            val admin = MorpheusDeviceAdminReceiver.component(context)
            val toBlock = blocked.toTypedArray()
            val toAllow = (managed - blocked).toTypedArray()
            runCatching { if (toBlock.isNotEmpty()) dpm.setPackagesSuspended(admin, toBlock, true) }
            runCatching { if (toAllow.isNotEmpty()) dpm.setPackagesSuspended(admin, toAllow, false) }
        }
    }
}
