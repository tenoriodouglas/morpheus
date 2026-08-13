package com.morpheus.family.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A rule for a single app on the child device.
 *
 * The app is blocked when the current trusted time falls in any of [windows],
 * OR when today's usage has reached [dailyLimitMinutes] (0 = no limit).
 */
@Serializable
data class AppRule(
    val packageName: String,
    val label: String = "",
    val windows: List<BlockWindowData> = emptyList(),
    val dailyLimitMinutes: Int = 0,
)

/** Serializable twin of [BlockWindow] (kept separate to avoid touching the tested type). */
@Serializable
data class BlockWindowData(
    val startMinutes: Int,
    val endMinutes: Int,
    val days: Set<Int> = BlockWindow.ALL_DAYS,
) {
    fun toDomain() = BlockWindow(startMinutes, endMinutes, days)
    fun label(): String = toDomain().label()
}

/** Device Owner-only restrictions applied on the child device. */
@Serializable
data class DeviceRestrictions(
    val blockAppInstall: Boolean = false,
    val lockDateTime: Boolean = false,
    val requireAutoTime: Boolean = true,
)

/**
 * Everything beyond the internet schedule: per-app rules, a total daily
 * screen-time budget, device restrictions and a granted "bonus time" instant.
 * Serialized as JSON so it round-trips through DataStore and Firestore and is
 * unit-testable on a plain JVM.
 */
@Serializable
data class AppPolicy(
    val dailyScreenBudgetMinutes: Int = 0,
    val apps: List<AppRule> = emptyList(),
    val restrictions: DeviceRestrictions = DeviceRestrictions(),
    val bonusUntil: Long = 0L,
) {
    /** Whether [packageName] should be blocked at [nowMillis] by its windows. */
    fun isAppBlockedByWindow(packageName: String, nowMillis: Long): Boolean {
        if (bonusUntil > nowMillis) return false
        val rule = apps.firstOrNull { it.packageName == packageName } ?: return false
        return rule.windows.any { Schedule(windows = listOf(it.toDomain())).isBlockedAt(nowMillis) }
    }

    fun ruleFor(packageName: String): AppRule? = apps.firstOrNull { it.packageName == packageName }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(policy: AppPolicy): String = json.encodeToString(policy)

        fun decode(raw: String?): AppPolicy {
            if (raw.isNullOrBlank()) return AppPolicy()
            return runCatching { json.decodeFromString<AppPolicy>(raw) }.getOrDefault(AppPolicy())
        }
    }
}
