package com.morpheus.family.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import com.morpheus.family.schedule.ScheduleEnforcer
import com.morpheus.family.service.GuardianService
import com.morpheus.family.vpn.BlockingVpnService

/**
 * Tears down all enforcement on the child device so the app can be uninstalled.
 *
 * Triggered remotely by the parent (via [com.morpheus.family.remote.RemoteRepository.requestRelease])
 * and executed in [GuardianService]. Order matters: stop blocking first, then
 * drop the admin/owner privileges that would otherwise prevent uninstall.
 */
object ProtectionManager {

    fun release(context: Context) {
        val app = context.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = MorpheusDeviceAdminReceiver.component(app)

        // 1. Stop actively blocking the network and cancel future toggles.
        BlockingVpnService.unblock(app)
        ScheduleEnforcer.cancel(app)

        // 2. Drop Device Owner powers (uninstall block + ownership) if held.
        runCatching {
            if (dpm.isDeviceOwnerApp(app.packageName)) {
                dpm.setUninstallBlocked(admin, app.packageName, false)
                dpm.clearDeviceOwnerApp(app.packageName)
            }
        }

        // 3. Deactivate Device Admin so the app is uninstallable normally.
        runCatching {
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        }

        // 4. Stop the supervisor; nothing re-arms enforcement after this.
        runCatching { app.stopService(Intent(app, GuardianService::class.java)) }
    }
}
