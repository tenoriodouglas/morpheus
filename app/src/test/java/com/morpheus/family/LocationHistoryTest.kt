package com.morpheus.family

import com.morpheus.family.data.LocationHistory
import com.morpheus.family.data.LocationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationHistoryTest {

    @Test
    fun encodeDecode_roundTrips() {
        val h = LocationHistory(
            listOf(LocationPoint(-23.5, -46.6, 1000L), LocationPoint(-23.6, -46.7, 2000L)),
        )
        assertEquals(h, LocationHistory.decode(LocationHistory.encode(h)))
    }

    @Test
    fun decode_blankOrGarbage_isEmpty() {
        assertTrue(LocationHistory.decode(null).points.isEmpty())
        assertTrue(LocationHistory.decode("").points.isEmpty())
        assertTrue(LocationHistory.decode("not json").points.isEmpty())
    }

    @Test
    fun appendPruned_dropsPointsOlderThan24h() {
        val now = 1_000_000_000L
        val h = LocationHistory().appendPruned(
            LocationPoint(1.0, 1.0, now - LocationHistory.WINDOW_MS - 1), now,
        )
        assertTrue(h.points.isEmpty()) // the appended point is already too old
        val fresh = h.appendPruned(LocationPoint(2.0, 2.0, now), now)
        assertEquals(1, fresh.points.size)
    }

    @Test
    fun appendPruned_capsAtMaxPoints() {
        val now = 1_000_000_000L
        var h = LocationHistory()
        for (i in 0 until LocationHistory.MAX_POINTS + 50) {
            h = h.appendPruned(LocationPoint(0.0, 0.0, now + i), now + i)
        }
        assertEquals(LocationHistory.MAX_POINTS, h.points.size)
        // Keeps the newest points.
        assertEquals(now + LocationHistory.MAX_POINTS + 49, h.points.last().at)
    }
}
