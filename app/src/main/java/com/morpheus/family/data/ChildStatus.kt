package com.morpheus.family.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Minutes spent in one app today. */
@Serializable
data class AppUsage(
    val pkg: String,
    val label: String = "",
    val minutes: Int = 0,
)

/** An app the child can't open right now, and why. */
@Serializable
data class BlockedApp(
    val pkg: String,
    val label: String = "",
    /** One of: schedule, limit, homework, budget. */
    val reason: String = "schedule",
)

/**
 * A transparent snapshot of what the child device is doing, uploaded
 * periodically for the parent's dashboard.
 *
 * Deliberately coarse: which app is in the foreground and for how long, daily
 * totals per app, and which apps are currently blocked. No message content, no
 * keystrokes, no screen/mic/camera capture — and the child device permanently
 * displays the "managed device" notice, so nothing here is hidden from the child.
 */
@Serializable
data class ChildStatus(
    val at: Long = 0L,
    /** Screen on and unlocked-ish (PowerManager.isInteractive). */
    val screenOn: Boolean = false,
    val currentApp: String = "",
    val currentAppLabel: String = "",
    /** When the current app came to the foreground (epoch millis). */
    val currentAppSince: Long = 0L,
    val totalMinutesToday: Int = 0,
    val topApps: List<AppUsage> = emptyList(),
    val blockedApps: List<BlockedApp> = emptyList(),
    val internetBlocked: Boolean = false,
    val homeworkActive: Boolean = false,
    /** Daily screen-time budget in minutes (0 = none), for a progress bar. */
    val budgetMinutes: Int = 0,
    /** False when the child hasn't granted Usage access, so the parent knows why it's empty. */
    val usageAccessGranted: Boolean = true,
) {
    /** Whether the child appears to be actively using the phone right now. */
    fun usingNow(nowMillis: Long): Boolean =
        screenOn && currentApp.isNotBlank() && nowMillis - at <= STALE_MS

    /** Whether this snapshot is too old to describe "now". */
    fun isStale(nowMillis: Long): Boolean = at <= 0L || nowMillis - at > STALE_MS

    companion object {
        /** A snapshot older than this no longer counts as live (ticker runs each minute). */
        const val STALE_MS = 5 * 60 * 1000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(status: ChildStatus): String = json.encodeToString(status)

        fun decode(raw: String?): ChildStatus {
            if (raw.isNullOrBlank()) return ChildStatus()
            return runCatching { json.decodeFromString<ChildStatus>(raw) }.getOrDefault(ChildStatus())
        }
    }
}
