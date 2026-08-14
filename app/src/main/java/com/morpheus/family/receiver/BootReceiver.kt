package com.morpheus.family.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.service.GuardianService
import com.morpheus.family.work.GuardianWatchdogWorker
import kotlinx.coroutines.runBlocking

/**
 * Restarts enforcement after a reboot or app update so the schedule survives —
 * a child cannot escape the block simply by restarting the phone.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val mode = runBlocking { Prefs(app).mode() }
        if (mode == AppMode.CHILD) {
            GuardianService.start(app)
            GuardianWatchdogWorker.enqueue(app)
        }
    }
}
