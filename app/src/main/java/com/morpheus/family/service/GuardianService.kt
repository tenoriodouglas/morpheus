package com.morpheus.family.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.morpheus.family.MorpheusApp
import com.morpheus.family.R
import com.morpheus.family.admin.ProtectionManager
import com.morpheus.family.data.Prefs
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.schedule.ScheduleEnforcer
import com.morpheus.family.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Long-lived supervisor on the child device. Shows the permanent, transparent
 * "this device is managed" notification (required by Google Play policy for
 * monitoring apps) and re-applies the schedule whenever it is (re)started —
 * on boot, after updates, and when the parent pushes a new policy.
 */
class GuardianService : LifecycleService() {

    private var policyListener: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        startRemoteSync()
    }

    /** Subscribe to the parent's live policy updates (no-op without Firebase). */
    private fun startRemoteSync() {
        val ctx = applicationContext
        RemoteRepository.ensureSignedIn(ctx)
        lifecycleScope.launch {
            val prefs = Prefs(ctx)
            // Re-attach the Firestore listener whenever the paired id changes.
            prefs.pairedIdFlow.collect { pairId ->
                policyListener?.remove()
                policyListener = if (pairId.isNullOrBlank()) null else
                    RemoteRepository.listenPolicy(ctx, pairId) { policy ->
                        lifecycleScope.launch {
                            if (policy.releaseRequestedAt > prefs.lastRelease()) {
                                // Parent asked to remove protection: disable the
                                // schedule, remember the request, then tear down.
                                prefs.setLastRelease(policy.releaseRequestedAt)
                                prefs.setSchedule(policy.schedule.copy(enabled = false))
                                ProtectionManager.release(ctx)
                            } else {
                                prefs.setSchedule(policy.schedule)
                                ScheduleEnforcer.apply(ctx)
                            }
                        }
                    }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ScheduleEnforcer.apply(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        policyListener?.remove()
        policyListener = null
        super.onDestroy()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, MorpheusApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.managed_title))
            .setContentText(getString(R.string.managed_text))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val NOTIF_ID = 4200

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuardianService::class.java))
        }
    }
}
