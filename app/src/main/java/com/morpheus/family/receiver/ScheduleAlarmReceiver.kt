package com.morpheus.family.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morpheus.family.schedule.ScheduleEnforcer

/** Fired at each block-window boundary; re-applies and re-arms. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleEnforcer.apply(context.applicationContext)
    }
}
