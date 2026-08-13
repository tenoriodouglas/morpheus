package com.morpheus.family.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
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
        runCatching { dpm.setAutoTimeRequired(admin, restrictions.requireAutoTime) }
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
