package com.morpheus.family.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.morpheus.family.data.DeviceRestrictions

/**
 * Applies Device Owner-only restrictions on the child device. No-op unless the
 * app is provisioned as Device Owner (see README). These close common bypasses:
 * installing new apps, and changing the system clock to defeat the schedule.
 */
object DeviceRestrictionsManager {

    fun apply(context: Context, restrictions: DeviceRestrictions) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = MorpheusDeviceAdminReceiver.component(context)

        toggle(dpm, admin, UserManager.DISALLOW_INSTALL_APPS, restrictions.blockAppInstall)
        toggle(dpm, admin, UserManager.DISALLOW_CONFIG_DATE_TIME, restrictions.lockDateTime)
        // Blocks Developer Options / USB debugging — the entry point for enabling
        // a fake-GPS "mock location" app.
        toggle(dpm, admin, UserManager.DISALLOW_DEBUGGING_FEATURES, restrictions.blockDevOptions)
        runCatching { dpm.setAutoTimeRequired(admin, restrictions.requireAutoTime) }

        // Content filtering: force a family-safe DNS-over-TLS host globally.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                if (restrictions.safeDnsHost.isNotBlank()) {
                    dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, restrictions.safeDnsHost)
                } else {
                    dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                }
            }
        }
    }

    private fun toggle(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        key: String,
        on: Boolean,
    ) {
        runCatching {
            if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        }
    }
}
