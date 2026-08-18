package com.morpheus.family.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.morpheus.family.MorpheusApp
import com.morpheus.family.R
import com.morpheus.family.ui.MainActivity

/**
 * Incoming-call notification. Delivered by FCM (the Cloud Function pushes it when
 * the other side starts ringing) so the callee is alerted even with the app
 * closed. Tapping — or the full-screen intent on lockscreen — opens the app,
 * where the live signaling listener shows the in-app accept/decline screen.
 */
object CallNotifications {

    private const val INCOMING_ID = 6100

    fun postIncoming(context: Context, caller: String, video: Boolean) {
        val who = caller.ifBlank { "Alguém" }
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, MorpheusApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (video) "📹 Chamada de vídeo" else "📞 Chamada recebida")
            .setContentText("$who está chamando…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOngoing(false)
            // Rings for ~45s then clears itself if the call is missed.
            .setTimeoutAfter(45_000)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(INCOMING_ID, n) }
    }

    /** Clear the ring when the call is answered/cancelled/ended. */
    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(INCOMING_ID) }
    }
}
