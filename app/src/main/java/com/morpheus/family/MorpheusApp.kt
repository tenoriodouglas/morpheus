package com.morpheus.family

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.morpheus.family.remote.RemoteRepository

class MorpheusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Sign in early so Firestore requests are authenticated (no-op if
        // Firebase isn't configured in this build).
        RemoteRepository.ensureSignedIn(this)
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
    }

    companion object {
        const val CHANNEL_STATUS = "morpheus_status"
    }
}
