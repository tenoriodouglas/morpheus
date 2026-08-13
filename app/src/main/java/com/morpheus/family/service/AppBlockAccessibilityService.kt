package com.morpheus.family.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.morpheus.family.enforcement.AppEnforcer
import com.morpheus.family.ui.BlockedAppActivity

/**
 * Fallback app blocker for installs that are NOT Device Owner.
 *
 * Watches which app comes to the foreground; if it is in
 * [AppEnforcer.blockedNow], it covers it with [BlockedAppActivity] and sends the
 * user home. Requires the user to enable the service in Accessibility settings
 * (a one-time step in the child setup). On Device Owner installs this is
 * unnecessary because packages are suspended outright.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // never block ourselves
        if (pkg in AppEnforcer.blockedNow) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val i = Intent(this, BlockedAppActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(BlockedAppActivity.EXTRA_PACKAGE, pkg)
            }
            runCatching { startActivity(i) }
        }
    }

    override fun onInterrupt() {}
}
