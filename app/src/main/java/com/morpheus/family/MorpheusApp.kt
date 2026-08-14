package com.morpheus.family

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.morpheus.family.remote.RemoteRepository
import com.morpheus.family.work.GuardianWatchdogWorker

class MorpheusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Sign in early so Firestore requests are authenticated (no-op if
        // Firebase isn't configured in this build).
        RemoteRepository.ensureSignedIn(this)
        // Keep the child under enforcement even after the app is closed.
        GuardianWatchdogWorker.enqueue(this)
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val status = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_status_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(status)

        // Higher-importance channel so block/unblock transitions actually notify
        // the child (the ongoing status notice is silent/LOW).
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_alerts_desc)
        }
        nm.createNotificationChannel(alerts)
    }

    companion object {
        const val CHANNEL_STATUS = "morpheus_status"
        const val CHANNEL_ALERTS = "morpheus_alerts"
    }
}
