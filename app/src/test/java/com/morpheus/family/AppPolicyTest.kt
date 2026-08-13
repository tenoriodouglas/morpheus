package com.morpheus.family

import com.morpheus.family.data.AppPolicy
import com.morpheus.family.data.AppRule
import com.morpheus.family.data.BlockWindowData
import com.morpheus.family.data.DeviceRestrictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AppPolicyTest {

    private fun at(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(2026, Calendar.AUGUST, 13, hour, minute) // a Thursday
        }.timeInMillis

    @Test
    fun encodeDecode_roundTrips() {
        val policy = AppPolicy(
            dailyScreenBudgetMinutes = 180,
            apps = listOf(
                AppRule("com.google.android.youtube", "YouTube",
                    windows = listOf(BlockWindowData(22 * 60, 6 * 60 + 30)),
                    dailyLimitMinutes = 60),
                AppRule("com.zhiliaoapp.musically", "TikTok"),
            ),
            restrictions = DeviceRestrictions(blockAppInstall = true, lockDateTime = true),
            bonusUntil = 999L,
        )
        val decoded = AppPolicy.decode(AppPolicy.encode(policy))
        assertEquals(policy, decoded)
        assertEquals(2, decoded.apps.size)
        assertEquals(60, decoded.apps[0].dailyLimitMinutes)
        assertTrue(decoded.restrictions.blockAppInstall)
    }

    @Test
    fun decode_emptyOrGarbageIsSafeDefault() {
        assertEquals(AppPolicy(), AppPolicy.decode(null))
        assertEquals(AppPolicy(), AppPolicy.decode(""))
        assertEquals(AppPolicy(), AppPolicy.decode("not json"))
    }

    @Test
    fun appBlockedByWindow_respectsWindowAndBonus() {
        val policy = AppPolicy(
            apps = listOf(
                AppRule("com.x", "X", windows = listOf(BlockWindowData(22 * 60, 6 * 60 + 30))),
            ),
        )
        assertTrue(policy.isAppBlockedByWindow("com.x", at(23, 0)))
        assertFalse(policy.isAppBlockedByWindow("com.x", at(15, 0)))
        assertFalse(policy.isAppBlockedByWindow("com.unknown", at(23, 0)))

        // Bonus time overrides the block.
        val withBonus = policy.copy(bonusUntil = at(23, 0) + 60_000)
        assertFalse(withBonus.isAppBlockedByWindow("com.x", at(23, 0)))
    }
}
