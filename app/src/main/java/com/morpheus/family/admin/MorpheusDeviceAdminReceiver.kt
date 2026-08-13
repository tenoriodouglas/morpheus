package com.morpheus.family.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.morpheus.family.data.Prefs
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.service.GuardianService
import kotlinx.coroutines.runBlocking

/**
 * Makes the app a Device Administrator.
 *
 * While an app is an active device admin the user cannot uninstall it from the
 * normal launcher/Settings flow — they must first deactivate admin, and we make
 * that friction visible AND alert the parent the moment it happens (onDisabled).
 * For a *truly* unremovable install (child cannot deactivate at all) the device
 * must be provisioned as **Device Owner** on a factory-reset device — either via
 * ADB or, with no PC, by scanning a provisioning QR during the initial setup
 * wizard. That path also unlocks stronger controls such as
 * [DevicePolicyManager.setUninstallBlocked].
 */
class MorpheusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        applyStrongLockdownIfOwner(context)
        GuardianService.start(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Desativar a administração remove a proteção de horário e o responsável será " +
            "avisado imediatamente."

    /**
     * Fired when the child (or anyone) actually deactivates device admin. We
     * warn the parent right away over Firestore. Best-effort: the write is
     * queued and delivered while the network is still available.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val app = context.applicationContext
        runCatching {
            val pairId = runBlocking { Prefs(app).pairedId() }
            if (!pairId.isNullOrBlank()) {
                RemoteRepository.reportAlert(
                    app, pairId, "protection_removed", System.currentTimeMillis(),
                )
            }
        }
    }

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
