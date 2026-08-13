package com.morpheus.family.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "morpheus")

/**
 * Single source of truth for persisted state: role, pairing identity and the
 * enforcement schedule. Backed by Jetpack DataStore.
 */
class Prefs(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val PAIR_CODE = stringPreferencesKey("pair_code")
        val PAIRED_ID = stringPreferencesKey("paired_id")
        val SCHEDULE = stringPreferencesKey("schedule_json")
        val ADMIN_ACK = booleanPreferencesKey("admin_ack")
        val LAST_APPLIED = longPreferencesKey("last_applied")
    }

    val modeFlow: Flow<AppMode> = context.dataStore.data.map { p ->
        runCatching { AppMode.valueOf(p[Keys.MODE] ?: AppMode.UNSET.name) }
            .getOrDefault(AppMode.UNSET)
    }

    val scheduleFlow: Flow<Schedule> = context.dataStore.data.map { p ->
        decodeSchedule(p[Keys.SCHEDULE])
    }

    val pairCodeFlow: Flow<String?> = context.dataStore.data.map { it[Keys.PAIR_CODE] }
    val pairedIdFlow: Flow<String?> = context.dataStore.data.map { it[Keys.PAIRED_ID] }

    suspend fun mode(): AppMode = modeFlow.first()
    suspend fun schedule(): Schedule = scheduleFlow.first()

    suspend fun setMode(mode: AppMode) =
        context.dataStore.edit { it[Keys.MODE] = mode.name }

    suspend fun setPairCode(code: String) =
        context.dataStore.edit { it[Keys.PAIR_CODE] = code }

    suspend fun setPairedId(id: String) =
        context.dataStore.edit { it[Keys.PAIRED_ID] = id }

    suspend fun setSchedule(schedule: Schedule) =
        context.dataStore.edit { it[Keys.SCHEDULE] = encodeSchedule(schedule) }

    suspend fun setAdminAcknowledged(value: Boolean) =
        context.dataStore.edit { it[Keys.ADMIN_ACK] = value }

    companion object {
        // Compact, dependency-free wire format so the same string round-trips in
        // DataStore and Firestore and can be unit-tested on a plain JVM:
        //   "<enabled 0|1>|<manualBlockUntil>|<win>;<win>;..."
        //   win = "<start>,<end>,<day.day.day>"

        fun encodeSchedule(s: Schedule): String {
            val windows = s.windows.joinToString(";") { w ->
                "${w.startMinutes},${w.endMinutes},${w.days.sorted().joinToString(".")}"
            }
            return "${if (s.enabled) 1 else 0}|${s.manualBlockUntil}|$windows"
        }

        fun decodeSchedule(raw: String?): Schedule {
            if (raw.isNullOrBlank()) return Schedule()
            return runCatching {
                val parts = raw.split("|", limit = 3)
                val enabled = parts[0] == "1"
                val manual = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val windows = (parts.getOrNull(2) ?: "")
                    .split(";")
                    .filter { it.isNotBlank() }
                    .map { seg ->
                        val f = seg.split(",")
                        val days = f.getOrNull(2)
                            ?.split(".")
                            ?.mapNotNull { it.toIntOrNull() }
                            ?.toSet()
                            ?.ifEmpty { BlockWindow.ALL_DAYS }
                            ?: BlockWindow.ALL_DAYS
                        BlockWindow(f[0].toInt(), f[1].toInt(), days)
                    }
                Schedule(
                    enabled = enabled,
                    windows = windows.ifEmpty { Schedule().windows },
                    manualBlockUntil = manual,
                )
            }.getOrDefault(Schedule())
        }
    }
}
