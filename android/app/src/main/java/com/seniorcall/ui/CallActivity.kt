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

        private const val ICE_RESTART_CHECK_MS = 8_000L   // 8s check (§6)
        private const val CONNECT_TIMEOUT_MS = 25_000L      // 25s timeout (§6)
        private const val DISCONNECTED_RESTART_MS = 5_000L // 5s restart on disconnect (§6)
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
    private var isMediaConnected = false

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

    private val iceRestartCheckRunnable = Runnable {
        if (!isMediaConnected) {
            Log.w(TAG, "8s connection check failed: media not connected yet. Triggering ICE restart.")
            triggerIceRestart()
        }
    }

    private val connectTimeoutRunnable = Runnable {
        if (!isMediaConnected) {
            Log.e(TAG, "25s connection timeout reached. Ending call.")
            Toast.makeText(this@CallActivity, "Connection timed out", Toast.LENGTH_SHORT).show()
            endCall("connect_timeout")
        }
    }

    private val disconnectedRestartRunnable = Runnable {
        if (!isMediaConnected) {
            Log.w(TAG, "Still disconnected after 5s. Triggering ICE restart.")
            triggerIceRestart()
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

        // Offer-in-Invite (§3.6)
        if (!isIncoming) {
            binding.tvCallDuration.text = getString(R.string.status_calling)
            // Caller generates offer immediately so it can be embedded in call_invite
            callSession?.createOffer()
        } else {
            binding.tvCallDuration.text = "Connecting..."
            val offerSdp = intent.getStringExtra("offerSdp") ?: service.currentInvite?.sdp
            if (!offerSdp.isNullOrEmpty()) {
                callSession?.setRemoteDescriptionAndAnswer(offerSdp)
            }
        }

        startConnectingTimers()

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

    private fun startConnectingTimers() {
        handler.removeCallbacks(iceRestartCheckRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(iceRestartCheckRunnable, ICE_RESTART_CHECK_MS)
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectingTimers() {
        handler.removeCallbacks(iceRestartCheckRunnable)
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.removeCallbacks(disconnectedRestartRunnable)
    }

    private fun triggerIceRestart() {
        if (!isIncoming) {
            // Caller owns ICE restart (§6.2)
            Log.d(TAG, "Caller triggering ICE restart...")
            callSession?.restartIce()
        } else {
            // Callee requests caller to restart ICE (§6.2)
            Log.d(TAG, "Callee sending ice_restart_request to caller...")
            CallService.instance?.signalingClient?.send(
                IceRestartRequestMessage(callId = callId)
            )
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
                val service = CallService.instance ?: return
                if (sdp.type == SessionDescription.Type.OFFER) {
                    if (!isIncoming) {
                        // First offer: embed in CallInviteMessage (§3.6)
                        val myRole = service.prefs.role
                        val myName = if (myRole == "senior") "Elder Phone" else "Family Member"
                        val myId = service.prefs.userId ?: if (myRole == "senior") "usr_elder" else "usr_family"
                        Log.d(TAG, "Sending CallInviteMessage with embedded Offer SDP")
                        service.signalingClient?.send(
                            CallInviteMessage(
                                callId = callId,
                                to = targetUserId,
                                from = myId,
                                media = mediaType,
                                callerName = myName,
                                sdp = sdp.description
                            )
                        )
                    } else {
                        // ICE restart offer
                        service.signalingClient?.send(
                            SdpMessage(t = "offer", callId = callId, sdp = sdp.description)
                        )
                    }
                } else {
                    // Answer SDP: embed in CallAcceptMessage (§3.6)
                    if (isIncoming) {
                        Log.d(TAG, "Sending CallAcceptMessage with embedded Answer SDP")
                        service.signalingClient?.send(
                            CallAcceptMessage(callId = callId, sdp = sdp.description)
                        )
                    } else {
                        service.signalingClient?.send(
                            SdpMessage(t = "answer", callId = callId, sdp = sdp.description)
                        )
                    }
                }
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
                        isMediaConnected = true
                        cancelConnectingTimers()
                        startTimer()
                        CallService.instance?.signalingClient?.send(
                            CallConnectedMessage(callId = callId)
                        )
                    } else if (state == PeerConnection.PeerConnectionState.FAILED) {
                        isMediaConnected = false
                        triggerIceRestart()
                    } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        isMediaConnected = false
                        handler.postDelayed(disconnectedRestartRunnable, DISCONNECTED_RESTART_MS)
                    }
                }
            }
        }
    }

    // SignalingClient.Listener Callbacks
    override fun onCallState(state: CallStateMessage) {
        if (state.callId == callId || state.callId.isEmpty()) {
            runOnUiThread {
                if (state.state == "waking") {
                    binding.tvCallDuration.text = "Waking device..."
                } else if (state.state == "ringing") {
                    binding.tvCallDuration.text = getString(R.string.status_calling)
                }
            }
        }
    }

    override fun onCallRinging(ringing: CallRingingMessage) {
        if (ringing.callId == callId || ringing.callId.isEmpty()) {
            runOnUiThread {
                binding.tvCallDuration.text = getString(R.string.status_calling)
            }
        }
    }

    override fun onCallAccept(accept: CallAcceptMessage) {
        if (accept.callId == callId || accept.callId.isEmpty()) {
            Log.d(TAG, "Callee accepted call. Setting remote answer...")
            runOnUiThread {
                if (!accept.sdp.isNullOrEmpty()) {
                    callSession?.setRemoteAnswer(accept.sdp)
                }
            }
        }
    }

    override fun onIceRestartRequest(msg: IceRestartRequestMessage) {
        if (msg.callId == callId || msg.callId.isEmpty()) {
            if (!isIncoming) {
                // Caller handles restart (§6.2)
                runOnUiThread {
                    Log.d(TAG, "Peer requested ICE restart. Restarting ICE as caller...")
                    callSession?.restartIce()
                }
            }
        }
    }

    override fun onPeerSignalingLost(msg: PeerSignalingLostMessage) {
        if (msg.callId == callId || msg.callId.isEmpty()) {
            runOnUiThread {
                // S3: Grace period, do NOT terminate media!
                Log.w(TAG, "Peer lost signaling connection, but media is preserved.")
                binding.tvCallQualityDots.text = "Signaling reconnecting..."
            }
        }
    }

    override fun onOffer(msg: SdpMessage) {
        if (msg.callId == callId || msg.callId.isEmpty()) {
            Log.d(TAG, "Received SDP offer. Setting remote description and generating answer...")
            runOnUiThread {
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
        cancelConnectingTimers()
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
        cancelConnectingTimers()
        handler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
