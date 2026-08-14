package com.morpheus.family.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.morpheus.family.MorpheusApp
import com.morpheus.family.R
import com.morpheus.family.ui.MainActivity

/**
 * Child-side notifications, so the child always sees — transparently — what is
 * being blocked right now. Two surfaces:
 *  - the permanent, LOW-importance "managed device" notice, whose text is kept in
 *    sync with the live block state (foreground-service notification, id [ONGOING_ID]);
 *  - a HIGH-importance one-shot posted only when the block state actually flips,
 *    so the child is told the moment internet/apps are blocked or freed.
 */
object ChildNotifications {

    /** Must match the id GuardianService uses for startForeground. */
    const val ONGOING_ID = 4200
    private const val ALERT_ID = 4300

    /** Builds the ongoing supervisor notification with the given live [text]. */
    fun ongoing(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, MorpheusApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.managed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(contentIntent(context))
            .build()

    /** Refresh the persistent notice so it reflects the current block state. */
    fun updateOngoing(context: Context, text: String) {
        runCatching {
            NotificationManagerCompat.from(context).notify(ONGOING_ID, ongoing(context, text))
        }
    }

    /**
     * Remove both the persistent notice and any lingering alert — used when the
     * parent releases protection so no orphaned notification survives the service.
     */
    fun cancelAll(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).apply {
                cancel(ONGOING_ID)
                cancel(ALERT_ID)
            }
        }
    }

    /** Post a one-shot alert when the block state changes. */
    fun postTransition(context: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(context, MorpheusApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ALERT_ID, n) }
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
