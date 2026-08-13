package com.morpheus.family.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.morpheus.family.data.Prefs
import com.morpheus.family.data.Schedule

/**
 * Real-time parent -> child channel over Cloud Firestore.
 *
 * Optional: without a `google-services.json`, [available] returns false and
 * every method is a no-op, so the app still installs and enforces a
 * locally-set schedule. With Firebase configured, the parent writes to a shared
 * document keyed by the child's pairing code and the child receives updates live.
 *
 * Anonymous Auth is used so Firestore security rules can require an
 * authenticated caller (see firestore.rules).
 */
object RemoteRepository {

    fun available(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty()

    /** Best-effort anonymous sign-in so secured rules accept our requests. */
    fun ensureSignedIn(context: Context) {
        if (!available(context)) return
        if (Firebase.auth.currentUser == null) {
            runCatching { Firebase.auth.signInAnonymously() }
        }
    }

    private fun doc(pairId: String) =
        Firebase.firestore.collection("families").document(pairId)

    /** Parent: publish the current policy for the paired child to pick up. */
    fun pushPolicy(context: Context, pairId: String, schedule: Schedule) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        val data = mapOf(
            "scheduleJson" to Prefs.encodeSchedule(schedule),
            "manualBlockUntil" to schedule.manualBlockUntil,
        )
        doc(pairId).set(data, SetOptions.merge())
    }

    /**
     * Parent: ask the child to release all protection so it can be uninstalled.
     * Stamped with [nowMillis] (pass System.currentTimeMillis()); the child acts
     * only when this is newer than the last release it handled.
     */
    fun requestRelease(context: Context, pairId: String, nowMillis: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("releaseRequestedAt" to nowMillis), SetOptions.merge())
    }

    /**
     * Child: subscribe to policy changes. Returns a registration the caller
     * must [ListenerRegistration.remove] when done, or null if unavailable.
     */
    fun listenPolicy(
        context: Context,
        pairId: String,
        onPolicy: (RemotePolicy) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val schedule = Prefs.decodeSchedule(snap.getString("scheduleJson"))
            val releaseAt = snap.getLong("releaseRequestedAt") ?: 0L
            onPolicy(RemotePolicy(schedule, releaseAt))
        }
    }
}
