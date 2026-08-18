package com.morpheus.family.remote

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.schedule.ScheduleEnforcer
import kotlinx.coroutines.runBlocking

/**
 * Optional fast-path wake-up. A data message from the parent (e.g. an immediate
 * "block now") nudges the child to re-read policy and re-apply enforcement
 * without waiting for the next scheduled boundary.
 */
class MorpheusMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
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
        // Publish the child's token so the optional Cloud Function can push a
        // high-priority wake-up (immediate block) that punches through Doze.
        runBlocking {
            val prefs = Prefs(applicationContext)
            if (prefs.mode() == AppMode.CHILD) {
                val pairId = prefs.pairedId() ?: return@runBlocking
                RemoteRepository.reportFcmToken(applicationContext, pairId, token)
            }
        }
    }
}
