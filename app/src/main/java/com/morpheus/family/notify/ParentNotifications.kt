package com.morpheus.family.notify

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.morpheus.family.MorpheusApp
import com.morpheus.family.R
import com.morpheus.family.ui.MainActivity

/**
 * Parent-side notifications: an FCM data message (sent by the Cloud Function when
 * a child raises an SOS or asks for more time) is turned into a HIGH-importance,
 * dismissible system notification so the parent is alerted even when the app is
 * closed. Unlike the child's ongoing supervisor notice, these are one-shot and
 * swipe-away (setAutoCancel + not ongoing).
 */
object ParentNotifications {

    private const val ALERT_ID = 5300
    private const val REQUEST_ID = 5400

    /** SOS / tamper / geofence alert from the child. */
    fun postAlert(context: Context, childName: String, text: String) {
        post(context, ALERT_ID, "🆘 ${childName.ifBlank { "Filho" }}", text)
    }

    /** Extra-time / unlock request from the child. */
    fun postRequest(context: Context, childName: String, text: String) {
        post(context, REQUEST_ID, "🙋 ${childName.ifBlank { "Filho" }} pediu algo", text)
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(context, MorpheusApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
