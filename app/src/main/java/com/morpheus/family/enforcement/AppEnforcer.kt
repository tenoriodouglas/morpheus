package com.morpheus.family.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.morpheus.family.admin.MorpheusDeviceAdminReceiver
import com.morpheus.family.data.AppPolicy

/**
 * Device-Owner reinforcement for focus ("dever de casa") mode: while it's active,
 * non-study managed apps are *suspended* (can't even be opened), not just cut off
 * from the internet. Everyday internet blocking — global and per-app — is handled
 * by [com.morpheus.family.vpn.BlockingVpnService], which works without Device
 * Owner and, importantly, lets the app still open (only its connection is cut).
 *
 * No-op unless Morpheus is Device Owner.
 */
object AppEnforcer {

    /** Packages suspended at the last [apply] (for diagnostics/UI). */
    @Volatile
    var blockedNow: Set<String> = emptySet()
        private set

    fun apply(context: Context, policy: AppPolicy, nowMillis: Long) {
        val managed = policy.apps.map { it.packageName }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (managed.isEmpty() || !dpm.isDeviceOwnerApp(context.packageName)) {
            blockedNow = emptySet()
            return
        }

        // Only focus mode suspends apps; everything else stays openable.
        val toSuspend = if (policy.homeworkActive(nowMillis)) {
            policy.apps.map { it.packageName }.filter { it !in policy.studyApps }.toSet()
        } else {
            emptySet()
        }
        blockedNow = toSuspend

        val admin = MorpheusDeviceAdminReceiver.component(context)
        val toAllow = (managed.toSet() - toSuspend).toTypedArray()
        runCatching { if (toSuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true) }
        runCatching { if (toAllow.isNotEmpty()) dpm.setPackagesSuspended(admin, toAllow, false) }
    }
}
