package com.seniorcall.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seniorcall.App
import com.seniorcall.R
import com.seniorcall.core.Haptics
import com.seniorcall.databinding.ActivityCallBinding
import com.seniorcall.rtc.CallSession
import com.seniorcall.rtc.QualityMonitor
import com.seniorcall.rtc.ScreenShare
import com.seniorcall.service.CallService
import com.seniorcall.service.ScreenShareService
import com.seniorcall.signaling.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class CallActivity : AppCompatActivity(), SignalingClient.Listener {
    companion object {
        private const val TAG = "CallActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_CONSENT_ACTIVITY = 1002
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var haptics: Haptics

    private var callId: String = ""
    private var isIncoming: Boolean = false
    private var targetUserId: String = ""
    private var targetName: String = "Family"
    private var mediaType: String = "video"

    private var isMuted = false
    private var isCameraOff = false
    private var isSharingScreen = false

    private var callSession: CallSession? = null
    private var screenShare: ScreenShare? = null
    private var qualityMonitor: QualityMonitor? = null

    private var callStartTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateDurationTimer()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        haptics = Haptics(this)

        isIncoming = intent.getBooleanExtra("isIncoming", false)
        callId = intent.getStringExtra("callId") ?: "call_${System.currentTimeMillis()}"
        targetUserId = intent.getStringExtra("targetUserId") ?: ""
        targetName = intent.getStringExtra("targetName") ?: "Family"
        mediaType = intent.getStringExtra("media") ?: "video"

        setupViews()
        setupWebRTC()
    }

    private fun setupViews() {
        binding.tvPeerName.text = targetName

        // WebRTC Renderers Initialization
        val eglContext = (application as App).rtcEngine.eglBase.eglBaseContext
        binding.remoteSurfaceRenderer.init(eglContext, null)
        binding.remoteSurfaceRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.remoteSurfaceRenderer.setEnableHardwareScaler(true)

        binding.screenSurfaceRenderer.init(eglContext, null)
        binding.screenSurfaceRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.screenSurfaceRenderer.setEnableHardwareScaler(true)

        binding.localSurfaceRenderer.init(eglContext, null)
        binding.localSurfaceRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.localSurfaceRenderer.setMirror(true)
        binding.localSurfaceRenderer.setZOrderMediaOverlay(true)

        if (mediaType == "audio") {
            binding.localSurfaceRenderer.visibility = View.GONE
            binding.remoteSurfaceRenderer.visibility = View.GONE
        }

        // Control buttons
        binding.btnMute.setOnClickListener {
            haptics.tick()
            toggleMute()
        }

        binding.btnToggleCam.setOnClickListener {
            haptics.tick()
            toggleCamera()
        }

        binding.btnShareScreen.setOnClickListener {
            haptics.tick()
            toggleScreenShare()
        }

        binding.btnStopSharingBanner.setOnClickListener {
            haptics.tick()
            stopScreenSharing()
        }

        binding.btnEndCall.setOnClickListener {
            haptics.tick()
            endCall("user_hung_up")
        }

        binding.btnLouder.setOnClickListener {
            haptics.tick()
            CallService.instance?.audioRouter?.increaseVolume()
        }
    }

    private fun setupWebRTC() {
        val service = CallService.instance ?: run {
            Toast.makeText(this, "Call service unavailable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        service.audioRouter.startCallAudio()

        // Register signaling listener
        service.addSignalingListener(this)

        // Initialize CallSession
        callSession = CallSession(
            this,
            (application as App).rtcEngine,
            service.iceServers,
            createSessionListener()
        )
        service.activeCallSession = callSession

        callSession?.setupLocalMedia(
            videoEnabled = mediaType == "video",
            localSink = binding.localSurfaceRenderer
        )

        if (!isIncoming) {
            binding.tvCallDuration.text = getString(R.string.status_calling)
            // Outgoing call invite
            val myRole = service.prefs.role
            val myName = if (myRole == "senior") "Elder Phone" else "Family Member"
            val myId = service.prefs.userId ?: if (myRole == "senior") "usr_elder" else "usr_family"

            service.signalingClient?.send(
                CallInviteMessage(
                    callId = callId,
                    to = targetUserId,
                    from = myId,
                    media = mediaType,
                    callerName = myName
                )
            )
        } else {
            // Incoming call answered, callee notifies acceptance
            service.signalingClient?.send(CallAcceptMessage(callId = callId))
            binding.tvCallDuration.text = "Connecting..."
        }

        // Start quality monitor
        callSession?.peerConnection?.let { pc ->
            qualityMonitor = QualityMonitor(pc, object : QualityMonitor.Callback {
                override fun onQualityChanged(dots: String, qualityScore: Int) {
                    binding.tvCallQualityDots.text = dots
                }

                override fun onLowBandwidthFallback() {
                    binding.remoteSurfaceRenderer.visibility = View.GONE
                    binding.localSurfaceRenderer.visibility = View.GONE
                }
            }).apply { start() }
        }
    }

    private fun createSessionListener(): CallSession.Listener {
        return object : CallSession.Listener {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Sending local ICE candidate: ${candidate.sdpMid}")
                CallService.instance?.signalingClient?.send(
                    IceCandidateMessage(
                        callId = callId,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                )
            }

            override fun onLocalDescription(sdp: SessionDescription) {
                val type = if (sdp.type == SessionDescription.Type.OFFER) "offer" else "answer"
                Log.d(TAG, "Sending local SDP description: $type")
                CallService.instance?.signalingClient?.send(
                    SdpMessage(t = type, callId = callId, sdp = sdp.description)
                )
            }

            override fun onRemoteVideoTrack(track: VideoTrack) {
                Log.d(TAG, "Remote video track received")
                runOnUiThread {
                    track.addSink(binding.remoteSurfaceRenderer)
                    binding.remoteSurfaceRenderer.visibility = View.VISIBLE
                }
            }

            override fun onRemoteScreenTrack(track: VideoTrack) {
                Log.d(TAG, "Remote screen track received")
                runOnUiThread {
                    track.addSink(binding.screenSurfaceRenderer)
                    binding.screenSurfaceRenderer.visibility = View.VISIBLE
                    binding.remoteSurfaceRenderer.visibility = View.GONE
                }
            }

            override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "WebRTC connection state: $state")
                runOnUiThread {
                    if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                        startTimer()
                    } else if (state == PeerConnection.PeerConnectionState.FAILED) {
                        callSession?.restartIce()
                    }
                }
            }
        }
    }

    // SignalingClient.Listener Callbacks
    override fun onCallAccept(accept: CallAcceptMessage) {
        if (accept.callId == callId || accept.callId.isEmpty()) {
            Log.d(TAG, "Callee accepted call. Creating SDP offer...")
            runOnUiThread {
                startTimer()
                callSession?.createOffer()
            }
        }
    }

    override fun onOffer(msg: SdpMessage) {
        if (msg.callId == callId || msg.callId.isEmpty()) {
            Log.d(TAG, "Received SDP offer. Setting remote description and generating answer...")
            runOnUiThread {
                startTimer()
                callSession?.setRemoteDescriptionAndAnswer(msg.sdp)
            }
        }
    }

    override fun onAnswer(msg: SdpMessage) {
        if (msg.callId == callId || msg.callId.isEmpty()) {
            Log.d(TAG, "Received SDP answer. Setting remote answer...")
            runOnUiThread {
                callSession?.setRemoteAnswer(msg.sdp)
            }
        }
    }

    override fun onIceCandidate(ice: IceCandidateMessage) {
        if (ice.callId == callId || ice.callId.isEmpty()) {
            callSession?.addRemoteIceCandidate(
                IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
            )
        }
    }

    override fun onCallReject(reject: CallRejectMessage) {
        if (reject.callId == callId || reject.callId.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "Call declined (${reject.reason})", Toast.LENGTH_SHORT).show()
                cleanupAndExit()
            }
        }
    }

    override fun onCallEnd(end: CallEndMessage) {
        if (end.callId == callId || end.callId.isEmpty()) {
            runOnUiThread {
                cleanupAndExit()
            }
        }
    }

    override fun onScreenRequest(req: ScreenRequestMessage) {
        runOnUiThread {
            val intent = Intent(this, ShareConsentActivity::class.java).apply {
                putExtra("callerName", targetName)
            }
            startActivityForResult(intent, REQUEST_CONSENT_ACTIVITY)
        }
    }

    override fun onScreenShareState(state: ScreenShareStateMessage) {
        runOnUiThread {
            if (state.t == "screen_share_stopped") {
                binding.screenSurfaceRenderer.visibility = View.GONE
                if (mediaType == "video") {
                    binding.remoteSurfaceRenderer.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        callSession?.setAudioMuted(isMuted)
        binding.btnMute.text = if (isMuted) getString(R.string.btn_unmute) else getString(R.string.btn_mute)
    }

    private fun toggleCamera() {
        isCameraOff = !isCameraOff
        callSession?.setVideoEnabled(!isCameraOff)
        binding.btnToggleCam.text = if (isCameraOff) getString(R.string.btn_camera_on) else getString(R.string.btn_camera_off)
        binding.localSurfaceRenderer.visibility = if (isCameraOff) View.GONE else View.VISIBLE
    }

    private fun toggleScreenShare() {
        if (isSharingScreen) {
            stopScreenSharing()
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val fgIntent = Intent(this, ScreenShareService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(fgIntent)
            } else {
                startService(fgIntent)
            }

            screenShare = ScreenShare(this, (application as App).rtcEngine, object : ScreenShare.Callback {
                override fun onScreenShareStarted(track: VideoTrack) {
                    callSession?.addScreenTrack(track)
                    isSharingScreen = true
                    binding.layoutSharingBanner.visibility = View.VISIBLE
                    binding.tvSharingBannerText.text = getString(R.string.sharing_banner, targetName)

                    CallService.instance?.signalingClient?.send(
                        ScreenShareStateMessage(t = "screen_share_started", callId = callId, trackId = track.id())
                    )
                }

                override fun onScreenShareStopped() {
                    stopScreenSharing()
                }
            })
            screenShare?.start(resultCode, data)
        } else if (requestCode == REQUEST_CONSENT_ACTIVITY) {
            if (resultCode == Activity.RESULT_OK) {
                toggleScreenShare()
            }
        }
    }

    private fun stopScreenSharing() {
        screenShare?.stop()
        screenShare = null
        callSession?.removeScreenTrack()
        isSharingScreen = false
        binding.layoutSharingBanner.visibility = View.GONE

        stopService(Intent(this, ScreenShareService::class.java))

        CallService.instance?.signalingClient?.send(
            ScreenShareStateMessage(t = "screen_share_stopped", callId = callId)
        )
    }

    private fun startTimer() {
        if (callStartTime == 0L) {
            callStartTime = System.currentTimeMillis()
            handler.post(timerRunnable)
        }
    }

    private fun updateDurationTimer() {
        val seconds = (System.currentTimeMillis() - callStartTime) / 1000
        val mins = seconds / 60
        val secs = seconds % 60
        binding.tvCallDuration.text = String.format("%02d:%02d", mins, secs)
    }

    private fun endCall(reason: String) {
        CallService.instance?.signalingClient?.send(
            CallEndMessage(callId = callId, reason = reason)
        )
        cleanupAndExit()
    }

    private fun cleanupAndExit() {
        handler.removeCallbacks(timerRunnable)
        qualityMonitor?.stop()
        if (isSharingScreen) stopScreenSharing()

        CallService.instance?.removeSignalingListener(this)

        callSession?.dispose()
        callSession = null
        CallService.instance?.activeCallSession = null
        CallService.instance?.audioRouter?.stopCallAudio()

        try {
            binding.remoteSurfaceRenderer.release()
            binding.screenSurfaceRenderer.release()
            binding.localSurfaceRenderer.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val postCallIntent = Intent(this, PostCallActivity::class.java).apply {
            putExtra("peerName", targetName)
            putExtra("peerId", targetUserId)
            putExtra("media", mediaType)
        }
        startActivity(postCallIntent)
        finish()
    }

    override fun onDestroy() {
        CallService.instance?.removeSignalingListener(this)
        handler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
