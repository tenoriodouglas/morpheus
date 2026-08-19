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

        // Block a "second space" / secondary user / work or clone profile — a common
        // way to run apps in an unmanaged space and dodge all enforcement. Always on
        // for a Device Owner child device. (OEM-proprietary spaces like Xiaomi's
        // "Second space" or Samsung "Secure Folder" may not honour these AOSP flags.)
        toggle(dpm, admin, UserManager.DISALLOW_ADD_USER, true)
        toggle(dpm, admin, UserManager.DISALLOW_ADD_MANAGED_PROFILE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            toggle(dpm, admin, UserManager.DISALLOW_USER_SWITCH, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            toggle(dpm, admin, UserManager.DISALLOW_ADD_CLONE_PROFILE, true)
        }

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
