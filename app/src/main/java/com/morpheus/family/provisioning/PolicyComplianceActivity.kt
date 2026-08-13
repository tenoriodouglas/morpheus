package com.morpheus.family.provisioning

import android.app.Activity
import android.os.Bundle
import com.morpheus.family.admin.MorpheusDeviceAdminReceiver
import com.morpheus.family.service.GuardianService

/**
 * Handles `android.app.action.ADMIN_POLICY_COMPLIANCE`, shown by the system
 * right after Device Owner provisioning so the DPC can finish setting up. We
 * apply the strong lockdown (uninstall-blocked), start enforcement and finish.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MorpheusDeviceAdminReceiver.applyStrongLockdownIfOwner(applicationContext)
        GuardianService.start(applicationContext)
        setResult(RESULT_OK)
        finish()
    }
}
