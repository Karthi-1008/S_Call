package com.seniorcall.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

class CallSession(
    private val context: Context,
    private val rtcEngine: RtcEngine,
    private val iceServers: List<PeerConnection.IceServer>,
    private var listener: Listener
) {
    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onLocalDescription(sdp: SessionDescription)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteScreenTrack(track: VideoTrack)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
    }

    private val tag = "CallSession"
    private val mainHandler = Handler(Looper.getMainLooper())

    var peerConnection: PeerConnection? = null
        private set

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenSender: RtpSender? = null

    private var isRemoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    init {
        createPeerConnection()
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    private fun createPeerConnection() {
        val rtcConfig = rtcEngine.createRtcConfig(iceServers)
        peerConnection = rtcEngine.peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                mainHandler.post { listener.onIceCandidate(candidate) }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                if (track is VideoTrack) {
                    mainHandler.post {
                        if (track.id().startsWith("screen-")) {
                            listener.onRemoteScreenTrack(track)
                        } else {
                            listener.onRemoteVideoTrack(track)
                        }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(tag, "ConnectionState changed: $newState")
                mainHandler.post { listener.onConnectionStateChange(newState) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    fun setupLocalMedia(videoEnabled: Boolean, localSink: VideoSink? = null) {
        // 1. Audio track
        val audioSource = rtcEngine.createAudioSource()
        localAudioTrack = rtcEngine.peerConnectionFactory.createAudioTrack("audio-0", audioSource)
        peerConnection?.addTrack(localAudioTrack, listOf("stream-0"))

        // 2. Video track
        if (videoEnabled) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rtcEngine.eglBase.eglBaseContext)
            videoCapturer = createCameraCapturer()
            val videoSource = rtcEngine.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            try {
                videoCapturer?.startCapture(640, 480, 24)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start camera capture", e)
            }

            localVideoTrack = rtcEngine.peerConnectionFactory.createVideoTrack("video-0", videoSource)
            localSink?.let { localVideoTrack?.addSink(it) }
            peerConnection?.addTrack(localVideoTrack, listOf("stream-0"))
        }
    }

    fun createOffer(mediaConstraints: MediaConstraints = MediaConstraints()) {
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        mainHandler.post { listener.onLocalDescription(desc) }
                    }
                }, desc)
            }
        }, mediaConstraints)
    }

    fun setRemoteDescriptionAndAnswer(remoteSdp: String) {
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainPendingIceCandidates()
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                mainHandler.post { listener.onLocalDescription(desc) }
                            }
                        }, desc)
                    }
                }, MediaConstraints())
            }
        }, sessionDesc)
    }

    fun setRemoteAnswer(remoteSdp: String) {
        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainPendingIceCandidates()
            }
        }, sessionDesc)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (isRemoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    private fun drainPendingIceCandidates() {
        mainHandler.post {
            for (c in pendingIceCandidates) {
                peerConnection?.addIceCandidate(c)
            }
            pendingIceCandidates.clear()
        }
    }

    fun addScreenTrack(screenTrack: VideoTrack) {
        screenSender = peerConnection?.addTrack(screenTrack, listOf("stream-screen"))
    }

    fun removeScreenTrack() {
        screenSender?.let { peerConnection?.removeTrack(it) }
        screenSender = null
    }

    fun restartIce() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        createOffer(constraints)
    }

    fun setAudioMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun dispose() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e("SDP", "onCreateFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e("SDP", "onSetFailure: $error")
        }
    }
}
