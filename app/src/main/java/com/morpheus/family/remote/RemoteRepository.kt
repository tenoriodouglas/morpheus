package com.morpheus.family.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.morpheus.family.data.AppPolicy
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

    /**
     * Claim membership of the family doc (trust-on-first-use): both the child
     * and the parent add their anonymous uid, so security rules can later limit
     * access to those members only.
     */
    fun joinMembership(context: Context, pairId: String) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        val uid = Firebase.auth.currentUser?.uid ?: return
        doc(pairId).set(mapOf("members" to FieldValue.arrayUnion(uid)), SetOptions.merge())
    }

    /** Child: heartbeat so the parent can see the device is online. */
    fun reportHeartbeat(context: Context, pairId: String, at: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("lastSeen" to at), SetOptions.merge())
    }

    /** Parent: observe the child's last-seen heartbeat. */
    fun listenHeartbeat(
        context: Context,
        pairId: String,
        onSeen: (Long) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val at = snap.getLong("lastSeen") ?: 0L
            if (at > 0L) onSeen(at)
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

    /** Parent: publish the per-app policy (rules, budgets, restrictions). */
    fun pushAppPolicy(context: Context, pairId: String, appPolicy: AppPolicy) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("appPolicyJson" to AppPolicy.encode(appPolicy)), SetOptions.merge())
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

    /** Child: report a tamper/alert event to the parent. */
    fun reportAlert(context: Context, pairId: String, type: String, nowMillis: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("alert" to type, "alertAt" to nowMillis), SetOptions.merge())
    }

    /** Parent: listen for the child's latest alert. */
    fun listenAlert(
        context: Context,
        pairId: String,
        onAlert: (type: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val type = snap.getString("alert") ?: return@addSnapshotListener
            val at = snap.getLong("alertAt") ?: 0L
            onAlert(type, at)
        }
    }

    // ---- Child -> parent requests (extra time / unlock) -----------------------

    fun reportRequest(context: Context, pairId: String, type: String, note: String, at: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(
            mapOf("reqType" to type, "reqNote" to note, "reqAt" to at),
            SetOptions.merge(),
        )
    }

    /** Parent: clear a handled request so its banner disappears. */
    fun clearRequest(context: Context, pairId: String) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("reqAt" to 0L), SetOptions.merge())
    }

    fun listenRequest(
        context: Context,
        pairId: String,
        onRequest: (type: String, note: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val at = snap.getLong("reqAt") ?: 0L
            if (at <= 0L) return@addSnapshotListener
            onRequest(snap.getString("reqType") ?: "extra", snap.getString("reqNote") ?: "", at)
        }
    }

    // ---- Child -> parent location & usage -------------------------------------

    fun reportLocation(context: Context, pairId: String, lat: Double, lng: Double, at: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("lat" to lat, "lng" to lng, "locAt" to at), SetOptions.merge())
    }

    fun listenLocation(
        context: Context,
        pairId: String,
        onLocation: (lat: Double, lng: Double, at: Long) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val at = snap.getLong("locAt") ?: 0L
            if (at <= 0L) return@addSnapshotListener
            onLocation(snap.getDouble("lat") ?: 0.0, snap.getDouble("lng") ?: 0.0, at)
        }
    }

    fun reportUsage(context: Context, pairId: String, usageJson: String, at: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("usageJson" to usageJson, "usageAt" to at), SetOptions.merge())
    }

    fun listenUsage(
        context: Context,
        pairId: String,
        onUsage: (usageJson: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val at = snap.getLong("usageAt") ?: 0L
            if (at <= 0L) return@addSnapshotListener
            onUsage(snap.getString("usageJson") ?: "", at)
        }
    }

    // ---- Child -> parent live status (screen time, current app, blocks) -------

    /** Child: publish the periodic transparency snapshot for the parent's dashboard. */
    fun reportStatus(context: Context, pairId: String, statusJson: String, at: Long) {
        if (!available(context) || pairId.isBlank()) return
        ensureSignedIn(context)
        doc(pairId).set(mapOf("statusJson" to statusJson, "statusAt" to at), SetOptions.merge())
    }

    /** Parent: observe the child's live status snapshot. */
    fun listenStatus(
        context: Context,
        pairId: String,
        onStatus: (statusJson: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        ensureSignedIn(context)
        return doc(pairId).addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            val at = snap.getLong("statusAt") ?: 0L
            if (at <= 0L) return@addSnapshotListener
            onStatus(snap.getString("statusJson") ?: "", at)
        }
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
            val appPolicy = AppPolicy.decode(snap.getString("appPolicyJson"))
            val releaseAt = snap.getLong("releaseRequestedAt") ?: 0L
            onPolicy(RemotePolicy(schedule, appPolicy, releaseAt))
        }
    }
}
