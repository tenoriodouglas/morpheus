package com.morpheus.family.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.morpheus.family.MorpheusApp
import com.morpheus.family.R
import com.morpheus.family.ui.MainActivity

/**
 * Blocks internet without root by standing up a local VPN whose routes capture
 * packets and forward none — traffic has nowhere to go. Two modes:
 *
 *  - **Block all** ([EXTRA_BLOCK_ALL]): every app loses internet EXCEPT Morpheus
 *    itself (added via [VpnService.Builder.addDisallowedApplication]), so the
 *    guardian can still upload the child's location and receive the parent's
 *    remote commands while everything else is offline.
 *  - **Per-app** ([EXTRA_APPS]): only the named packages route through the
 *    black-hole (via [VpnService.Builder.addAllowedApplication]); the rest of the
 *    device — and Morpheus — keep normal internet.
 *
 * The interface is (re)established whenever the requested config changes, since
 * the allowed/disallowed app set is fixed at establish time.
 */
class BlockingVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var curBlockAll = false
    private var curApps: Set<String> = emptySet()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always become foreground promptly (we may be started from background).
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_UNBLOCK -> {
                stopBlocking()
                return START_NOT_STICKY
            }
            else -> {
                val blockAll = intent?.getBooleanExtra(EXTRA_BLOCK_ALL, false) ?: false
                val apps = intent?.getStringArrayExtra(EXTRA_APPS)?.toSet() ?: emptySet()
                applyConfig(blockAll, apps)
            }
        }
        // Re-deliver the last config if the service is restarted after a kill.
        return START_REDELIVER_INTENT
    }

    private fun applyConfig(blockAll: Boolean, apps: Set<String>) {
        if (!blockAll && apps.isEmpty()) {
            stopBlocking()
            return
        }
        // No change and already up: nothing to do.
        if (tunnel != null && blockAll == curBlockAll && apps == curApps) return

        closeTunnel()
        tunnel = establish(blockAll, apps)
        if (tunnel == null) {
            // Nothing could be blocked (e.g. none of the apps are installed) —
            // don't leave an empty service running.
            stopBlocking()
            return
        }
        curBlockAll = blockAll
        curApps = apps
    }

    private fun establish(blockAll: Boolean, apps: Set<String>): ParcelFileDescriptor? =
        runCatching {
            val b = Builder()
                .setSession("Morpheus")
                .addAddress("10.111.222.1", 24)
                .addRoute("0.0.0.0", 0)   // capture all IPv4
                .addRoute("::", 0)         // capture all IPv6
                .setBlocking(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)

            if (blockAll) {
                // Block everyone except ourselves so location + remote control
                // keep working while the child is offline.
                runCatching { b.addDisallowedApplication(packageName) }
            } else {
                var added = 0
                apps.forEach { pkg ->
                    runCatching { b.addAllowedApplication(pkg) }.onSuccess { added++ }
                }
                if (added == 0) return@runCatching null // avoid capturing the whole device
            }
            b.establish()
        }.getOrNull()

    private fun closeTunnel() {
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private fun stopBlocking() {
        closeTunnel()
        curBlockAll = false
        curApps = emptySet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // User revoked the VPN in system settings; the enforcer will re-arm.
        closeTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, MorpheusApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.managed_title))
            .setContentText(getString(R.string.blocked_text))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val NOTIF_ID = 4201
        const val ACTION_APPLY = "com.morpheus.family.action.APPLY"
        const val ACTION_UNBLOCK = "com.morpheus.family.action.UNBLOCK"
        const val EXTRA_BLOCK_ALL = "block_all"
        const val EXTRA_APPS = "apps"

        /**
         * Apply the desired blocking: [blockAll] cuts the whole device (except
         * Morpheus); otherwise only [apps] lose internet. No-op teardown if
         * neither is requested.
         */
        fun apply(context: Context, blockAll: Boolean, apps: Set<String>) {
            if (!blockAll && apps.isEmpty()) {
                unblock(context)
                return
            }
            val i = Intent(context, BlockingVpnService::class.java)
                .setAction(ACTION_APPLY)
                .putExtra(EXTRA_BLOCK_ALL, blockAll)
                .putExtra(EXTRA_APPS, apps.toTypedArray())
            context.startForegroundService(i)
        }

        fun unblock(context: Context) {
            val i = Intent(context, BlockingVpnService::class.java).setAction(ACTION_UNBLOCK)
            // startForegroundService so it's allowed from a background broadcast
            // (alarm/boot); the service calls startForeground immediately.
            context.startForegroundService(i)
        }
    }
}
