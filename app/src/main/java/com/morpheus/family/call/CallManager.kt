package com.morpheus.family.call

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.morpheus.family.remote.RemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * Parent <-> child audio call, app-global. Signaling (offer/answer/ICE) rides
 * fields on the shared family document, so it reuses the same Firestore channel
 * and security rules as everything else.
 *
 * v1 limitation: incoming calls only ring while both apps are open (the family
 * doc listener is active). Ringing a killed/backgrounded peer needs the FCM
 * wake-up (same Cloud Function as immediate block) — a follow-up.
 */
object CallManager {

    enum class State { IDLE, OUTGOING, INCOMING, ACTIVE }

    data class CallUi(
        val state: State = State.IDLE,
        val peer: String = "",
        val muted: Boolean = false,
    )

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private var appContext: Context? = null
    private var pairId: String = ""
    private var role: String = ""          // "parent" or "child"
    private var peerName: String = ""
    private var listener: ListenerRegistration? = null
    private var bindJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var client: WebRtcClient? = null
    // Optional TURN relay, read from the family doc (turnUrl/turnUser/turnCred).
    // Empty until configured; STUN-only until then.
    private var turnServers: List<PeerConnection.IceServer> = emptyList()
    private var iAmCaller = false
    private var remoteReady = false
    private var handledCallAt = 0L
    private var pendingOffer: String? = null
    private var offerIceConsumed = 0
    private var answerIceConsumed = 0

    private fun doc() = Firebase.firestore.collection("families").document(pairId)

