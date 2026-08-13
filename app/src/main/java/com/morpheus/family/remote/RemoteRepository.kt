package com.morpheus.family.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule

/**
 * Real-time parent -> child channel over Cloud Firestore.
 *
 * The whole layer is optional: if the app was built without a
 * `google-services.json`, [available] returns false and every method is a
 * no-op, so the app still installs and enforces a locally-set schedule. When
 * Firebase *is* configured, the parent [pushPolicy] writes to a shared document
 * keyed by the pairing code and the child [listenPolicy] receives updates live —
 * no custom backend required.
 */
object RemoteRepository {

    fun available(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    private fun doc(pairId: String) =
        Firebase.firestore.collection("families").document(pairId)

    /** Parent: publish the current policy for the paired child to pick up. */
    fun pushPolicy(context: Context, pairId: String, schedule: Schedule) {
        if (!available(context) || pairId.isBlank()) return
        val data = mapOf(
            "scheduleJson" to Prefs.encodeSchedule(schedule),
            "manualBlockUntil" to schedule.manualBlockUntil,
        )
        doc(pairId).set(data, SetOptions.merge())
    }

    /**
     * Child: subscribe to policy changes. Returns a registration the caller
     * must [ListenerRegistration.remove] when done, or null if unavailable.
     */
    fun listenPolicy(
        context: Context,
        pairId: String,
        onSchedule: (Schedule) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val json = snap.getString("scheduleJson")
            onSchedule(Prefs.decodeSchedule(json))
        }
    }
}
