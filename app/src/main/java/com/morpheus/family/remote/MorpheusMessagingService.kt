package com.morpheus.family.remote

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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
        val manualUntil = message.data["manualBlockUntil"]?.toLongOrNull()
        if (manualUntil != null) {
            runBlocking {
                val prefs = Prefs(applicationContext)
                val current = prefs.schedule()
                prefs.setSchedule(current.copy(manualBlockUntil = manualUntil))
            }
        }
        ScheduleEnforcer.apply(applicationContext)
    }

    override fun onNewToken(token: String) {
        // The child's token can be registered for direct pushes if a backend
        // is added later; not required for Firestore real-time sync.
    }
}