    /** Attach to a family doc so we can place/receive calls with [peer]. */
    fun bind(context: Context, pairId: String, role: String, peer: String) {
        if (this.pairId == pairId && this.role == role && listener != null) {
            peerName = peer
            return
        }
        unbindListenerOnly()
        appContext = context.applicationContext
        this.pairId = pairId
        this.role = role
        this.peerName = peer
        if (pairId.isBlank()) return
        // Attach the signaling listener only AFTER anonymous sign-in completes.
        // Attaching it while unauthenticated hits PERMISSION_DENIED and Firestore
        // tears the listener down for good, silently killing the call channel.
        bindJob = scope.launch {
            if (!RemoteRepository.awaitSignedIn(context)) return@launch
            listener = doc().addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                onSnapshot(snap)
            }
        }
    }

    private fun onSnapshot(snap: DocumentSnapshot) {
        // Pick up an optional TURN relay whenever the doc carries one (independent
        // of any active call), so the next call can use it.
        val turnUrl = snap.getString("turnUrl")
        turnServers = if (turnUrl.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(snap.getString("turnUser") ?: "")
                    .setPassword(snap.getString("turnCred") ?: "")
                    .createIceServer(),
            )
        }

        val from = snap.getString("callFrom") ?: return
        val status = snap.getString("callStatus") ?: ""
        val callAt = snap.getLong("callAt") ?: 0L

        // Peer hung up / ended.
        if (status == "ended") {
            if (_ui.value.state != State.IDLE) endLocal(writeEnded = false)
            return
        }

        // Incoming call from the peer while we're idle (ignore stale rings).
        val fresh = System.currentTimeMillis() - callAt < 60_000
        if (_ui.value.state == State.IDLE && from != role && status == "ringing" && fresh && callAt > handledCallAt) {
            handledCallAt = callAt
            pendingOffer = snap.getString("callOffer")
            _ui.value = CallUi(State.INCOMING, peerName)
            return
        }

        // We are the caller: pick up the peer's answer + ICE.
        if (iAmCaller && _ui.value.state != State.IDLE) {
            if (!remoteReady) {
                val ans = snap.getString("callAnswer")
                if (!ans.isNullOrBlank()) {
                    client?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, ans))
                    remoteReady = true
                }
            }
            if (remoteReady) answerIceConsumed = consumeIce(snap, "callAnswerIce", answerIceConsumed)
        }

        // We are the callee: pick up the caller's ICE once we answered.
        if (!iAmCaller && _ui.value.state == State.ACTIVE && remoteReady) {
            offerIceConsumed = consumeIce(snap, "callOfferIce", offerIceConsumed)
        }
    }

    /** Add any not-yet-added remote candidates from [field]; returns new consumed count. */
    private fun consumeIce(snap: DocumentSnapshot, field: String, consumed: Int): Int {
        @Suppress("UNCHECKED_CAST")
        val list = snap.get(field) as? List<Map<String, Any?>> ?: return consumed
        var i = consumed
        while (i < list.size) {
            val m = list[i]
            val mid = m["mid"] as? String
            val idx = (m["idx"] as? Number)?.toInt() ?: 0
            val sdp = m["sdp"] as? String
            if (sdp != null) client?.addRemoteIce(IceCandidate(mid, idx, sdp))
            i++
        }
        return i
    }

    /** Place a call to the peer. */
    fun startCall(context: Context) {
        if (_ui.value.state != State.IDLE || pairId.isBlank()) return
        iAmCaller = true
        remoteReady = false
        answerIceConsumed = 0
        offerIceConsumed = 0
        _ui.value = CallUi(State.OUTGOING, peerName)
        client = WebRtcClient(
            context.applicationContext,
            onLocalIce = { c -> appendIce("callOfferIce", c) },
            onConnected = { _ui.value = _ui.value.copy(state = State.ACTIVE) },
            onClosed = { endLocal(writeEnded = true) },
            extraIceServers = turnServers,
        )
        val now = System.currentTimeMillis()
        handledCallAt = now
        client?.createOffer { sdp ->
            doc().set(
                mapOf(
                    "callFrom" to role,
                    "callAt" to now,
                    "callStatus" to "ringing",
                    "callOffer" to sdp.description,
                    "callAnswer" to "",
                    "callOfferIce" to emptyList<Any>(),
                    "callAnswerIce" to emptyList<Any>(),
                ),
                SetOptions.merge(),
            )
        }
    }

    /** Accept the incoming call. */
    fun accept(context: Context) {
        if (_ui.value.state != State.INCOMING) return
        val offer = pendingOffer ?: return endLocal(writeEnded = true)
        iAmCaller = false
        offerIceConsumed = 0
        client = WebRtcClient(
            context.applicationContext,
            onLocalIce = { c -> appendIce("callAnswerIce", c) },
            onConnected = { _ui.value = _ui.value.copy(state = State.ACTIVE) },
            onClosed = { endLocal(writeEnded = true) },
            extraIceServers = turnServers,
        )
        client?.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offer))
        remoteReady = true
        _ui.value = CallUi(State.ACTIVE, peerName)
        client?.createAnswer { sdp ->
            doc().set(
                mapOf("callAnswer" to sdp.description, "callStatus" to "active"),
                SetOptions.merge(),
            )
        }
    }

    fun toggleMute() {
        val m = !_ui.value.muted
        client?.setMuted(m)
        _ui.value = _ui.value.copy(muted = m)
    }

    /** Hang up / decline. */
    fun hangup() = endLocal(writeEnded = true)

    private fun appendIce(field: String, c: IceCandidate) {
        runCatching {
            doc().set(
                mapOf(field to FieldValue.arrayUnion(
                    mapOf("mid" to c.sdpMid, "idx" to c.sdpMLineIndex.toLong(), "sdp" to c.sdp),
                )),
                SetOptions.merge(),
            )
        }
    }

    private fun endLocal(writeEnded: Boolean) {
        if (writeEnded && pairId.isNotBlank()) {
            runCatching { doc().set(mapOf("callStatus" to "ended"), SetOptions.merge()) }
        }
        runCatching { client?.close() }
        client = null
        iAmCaller = false
        remoteReady = false
        pendingOffer = null
        _ui.value = CallUi(State.IDLE, peerName)
    }

    private fun unbindListenerOnly() {
        bindJob?.cancel()
        bindJob = null
        runCatching { listener?.remove() }
        listener = null
    }
}
