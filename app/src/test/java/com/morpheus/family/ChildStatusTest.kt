package com.morpheus.family

import com.morpheus.family.data.AppUsage
import com.morpheus.family.data.BlockedApp
import com.morpheus.family.data.ChildStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildStatusTest {

    private val sample = ChildStatus(
        at = 1_000_000L,
        screenOn = true,
        currentApp = "com.google.android.youtube",
        currentAppLabel = "YouTube",
        currentAppSince = 900_000L,
        totalMinutesToday = 137,
        topApps = listOf(
            AppUsage("com.google.android.youtube", "YouTube", 90),
            AppUsage("com.whatsapp", "WhatsApp", 47),
        ),
        blockedApps = listOf(BlockedApp("com.roblox.client", "Roblox", "schedule")),
        internetBlocked = true,
        homeworkActive = false,
        budgetMinutes = 180,
        usageAccessGranted = true,
    )

    @Test
    fun encodeDecode_roundTrips() {
        assertEquals(sample, ChildStatus.decode(ChildStatus.encode(sample)))
    }

    @Test
    fun decode_blankOrGarbage_fallsBackToEmpty() {
        assertEquals(ChildStatus(), ChildStatus.decode(null))
        assertEquals(ChildStatus(), ChildStatus.decode(""))
        assertEquals(ChildStatus(), ChildStatus.decode("not json"))
    }

    @Test
    fun decode_toleratesUnknownFields() {
        val withExtra = """{"at":5,"screenOn":true,"somethingNew":42}"""
        val decoded = ChildStatus.decode(withExtra)
        assertEquals(5L, decoded.at)
        assertTrue(decoded.screenOn)
    }

    @Test
    fun usingNow_requiresScreenOnForegroundAppAndFreshSnapshot() {
        val justAfter = sample.at + 1000
        assertTrue(sample.usingNow(justAfter))

        // Screen off, or no foreground app => not using.
        assertFalse(sample.copy(screenOn = false).usingNow(justAfter))
        assertFalse(sample.copy(currentApp = "").usingNow(justAfter))

        // A stale snapshot must never be reported as "using now".
        assertFalse(sample.usingNow(sample.at + ChildStatus.STALE_MS + 1))
    }

    @Test
    fun isStale_flipsAfterTheStaleWindow() {
        assertFalse(sample.isStale(sample.at + ChildStatus.STALE_MS - 1))
        assertTrue(sample.isStale(sample.at + ChildStatus.STALE_MS + 1))
        // A never-reported status counts as stale.
        assertTrue(ChildStatus().isStale(System.currentTimeMillis()))
    }
}
