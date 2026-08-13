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
 * Blocks all internet access without root by standing up a local VPN whose
 * routes capture every packet and forward none — traffic simply has nowhere to
 * go. Started/stopped by [com.morpheus.family.schedule.ScheduleEnforcer].
 *
 * Because Android routes *all* app traffic through the active VPN, the child
 * cannot reach the network for as long as this interface is up, and there is no
 * per-app allowlist to bypass. Combined with Device Admin/Owner (which stops the
 * app being killed or uninstalled), the block cannot be casually defeated.
 */
class BlockingVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UNBLOCK -> {
                // Must call startForeground promptly since we may have been
                // launched via startForegroundService from the background.
                startForeground(NOTIF_ID, buildNotification())
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> block()
        }
        return START_STICKY
    }

    private fun block() {
        startForeground(NOTIF_ID, buildNotification())
        if (tunnel != null) return // already blocking
        tunnel = runCatching {
            Builder()
                .setSession("Morpheus")
                .addAddress("10.111.222.1", 24)
                .addRoute("0.0.0.0", 0)      // capture all IPv4
                .addRoute("::", 0)            // capture all IPv6
                .setBlocking(false)
                .also { b -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false) }
                .establish()
        }.getOrNull()
    }

    private fun teardown() {
        runCatching { tunnel?.close() }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        // User revoked the VPN in system settings; the enforcer will re-arm.
        teardown()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
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
        const val ACTION_BLOCK = "com.morpheus.family.action.BLOCK"
        const val ACTION_UNBLOCK = "com.morpheus.family.action.UNBLOCK"

        fun block(context: Context) {
            val i = Intent(context, BlockingVpnService::class.java).setAction(ACTION_BLOCK)
            context.startForegroundService(i)
        }

        fun unblock(context: Context) {
            val i = Intent(context, BlockingVpnService::class.java).setAction(ACTION_UNBLOCK)
            // startForegroundService so it is allowed even from a background
            // broadcast (alarm/boot); the service calls startForeground at once.
            context.startForegroundService(i)
        }
    }
}
