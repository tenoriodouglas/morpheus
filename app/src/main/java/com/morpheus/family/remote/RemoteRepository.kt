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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    /** Background scope for writes that must wait for anonymous sign-in first. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Anonymous sign-in, awaited.
     *
     * Security rules require `request.auth != null`, so every read and write has
     * to happen *after* sign-in completes. Firing sign-in and continuing (the
     * previous behaviour) made the first operations after a cold start fail with
     * permission-denied and vanish silently.
     */
    private suspend fun awaitSignIn(): Boolean {
        Firebase.auth.currentUser?.let { return true }
        return runCatching { Firebase.auth.signInAnonymously().await() }.isSuccess &&
            Firebase.auth.currentUser != null
    }

    /** Kick off sign-in early (e.g. from the guardian service) so later calls are instant. */
    fun ensureSignedIn(context: Context) {
        if (!available(context)) return
        scope.launch { awaitSignIn() }
    }

    /** Merge [data] into the family doc once authenticated. Fire-and-forget. */
    private fun write(context: Context, pairId: String, data: Map<String, Any>) {
        if (!available(context) || pairId.isBlank()) return
        scope.launch {
            if (awaitSignIn()) runCatching { doc(pairId).set(data, SetOptions.merge()) }
        }
    }

    /**
     * Attach a snapshot listener once authenticated, returning a registration
     * that works whether or not sign-in has finished yet.
     */
    private fun listen(
        context: Context,
        pairId: String,
        onSnapshot: (com.google.firebase.firestore.DocumentSnapshot) -> Unit,
    ): ListenerRegistration? {
        if (!available(context) || pairId.isBlank()) return null
        var inner: ListenerRegistration? = null
        var cancelled = false
        scope.launch {
            if (!awaitSignIn() || cancelled) return@launch
            val reg = doc(pairId).addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                onSnapshot(snap)
            }
            if (cancelled) reg.remove() else inner = reg
        }
        return ListenerRegistration {
            cancelled = true
            inner?.remove()
        }
    }

    /**
     * Claim membership of the family doc (trust-on-first-use): both the child
     * and the parent add their anonymous uid, so security rules can later limit
     * access to those members only.
     */
    fun joinMembership(context: Context, pairId: String) {
        if (!available(context) || pairId.isBlank()) return
        scope.launch {
            if (!awaitSignIn()) return@launch
            val uid = Firebase.auth.currentUser?.uid ?: return@launch
            runCatching {
                doc(pairId).set(mapOf("members" to FieldValue.arrayUnion(uid)), SetOptions.merge())
            }
        }
    }

    /** Child: heartbeat so the parent can see the device is online. */
    fun reportHeartbeat(context: Context, pairId: String, at: Long) {
        write(context, pairId, mapOf("lastSeen" to at))
    }

    /** Parent: observe the child's last-seen heartbeat. */
    fun listenHeartbeat(
        context: Context,
        pairId: String,
        onSeen: (Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val at = snap.getLong("lastSeen") ?: 0L
            if (at > 0L) onSeen(at)
        }
    }

    private fun doc(pairId: String) =
        Firebase.firestore.collection("families").document(pairId)

    /** Parent: publish the current policy for the paired child to pick up. */
    fun pushPolicy(context: Context, pairId: String, schedule: Schedule) {
        write(
            context,
            pairId,
            mapOf(
                "scheduleJson" to Prefs.encodeSchedule(schedule),
                "manualBlockUntil" to schedule.manualBlockUntil,
            ),
        )
    }

    /** Parent: publish the per-app policy (rules, budgets, restrictions). */
    fun pushAppPolicy(context: Context, pairId: String, appPolicy: AppPolicy) {
        write(context, pairId, mapOf("appPolicyJson" to AppPolicy.encode(appPolicy)))
    }

    /**
     * Parent: ask the child to release all protection so it can be uninstalled.
     * Stamped with [nowMillis] (pass System.currentTimeMillis()); the child acts
     * only when this is newer than the last release it handled.
     */
    fun requestRelease(context: Context, pairId: String, nowMillis: Long) {
        write(context, pairId, mapOf("releaseRequestedAt" to nowMillis))
    }

    /** Child: report a tamper/alert event to the parent. */
    fun reportAlert(context: Context, pairId: String, type: String, nowMillis: Long) {
        write(context, pairId, mapOf("alert" to type, "alertAt" to nowMillis))
    }

    /** Parent: listen for the child's latest alert. */
    fun listenAlert(
        context: Context,
        pairId: String,
        onAlert: (type: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val type = snap.getString("alert")
            val at = snap.getLong("alertAt") ?: 0L
            if (type != null) onAlert(type, at)
        }
    }

    // ---- Child -> parent requests (extra time / unlock) -----------------------

    fun reportRequest(context: Context, pairId: String, type: String, note: String, at: Long) {
        write(context, pairId, mapOf("reqType" to type, "reqNote" to note, "reqAt" to at))
    }

    /** Parent: clear a handled request so its banner disappears. */
    fun clearRequest(context: Context, pairId: String) {
        write(context, pairId, mapOf("reqAt" to 0L))
    }

    fun listenRequest(
        context: Context,
        pairId: String,
        onRequest: (type: String, note: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val at = snap.getLong("reqAt") ?: 0L
            if (at > 0L) {
                onRequest(snap.getString("reqType") ?: "extra", snap.getString("reqNote") ?: "", at)
            }
        }
    }

    // ---- Child -> parent location & usage -------------------------------------

    fun reportLocation(context: Context, pairId: String, lat: Double, lng: Double, at: Long) {
        write(context, pairId, mapOf("lat" to lat, "lng" to lng, "locAt" to at))
    }

    fun listenLocation(
        context: Context,
        pairId: String,
        onLocation: (lat: Double, lng: Double, at: Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val at = snap.getLong("locAt") ?: 0L
            if (at > 0L) onLocation(snap.getDouble("lat") ?: 0.0, snap.getDouble("lng") ?: 0.0, at)
        }
    }

    fun reportUsage(context: Context, pairId: String, usageJson: String, at: Long) {
        write(context, pairId, mapOf("usageJson" to usageJson, "usageAt" to at))
    }

    fun listenUsage(
        context: Context,
        pairId: String,
        onUsage: (usageJson: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val at = snap.getLong("usageAt") ?: 0L
            if (at > 0L) onUsage(snap.getString("usageJson") ?: "", at)
        }
    }

    // ---- Child -> parent live status (screen time, current app, blocks) -------

    /** Child: publish the periodic transparency snapshot for the parent's dashboard. */
    fun reportStatus(context: Context, pairId: String, statusJson: String, at: Long) {
        write(context, pairId, mapOf("statusJson" to statusJson, "statusAt" to at))
    }

    /** Parent: observe the child's live status snapshot. */
    fun listenStatus(
        context: Context,
        pairId: String,
        onStatus: (statusJson: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val at = snap.getLong("statusAt") ?: 0L
            if (at > 0L) onStatus(snap.getString("statusJson") ?: "", at)
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
        return listen(context, pairId) { snap ->
            onPolicy(
                RemotePolicy(
                    Prefs.decodeSchedule(snap.getString("scheduleJson")),
                    AppPolicy.decode(snap.getString("appPolicyJson")),
                    snap.getLong("releaseRequestedAt") ?: 0L,
                ),
            )
        }
    }
}
