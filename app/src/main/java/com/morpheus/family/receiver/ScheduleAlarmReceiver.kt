package com.morpheus.family.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morpheus.family.data.AppMode
import com.morpheus.family.data.Prefs
import com.morpheus.family.schedule.ScheduleEnforcer
import com.morpheus.family.service.GuardianService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired at each block-window boundary (and hourly as a safety net). Re-syncs
 * trusted time and re-applies, and — if this is a child device — resurrects the
 * guardian service in case it was killed, so enforcement self-heals.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ScheduleEnforcer.syncAndApply(app)
                if (Prefs(app).mode() == AppMode.CHILD) GuardianService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
