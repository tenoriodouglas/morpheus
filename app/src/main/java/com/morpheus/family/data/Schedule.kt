package com.morpheus.family.data

import java.util.Calendar

/**
 * A recurring window in which internet access is blocked on the child device.
 *
 * Times are minutes-from-midnight in the device's local time. A window may wrap
 * across midnight (e.g. 22:00 -> 06:00), in which case [startMinutes] >
 * [endMinutes]. [days] holds [Calendar] day-of-week constants
 * ([Calendar.SUNDAY]..[Calendar.SATURDAY]) on which the window's *start* applies.
 */
data class BlockWindow(
    val startMinutes: Int,
    val endMinutes: Int,
    val days: Set<Int> = ALL_DAYS,
) {
    fun label(): String = "%02d:%02d–%02d:%02d".format(
        startMinutes / 60, startMinutes % 60,
        endMinutes / 60, endMinutes % 60,
    )

    companion object {
        val ALL_DAYS = setOf(
            Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY,
        )
    }
}

/**
 * The full enforcement policy pushed from the parent to the child.
 *
 * [enabled] is the master switch. [manualBlockUntil] is an epoch-millis instant
 * for an immediate "block now" from the parent (0 = no manual block).
 */
data class Schedule(
    val enabled: Boolean = true,
    val windows: List<BlockWindow> = listOf(
        // Sensible default: nightly 22:00 -> 06:30 every day.
        BlockWindow(startMinutes = 22 * 60, endMinutes = 6 * 60 + 30),
    ),
    val manualBlockUntil: Long = 0L,
    // Grants "extra time": while allowUntil is in the future, blocking is
    // suspended (used to approve a child's request), unless the parent has an
    // explicit manual block active (which wins).
    val allowUntil: Long = 0L,
) {
    /** Whether internet should be blocked at [nowMillis] under this policy. */
    fun isBlockedAt(nowMillis: Long): Boolean {
        if (!enabled) return false
        if (manualBlockUntil > nowMillis) return true
        if (allowUntil > nowMillis) return false

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = cal.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (today == Calendar.SUNDAY) Calendar.SATURDAY else today - 1

        for (w in windows) {
            if (w.startMinutes <= w.endMinutes) {
                // Same-day window.
                if (today in w.days && nowMinutes >= w.startMinutes && nowMinutes < w.endMinutes) {
                    return true
                }
            } else {
                // Wraps past midnight: split into evening + morning halves.
                if (today in w.days && nowMinutes >= w.startMinutes) return true
                if (yesterday in w.days && nowMinutes < w.endMinutes) return true
            }
        }
        return false
    }
}
