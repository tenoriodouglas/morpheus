package com.morpheus.family.remote

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.notify.CallNotifications
import com.morpheus.family.notify.ChildNotifications
import com.morpheus.family.notify.ParentNotifications
import com.morpheus.family.schedule.ScheduleEnforcer
import kotlinx.coroutines.runBlocking

/**
 * Optional fast-path wake-up. A data message from the parent (e.g. an immediate
 * "block now") nudges the child to re-read policy and re-apply enforcement
 * without waiting for the next scheduled boundary.
 */
class MorpheusMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // Incoming call from the peer (works even with the app closed): ring, and
        // opening the app shows the in-app accept/decline screen.
        when (message.data["notifyType"]) {
            "incoming_call" -> {
                CallNotifications.postIncoming(
                    applicationContext,
                    message.data["caller"] ?: "",
                    message.data["video"] == "true",
                )
                return
            }
            "call_cancelled" -> {
                CallNotifications.cancel(applicationContext)
                return
            }
            "req_response" -> {
                val approved = message.data["approved"] == "true"
                ChildNotifications.postTransition(
                    applicationContext,
                    "Resposta do responsável",
                    if (approved) "✅ Pedido aprovado! Você ganhou mais tempo."
                    else "❌ Pedido de mais tempo recusado.",
                )
                return
            }
            "refresh_status" -> {
                // Parent is watching: publish a fresh snapshot now (runs on FCM's
                // background thread, so blocking briefly is fine — no ANR).
                runBlocking { runCatching { ScheduleEnforcer.uploadStatus(applicationContext) } }
                return
            }
        }

        // Parent-bound alert (SOS / request) from the Cloud Function: show it as a
        // dismissible system notification even if the parent app is closed.
        when (message.data["notifyType"]) {
            "alert" -> {
                ParentNotifications.postAlert(
                    applicationContext,
                    message.data["childName"] ?: "",
                    message.data["text"] ?: "🆘 Pedido de ajuda!",
                )
                return
            }
            "request" -> {
                ParentNotifications.postRequest(
                    applicationContext,
                    message.data["childName"] ?: "",
                    message.data["text"] ?: "Pediu mais tempo",
                )
                return
            }
        }

        // Otherwise it's a child wake-up (immediate block/unblock).
        val block = message.data["manualBlockUntil"]?.toLongOrNull()
        val unblock = message.data["manualUnblockUntil"]?.toLongOrNull()
        val setAt = message.data["manualSetAt"]?.toLongOrNull()
        if (block != null || unblock != null) {
            runBlocking {
                val prefs = Prefs(applicationContext)
                val current = prefs.schedule()
                prefs.setSchedule(
                    current.copy(
                        manualBlockUntil = block ?: current.manualBlockUntil,
                        manualUnblockUntil = unblock ?: current.manualUnblockUntil,
                        manualSetAt = setAt ?: current.manualSetAt,
                    ),
                )
            }
        }
        ScheduleEnforcer.apply(applicationContext)
    }

    override fun onNewToken(token: String) {
        // Publish the token so the optional Cloud Function can push high-priority
        // messages: to the child (immediate block/unblock through Doze), or back
        // to the parent (SOS/request alerts while its app is closed).
        runBlocking {
            val prefs = Prefs(applicationContext)
            when (prefs.mode()) {
                AppMode.CHILD -> {
                    val pairId = prefs.pairedId() ?: return@runBlocking
                    RemoteRepository.reportFcmToken(applicationContext, pairId, token)
                }
                AppMode.PARENT -> {
                    RemoteRepository.uploadParentFcmToken(
                        applicationContext,
                        prefs.children().associate { it.id to it.name },
                    )
                }
                else -> {}
            }
        }
    }
}
