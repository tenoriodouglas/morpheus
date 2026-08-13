package com.morpheus.family.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.morpheus.family.service.GuardianService

/**
 * Makes the app a Device Administrator.
 *
 * While an app is an active device admin the user cannot uninstall it from the
 * normal launcher/Settings flow — they must first deactivate admin, and we make
 * that friction visible. For a *truly* unremovable install (child cannot
 * deactivate at all) the device must be provisioned as **Device Owner** via ADB
 * on a factory-reset device (see README); that path also unlocks stronger
 * controls such as [DevicePolicyManager.setUninstallBlocked].
 */
class MorpheusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        applyStrongLockdownIfOwner(context)
        GuardianService.start(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Desativar a administração vai remover a proteção de horário definida pelo responsável."

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, MorpheusDeviceAdminReceiver::class.java)

        /**
         * When the app is Device Owner, block its own uninstall outright and
         * make the launcher icon non-removable. No-op otherwise.
         */
        fun applyStrongLockdownIfOwner(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = component(context)
            runCatching {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            }
        }
    }
}
