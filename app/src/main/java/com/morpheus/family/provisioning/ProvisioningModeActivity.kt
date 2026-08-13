package com.morpheus.family.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Handles `android.app.action.GET_PROVISIONING_MODE` during Device Owner
 * provisioning (Android 10+). We declare Morpheus a **fully managed device**.
 * Headless — it just returns the mode and finishes.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }
}
