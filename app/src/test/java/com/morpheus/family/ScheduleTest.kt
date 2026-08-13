package com.morpheus.family

import com.morpheus.family.data.BlockWindow
import com.morpheus.family.data.ChildRef
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    @Test
    fun nightlyWindow_blocksLateEveningAndEarlyMorning() {
        val s = Schedule(windows = listOf(BlockWindow(22 * 60, 6 * 60 + 30)))
        // 2026-08-13 is a Thursday.
        assertTrue(s.isBlockedAt(at(2026, Calendar.AUGUST, 13, 23, 0)))   // late evening
        assertTrue(s.isBlockedAt(at(2026, Calendar.AUGUST, 14, 5, 0)))    // early morning next day
        assertFalse(s.isBlockedAt(at(2026, Calendar.AUGUST, 13, 15, 0)))  // afternoon
        assertFalse(s.isBlockedAt(at(2026, Calendar.AUGUST, 14, 7, 0)))   // after wake time
    }

    @Test
    fun sameDayWindow_respectsBoundaries() {
        val s = Schedule(windows = listOf(BlockWindow(13 * 60, 14 * 60)))
        assertTrue(s.isBlockedAt(at(2026, Calendar.AUGUST, 13, 13, 30)))
        assertFalse(s.isBlockedAt(at(2026, Calendar.AUGUST, 13, 14, 0)))  // end is exclusive
        assertFalse(s.isBlockedAt(at(2026, Calendar.AUGUST, 13, 12, 59)))
    }

    @Test
    fun disabled_neverBlocks() {
        val s = Schedule(enabled = false, windows = listOf(BlockWindow(0, 1439)))
        assertFalse(s.isBlockedAt(at(2026, Calendar.AUGUST, 13, 3, 0)))
    }

    @Test
    fun manualBlock_overridesSchedule() {
        val now = at(2026, Calendar.AUGUST, 13, 15, 0)
        val s = Schedule(windows = emptyList(), manualBlockUntil = now + 60_000)
        assertTrue(s.isBlockedAt(now))
        assertFalse(s.isBlockedAt(now + 120_000))
    }

    @Test
    fun allowUntil_grantsExtraTimeButManualBlockWins() {
        val night = listOf(BlockWindow(22 * 60, 6 * 60 + 30))
        val now = at(2026, Calendar.AUGUST, 13, 23, 0) // normally blocked
        // allowUntil suspends the block...
        val allowed = Schedule(windows = night, allowUntil = now + 60_000)
        assertFalse(allowed.isBlockedAt(now))
        assertTrue(allowed.isBlockedAt(now + 120_000)) // extra time expired
        // ...unless an explicit manual block is active (it wins).
        val blocked = allowed.copy(manualBlockUntil = now + 60_000)
        assertTrue(blocked.isBlockedAt(now))
    }

    @Test
    fun children_roundTripAndUpsertSemantics() {
        val list = listOf(
            ChildRef("ABC123", "João"),
            ChildRef("XYZ789", "Maria | Ana"), // pipe must be sanitized
        )
        val decoded = Prefs.decodeChildren(Prefs.encodeChildren(list))
        assertEquals(2, decoded.size)
        assertEquals("ABC123", decoded[0].id)
        assertEquals("João", decoded[0].name)
        assertFalse(decoded[1].name.contains("|")) // separator stripped
        assertEquals("XYZ789", decoded[1].id)
    }

    @Test
    fun children_emptyDecodesToEmptyList() {
        assertTrue(Prefs.decodeChildren(null).isEmpty())
        assertTrue(Prefs.decodeChildren("").isEmpty())
    }

    @Test
    fun encodeDecode_roundTrips() {
        val s = Schedule(
            enabled = true,
            windows = listOf(
                BlockWindow(22 * 60, 6 * 60 + 30, setOf(Calendar.MONDAY, Calendar.FRIDAY)),
                BlockWindow(13 * 60, 14 * 60),
            ),
            manualBlockUntil = 123456789L,
        )
        val decoded = Prefs.decodeSchedule(Prefs.encodeSchedule(s))
        assertEquals(s.enabled, decoded.enabled)
        assertEquals(s.manualBlockUntil, decoded.manualBlockUntil)
        assertEquals(s.windows.size, decoded.windows.size)
        assertEquals(s.windows[0].days, decoded.windows[0].days)
        assertEquals(s.windows[0].startMinutes, decoded.windows[0].startMinutes)
    }
}
