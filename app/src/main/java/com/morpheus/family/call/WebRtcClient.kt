package com.morpheus.family.call

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Minimal audio-only WebRTC peer. Signaling (offer/answer/ICE) is done elsewhere
 * ([CallManager] over Firestore); this just owns the peer connection and the
 * microphone track. Remote audio is auto-played by WebRTC's audio device, so
 * there's nothing to render.
 *
 * STUN only (Google public servers). Calls across some mobile networks need a
 * TURN server too — add it to [ICE_SERVERS] once available.
 */
class WebRtcClient(
    appContext: Context,
    private val onLocalIce: (IceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onClosed: () -> Unit,
    extraIceServers: List<PeerConnection.IceServer> = emptyList(),
) {
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    // STUN discovers a public path on most Wi-Fi; TURN (when configured) relays
    // media on restrictive mobile/CGNAT networks where STUN alone can't connect.
    private val iceServers: List<PeerConnection.IceServer> = ICE_SERVERS + extraIceServers

    init {
        ensureInit(appContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
        createPeerConnection()
        addLocalAudio()
    }

    private fun createPeerConnection() {
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) = onLocalIce(candidate)
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> onClosed()
                    else -> {}
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    private fun addLocalAudio() {
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("audio0", source)
        track.setEnabled(true)
        localAudioTrack = track
        runCatching { pc?.addTrack(track, listOf("stream0")) }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun createOffer(onSdp: (SessionDescription) -> Unit) {
        pc?.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(SdpAdapter(), sdp)
                onSdp(sdp)
            }
        }, MediaConstraints())
    }

    fun createAnswer(onSdp: (SessionDescription) -> Unit) {
        pc?.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(SdpAdapter(), sdp)
                onSdp(sdp)
            }
        }, MediaConstraints())
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        pc?.setRemoteDescription(SdpAdapter(), sdp)
    }

    fun addRemoteIce(candidate: IceCandidate) {
        runCatching { pc?.addIceCandidate(candidate) }
    }

    fun close() {
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { factory.dispose() }
    }

    companion object {
        @Volatile
        private var initialized = false

        private fun ensureInit(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                initialized = true
            }
        }

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
    }
}

/** No-op [SdpObserver] so callers only override what they need. */
open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
