package com.morpheus.family.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morpheus.family.schedule.ScheduleEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired at each block-window boundary (and hourly as a safety net). Re-syncs
 * trusted time and re-applies, so time drift/staleness is corrected regularly.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ScheduleEnforcer.syncAndApply(app)
            } finally {
                pending.finish()
            }
        }
    }
}
