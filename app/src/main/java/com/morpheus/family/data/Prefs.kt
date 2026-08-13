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
        val LAST_RELEASE = longPreferencesKey("last_release")

        // Parent side: the roster of managed children and each child's schedule.
        val CHILDREN = stringPreferencesKey("children")
        fun childSchedule(id: String) = stringPreferencesKey("sched_$id")
        fun childAppPolicy(id: String) = stringPreferencesKey("apppol_$id")

        // Child side: this device's own app policy (synced from the parent).
        val APP_POLICY = stringPreferencesKey("app_policy")

        // Parent side: optional PIN (stored hashed) gating the parent UI.
        val PARENT_PIN = stringPreferencesKey("parent_pin")

        // Parent side: URL that serves the signed release APK for Device Owner
        // QR provisioning.
        val DO_APK_URL = stringPreferencesKey("do_apk_url")

        // Child side: last known geofence membership (null until first fix).
        val GEOFENCE_INSIDE = booleanPreferencesKey("geofence_inside")

        // Persisted trusted-time anchor (survives process death, not reboot).
        val ANCHOR_UTC = longPreferencesKey("anchor_utc")
        val ANCHOR_ELAPSED = longPreferencesKey("anchor_elapsed")
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
    suspend fun pairedId(): String? = pairedIdFlow.first()

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

    val lastReleaseFlow: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_RELEASE] ?: 0L }
    suspend fun lastRelease(): Long = lastReleaseFlow.first()
    suspend fun setLastRelease(value: Long) =
        context.dataStore.edit { it[Keys.LAST_RELEASE] = value }

    // Child side: this device's own app policy.
    val appPolicyFlow: Flow<AppPolicy> = context.dataStore.data.map { AppPolicy.decode(it[Keys.APP_POLICY]) }
    suspend fun appPolicy(): AppPolicy = appPolicyFlow.first()
    suspend fun setAppPolicy(policy: AppPolicy) =
        context.dataStore.edit { it[Keys.APP_POLICY] = AppPolicy.encode(policy) }

    // Parent side: per-child app policy.
    fun childAppPolicyFlow(id: String): Flow<AppPolicy> =
        context.dataStore.data.map { AppPolicy.decode(it[Keys.childAppPolicy(id)]) }
    suspend fun childAppPolicy(id: String): AppPolicy = childAppPolicyFlow(id).first()
    suspend fun setChildAppPolicy(id: String, policy: AppPolicy) =
        context.dataStore.edit { it[Keys.childAppPolicy(id)] = AppPolicy.encode(policy) }

    // Parent PIN (hashed). Empty flow value = no PIN set.
    val parentPinFlow: Flow<String?> = context.dataStore.data.map { it[Keys.PARENT_PIN] }
    suspend fun setParentPin(hash: String?) = context.dataStore.edit {
        if (hash == null) it.remove(Keys.PARENT_PIN) else it[Keys.PARENT_PIN] = hash
    }

    // Device Owner provisioning APK URL (parent side).
    val deviceOwnerApkUrlFlow: Flow<String> = context.dataStore.data.map { it[Keys.DO_APK_URL] ?: "" }
    suspend fun setDeviceOwnerApkUrl(url: String) =
        context.dataStore.edit { it[Keys.DO_APK_URL] = url }

    // Child geofence membership (null until first fix).
    suspend fun geofenceInside(): Boolean? = context.dataStore.data.first()[Keys.GEOFENCE_INSIDE]
    suspend fun setGeofenceInside(inside: Boolean) =
        context.dataStore.edit { it[Keys.GEOFENCE_INSIDE] = inside }

    // Trusted-time anchor: (trustedUtcMillis, elapsedRealtimeAtAnchor). Null if unset.
    suspend fun timeAnchor(): Pair<Long, Long>? {
        val p = context.dataStore.data.first()
        val utc = p[Keys.ANCHOR_UTC] ?: 0L
        val elapsed = p[Keys.ANCHOR_ELAPSED] ?: 0L
        return if (utc > 0L) utc to elapsed else null
    }

    suspend fun setTimeAnchor(utcMillis: Long, elapsed: Long) = context.dataStore.edit {
        it[Keys.ANCHOR_UTC] = utcMillis
        it[Keys.ANCHOR_ELAPSED] = elapsed
    }

    // ---- Parent side: multiple managed children -------------------------------

    val childrenFlow: Flow<List<ChildRef>> = context.dataStore.data.map { p ->
        decodeChildren(p[Keys.CHILDREN])
    }

    suspend fun children(): List<ChildRef> = childrenFlow.first()

    /** Add or update a child by id (idempotent on id, refreshes the name). */
    suspend fun upsertChild(child: ChildRef) = context.dataStore.edit { p ->
        val current = decodeChildren(p[Keys.CHILDREN]).filterNot { it.id == child.id }
        p[Keys.CHILDREN] = encodeChildren(current + child)
    }

    suspend fun removeChild(id: String) = context.dataStore.edit { p ->
        val current = decodeChildren(p[Keys.CHILDREN]).filterNot { it.id == id }
        p[Keys.CHILDREN] = encodeChildren(current)
        p.remove(Keys.childSchedule(id))
    }

    fun childScheduleFlow(id: String): Flow<Schedule> = context.dataStore.data.map { p ->
        decodeSchedule(p[Keys.childSchedule(id)])
    }

    suspend fun childSchedule(id: String): Schedule = childScheduleFlow(id).first()

    suspend fun setChildSchedule(id: String, schedule: Schedule) =
        context.dataStore.edit { it[Keys.childSchedule(id)] = encodeSchedule(schedule) }

    companion object {
        // Children roster: one entry per line as "<id>|<name>".
        fun encodeChildren(list: List<ChildRef>): String =
            list.joinToString("\n") { c ->
                val safeName = c.name.replace("|", " ").replace("\n", " ").trim()
                "${c.id}|$safeName"
            }

        fun decodeChildren(raw: String?): List<ChildRef> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val i = line.indexOf('|')
                    if (i < 0) ChildRef(line.trim(), "")
                    else ChildRef(line.take(i).trim(), line.substring(i + 1).trim())
                }
                .toList()
        }

        // Compact, dependency-free wire format so the same string round-trips in
        // DataStore and Firestore and can be unit-tested on a plain JVM:
        //   "<enabled 0|1>|<manualBlockUntil>|<allowUntil>|<win>;<win>;..."
        //   win = "<start>,<end>,<day.day.day>"

        fun encodeSchedule(s: Schedule): String {
            val windows = s.windows.joinToString(";") { w ->
                "${w.startMinutes},${w.endMinutes},${w.days.sorted().joinToString(".")}"
            }
            return "${if (s.enabled) 1 else 0}|${s.manualBlockUntil}|${s.allowUntil}|$windows"
        }

        fun decodeSchedule(raw: String?): Schedule {
            if (raw.isNullOrBlank()) return Schedule()
            return runCatching {
                val parts = raw.split("|", limit = 4)
                val enabled = parts[0] == "1"
                val manual = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val allow = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val windows = (parts.getOrNull(3) ?: "")
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
                    allowUntil = allow,
                )
            }.getOrDefault(Schedule())
        }
    }
}
