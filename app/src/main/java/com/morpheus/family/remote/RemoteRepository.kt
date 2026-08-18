package com.morpheus.family.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    /** Live diagnostics so the UI can show WHY sync is (not) working. */
    data class SyncStatus(
        val signedIn: Boolean = false,
        val lastOkAt: Long = 0L,
        val lastError: String? = null,
    )

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Turn a Firestore/auth failure into a plain, actionable message. */
    private fun describe(t: Throwable?): String = when {
        t is FirebaseFirestoreException &&
            t.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Sem permissão do Firestore — publique as novas regras (firebase deploy --only firestore:rules) e confira o pareamento."
        t is FirebaseFirestoreException &&
            t.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Firestore indisponível — sem internet no momento."
        else -> t?.message ?: "erro desconhecido"
    }

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
        Firebase.auth.currentUser?.let {
            _status.update { s -> s.copy(signedIn = true) }
            return true
        }
        val result = runCatching { Firebase.auth.signInAnonymously().await() }
        return if (result.isSuccess && Firebase.auth.currentUser != null) {
            _status.update { it.copy(signedIn = true) }
            true
        } else {
            _status.update {
                it.copy(
                    signedIn = false,
                    lastError = "Login anônimo falhou — ative Authentication → Sign-in method → " +
                        "Anonymous no Firebase (${result.exceptionOrNull()?.message ?: "sem detalhe"})",
                )
            }
            false
        }
    }

    /** Kick off sign-in early (e.g. from the guardian service) so later calls are instant. */
    fun ensureSignedIn(context: Context) {
        if (!available(context)) return
        scope.launch { awaitSignIn() }
    }

    /**
     * Await anonymous sign-in, for callers (e.g. the call channel) that must
     * attach their own Firestore listener only *after* auth — a listener attached
     * while unauthenticated hits PERMISSION_DENIED and is torn down permanently.
     * Returns false if Firebase isn't configured or sign-in failed.
     */
    suspend fun awaitSignedIn(context: Context): Boolean =
        available(context) && awaitSignIn()

    /** Merge [data] into the family doc once authenticated. Fire-and-forget. */
    private fun write(context: Context, pairId: String, data: Map<String, Any>) {
        if (!available(context) || pairId.isBlank()) return
        scope.launch {
            if (awaitSignIn()) {
                doc(pairId).set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        _status.update { it.copy(lastOkAt = System.currentTimeMillis(), lastError = null) }
                    }
                    .addOnFailureListener { e -> _status.update { it.copy(lastError = describe(e)) } }
            }
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
                if (err != null) { _status.update { it.copy(lastError = describe(err)) }; return@addSnapshotListener }
                if (snap == null || !snap.exists()) return@addSnapshotListener
                _status.update { it.copy(lastOkAt = System.currentTimeMillis(), lastError = null) }
                // NB: do NOT skip snapshots with hasPendingWrites here. The child
                // both writes (status/heartbeat/location) and reads (policy) from
                // the SAME document, so a parent command arriving while the child
                // has an in-flight write would be dropped/delayed. Echo suppression
                // is handled downstream (GuardianService's lastEnforced diff-cache).
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

    /** Child: publish this device's FCM token so a backend can push wake-ups. */
    fun reportFcmToken(context: Context, pairId: String, token: String) {
        if (token.isBlank()) return
        write(context, pairId, mapOf("childFcmToken" to token))
    }

    /** Child: fetch the current FCM token and publish it (best-effort). */
    fun uploadCurrentFcmToken(context: Context, pairId: String) {
        if (!available(context) || pairId.isBlank()) return
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> reportFcmToken(context, pairId, token) }
        }
    }

    /** Parent: publish this device's FCM token (and the child's display name)
     *  onto a child's doc so the backend can push SOS/request alerts back to the
     *  parent — labelled with the child's name — even when its app is closed. */
    fun reportParentFcmToken(context: Context, pairId: String, token: String, childName: String) {
        if (token.isBlank()) return
        val data = mutableMapOf<String, Any>("parentFcmToken" to token)
        if (childName.isNotBlank()) data["childName"] = childName
        write(context, pairId, data)
    }

    /** Parent: publish the current FCM token onto every paired child's doc.
     *  [children] maps each child's pairing id to its display name. */
    fun uploadParentFcmToken(context: Context, children: Map<String, String>) {
        if (!available(context) || children.isEmpty()) return
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    children.forEach { (id, name) -> reportParentFcmToken(context, id, token, name) }
                }
        }
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
                // Redundant top-level mirrors for the FCM fast-path; the child
                // reads the full state from scheduleJson.
                "manualBlockUntil" to schedule.manualBlockUntil,
                "manualUnblockUntil" to schedule.manualUnblockUntil,
                "manualSetAt" to schedule.manualSetAt,
            ),
        )
    }

    /**
     * Parent: request a live-location window until [untilMillis]. While this is
     * in the future the child streams frequent fixes; it auto-reverts to the
     * battery-saving cadence when the window lapses.
     */
    fun requestLive(context: Context, pairId: String, untilMillis: Long) {
        write(context, pairId, mapOf("liveUntil" to untilMillis))
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
            // Only surface a live alert. at == 0 means it was dismissed/cleared,
            // so it must not re-appear on the next unrelated snapshot.
            if (type != null && at > 0L) onAlert(type, at)
        }
    }

    /** Parent: dismiss a handled alert so it stops re-appearing (swipe-to-remove). */
    fun clearAlert(context: Context, pairId: String) {
        write(context, pairId, mapOf("alertAt" to 0L))
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

    /**
     * Child: publish the current position and, optionally, the rolling 24h route
     * in the same write. Collapsing the two into one [write] halves the Firestore
     * traffic on the frequent live-streaming path.
     */
    fun reportLocation(
        context: Context,
        pairId: String,
        lat: Double,
        lng: Double,
        at: Long,
        historyJson: String? = null,
        historyAt: Long = at,
        mock: Boolean = false,
    ) {
        val data = mutableMapOf<String, Any>("lat" to lat, "lng" to lng, "locAt" to at, "locMock" to mock)
        if (historyJson != null) {
            data["locHistoryJson"] = historyJson
            data["locHistoryAt"] = historyAt
        }
        write(context, pairId, data)
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

    /** Parent: observe the child's 24h route. */
    fun listenLocationHistory(
        context: Context,
        pairId: String,
        onHistory: (historyJson: String, at: Long) -> Unit,
    ): ListenerRegistration? {
        return listen(context, pairId) { snap ->
            val at = snap.getLong("locHistoryAt") ?: 0L
            if (at > 0L) onHistory(snap.getString("locHistoryJson") ?: "", at)
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

    /**
     * Child: publish the periodic transparency snapshot for the parent's dashboard.
     * Also stamps `lastSeen` so the online heartbeat rides the same write instead
     * of costing a second round-trip every tick.
     */
    fun reportStatus(context: Context, pairId: String, statusJson: String, at: Long) {
        write(context, pairId, mapOf("statusJson" to statusJson, "statusAt" to at, "lastSeen" to at))
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
                    snap.getLong("liveUntil") ?: 0L,
                ),
            )
        }
    }
}
