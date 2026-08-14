package com.morpheus.family.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One recorded position: latitude, longitude and the epoch-millis it was taken. */
@Serializable
data class LocationPoint(val lat: Double, val lng: Double, val at: Long)

/**
 * The child's recent trail, used to draw the last-24h route on the parent's map.
 *
 * Self-bounding: [appendPruned] drops points older than 24h and hard-caps the
 * total, so the serialized JSON stays tiny (~40 KB at the cap) and never
 * threatens the Firestore 1 MB document limit it shares with the other fields.
 */
@Serializable
data class LocationHistory(val points: List<LocationPoint> = emptyList()) {

    /** Add [p], drop points older than 24h, and keep at most [MAX_POINTS]. */
    fun appendPruned(p: LocationPoint, nowMillis: Long): LocationHistory =
        LocationHistory(
            (points + p)
                .filter { it.at >= nowMillis - WINDOW_MS }
                .takeLast(MAX_POINTS),
        )

    companion object {
        const val WINDOW_MS = 24L * 60 * 60 * 1000
        const val MAX_POINTS = 500

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(history: LocationHistory): String = json.encodeToString(history)

        fun decode(raw: String?): LocationHistory {
            if (raw.isNullOrBlank()) return LocationHistory()
            return runCatching { json.decodeFromString<LocationHistory>(raw) }
                .getOrDefault(LocationHistory())
        }
    }
}
