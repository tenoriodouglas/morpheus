package com.morpheus.family.call

import android.content.Context
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRTC peer for parent <-> child calls. Signaling (offer/answer/ICE) is done
 * elsewhere ([CallManager] over Firestore); this owns the peer connection, the
 * microphone track, and — for a video call — the camera track.
 *
 * Audio calls ([videoEnabled] = false) behave exactly as before: only a mic
 * track is added and remote audio is auto-played, nothing to render. When
 * [videoEnabled] is true it also captures the front camera into a local
 * [localVideoTrack] and reports the remote camera through [onRemoteVideo], both
 * rendered with the shared [eglBase] context.
 *
 * STUN by default; a TURN relay can be supplied via [extraIceServers].
 */
class WebRtcClient(
    appContext: Context,
    private val onLocalIce: (IceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onClosed: () -> Unit,
    extraIceServers: List<PeerConnection.IceServer> = emptyList(),
    private val videoEnabled: Boolean = false,
    private val onRemoteVideo: (VideoTrack) -> Unit = {},
) {
    private val appContext: Context = appContext.applicationContext

    /** Shared GL context for the factory's codecs and the UI's renderers. */
    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null

    /** Local camera track (video calls only); null for audio calls. */
    var localVideoTrack: VideoTrack? = null
        private set

    // STUN discovers a public path on most Wi-Fi; TURN (when configured) relays
    // media on restrictive mobile/CGNAT networks where STUN alone can't connect.
    private val iceServers: List<PeerConnection.IceServer> = ICE_SERVERS + extraIceServers

    init {
        ensureInit(this.appContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        createPeerConnection()
        addLocalAudio()
        if (videoEnabled) addLocalVideo()
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
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let { onRemoteVideo(it) }
            }
        })
    }

    private fun addLocalAudio() {
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("audio0", source)
        track.setEnabled(true)
        localAudioTrack = track
        runCatching { pc?.addTrack(track, listOf("stream0")) }
    }

    private fun addLocalVideo() {
        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        val source = factory.createVideoSource(capturer.isScreencast)
        localVideoSource = source
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceHelper = helper
        capturer.initialize(helper, appContext, source.capturerObserver)
        runCatching { capturer.startCapture(1280, 720, 30) }
        val track = factory.createVideoTrack("video0", source)
        track.setEnabled(true)
        localVideoTrack = track
        runCatching { pc?.addTrack(track, listOf("stream0")) }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        // Prefer the front camera for a call.
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            return enumerator.createCapturer(it, null)
        }
        return names.firstOrNull()?.let { enumerator.createCapturer(it, null) }
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    /** Turn the local camera feed on/off during a video call. */
    fun setCameraEnabled(on: Boolean) {
        localVideoTrack?.setEnabled(on)
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
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
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
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
