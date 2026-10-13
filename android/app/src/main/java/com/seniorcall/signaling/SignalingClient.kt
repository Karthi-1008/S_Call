package com.seniorcall.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.seniorcall.data.Contact
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class SignalingClient(
    private val wsUrl: String,
    private val token: String,
    private val deviceId: String = "android-device",
    initialListener: Listener? = null
) {
    interface Listener {
        fun onConnected(hello: HelloMessage) {}
        fun onDisconnected() {}
        fun onCallInvite(invite: CallInviteMessage) {}
        fun onCallState(state: CallStateMessage) {}
        fun onCallRinging(ringing: CallRingingMessage) {}
        fun onCallAccept(accept: CallAcceptMessage) {}
        fun onCallConnected(connected: CallConnectedMessage) {}
        fun onPeerSignalingLost(msg: PeerSignalingLostMessage) {}
        fun onIceRestartRequest(msg: IceRestartRequestMessage) {}
        fun onCallReject(reject: CallRejectMessage) {}
        fun onOffer(msg: SdpMessage) {}
        fun onAnswer(msg: SdpMessage) {}
        fun onIceCandidate(ice: IceCandidateMessage) {}
        fun onRenegotiate(msg: RenegotiateMessage) {}
        fun onScreenRequest(req: ScreenRequestMessage) {}
        fun onScreenRequestReply(reply: ScreenRequestReplyMessage) {}
        fun onScreenShareState(state: ScreenShareStateMessage) {}
        fun onCallEnd(end: CallEndMessage) {}
        fun onPresence(presence: PresenceMessage) {}
        fun onContactsUpdate(contacts: List<Contact>) {}
        fun onError(code: String, message: String) {}
    }

    private val tag = "SignalingClient"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    init {
        initialListener?.let { listeners.add(it) }
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) {
            listeners.add(l)
        }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isClosedManually = false
    private var reconnectAttempt = 0
    private var isConnecting = false

    var isConnected: Boolean = false
        private set

    var activeCallId: String? = null

    private var lastPongReceivedAt = System.currentTimeMillis()
    private val PONG_TIMEOUT_MS = 10_000L

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                send(mapOf("t" to "ping"))
                // Check if pong is overdue
                mainHandler.postDelayed(pongCheckRunnable, PONG_TIMEOUT_MS)
            }
            mainHandler.postDelayed(this, 20_000L)
        }
    }

    private val pongCheckRunnable = Runnable {
        if (isConnected && System.currentTimeMillis() - lastPongReceivedAt > 35_000L) {
            Log.w(tag, "Pong timed out! Terminating stale WebSocket connection.")
            webSocket?.cancel()
        }
    }

    @Synchronized
    fun connect() {
        if (isConnected || isConnecting) {
            Log.d(tag, "connect() ignored: already connected or connecting")
            return
        }

        isClosedManually = false
        isConnecting = true

        val separator = if (wsUrl.contains("?")) "&" else "?"
        var urlWithParams = wsUrl
        if (token.isNotBlank()) {
            urlWithParams = "$urlWithParams${separator}token=$token&deviceId=$deviceId"
        } else {
            urlWithParams = "$urlWithParams${separator}deviceId=$deviceId"
        }

        Log.d(tag, "Connecting to WebSocket: $urlWithParams")
        val request = Request.Builder()
            .url(urlWithParams)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Device-Id", deviceId)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket opened successfully")
                isConnected = true
                isConnecting = false
                reconnectAttempt = 0
                lastPongReceivedAt = System.currentTimeMillis()

                // If reconnecting during an active call, send resume
                if (!activeCallId.isNullOrEmpty()) {
                    send(ResumeMessage(activeCallId = activeCallId))
                }

                mainHandler.removeCallbacks(pingRunnable)
                mainHandler.post(pingRunnable)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code / $reason")
                onSocketDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket error: ${t.message}")
                onSocketDisconnected()
            }
        })
    }

    private fun onSocketDisconnected() {
        isConnected = false
        isConnecting = false
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacks(pongCheckRunnable)
        mainHandler.post {
            listeners.forEach { it.onDisconnected() }
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (isClosedManually) return
        reconnectAttempt++
        // Exponential backoff: 1s, 2s, 4s, 8s, max 16s with jitter
        val baseDelay = min(1000L * (1L shl min(reconnectAttempt - 1, 4)), 16000L)
        val jitter = Random.nextLong(-200L, 200L)
        val delayMs = (baseDelay + jitter).coerceAtLeast(500L)

        Log.d(tag, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        mainHandler.postDelayed({ connect() }, delayMs)
    }

    fun send(msg: Any) {
        val json = if (msg is String) msg else gson.toJson(msg)
        webSocket?.send(json)
    }

    @Synchronized
    fun disconnect() {
        isClosedManually = true
        isConnected = false
        isConnecting = false
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacks(pongCheckRunnable)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val generic = gson.fromJson(json, GenericMessage::class.java) ?: return
            lastPongReceivedAt = System.currentTimeMillis()

            mainHandler.post {
                when (generic.t) {
                    "pong" -> {
                        // Keepalive pong received
                    }
                    "hello" -> {
                        val hello = gson.fromJson(json, HelloMessage::class.java)
                        listeners.forEach { it.onConnected(hello) }
                    }
                    "call_invite" -> {
                        val invite = gson.fromJson(json, CallInviteMessage::class.java)
                        activeCallId = invite.callId
                        listeners.forEach { it.onCallInvite(invite) }
                    }
                    "call_state" -> {
                        val state = gson.fromJson(json, CallStateMessage::class.java)
                        listeners.forEach { it.onCallState(state) }
                    }
                    "call_ringing" -> {
                        val ringing = gson.fromJson(json, CallRingingMessage::class.java)
                        listeners.forEach { it.onCallRinging(ringing) }
                    }
                    "call_accept" -> {
                        val accept = gson.fromJson(json, CallAcceptMessage::class.java)
                        activeCallId = accept.callId
                        listeners.forEach { it.onCallAccept(accept) }
                    }
                    "call_connected" -> {
                        val connected = gson.fromJson(json, CallConnectedMessage::class.java)
                        listeners.forEach { it.onCallConnected(connected) }
                    }
                    "peer_signaling_lost" -> {
                        val lost = gson.fromJson(json, PeerSignalingLostMessage::class.java)
                        listeners.forEach { it.onPeerSignalingLost(lost) }
                    }
                    "ice_restart_request" -> {
                        val req = gson.fromJson(json, IceRestartRequestMessage::class.java)
                        listeners.forEach { it.onIceRestartRequest(req) }
                    }
                    "call_reject" -> {
                        val reject = gson.fromJson(json, CallRejectMessage::class.java)
                        activeCallId = null
                        listeners.forEach { it.onCallReject(reject) }
                    }
                    "offer" -> {
                        val sdp = gson.fromJson(json, SdpMessage::class.java)
                        listeners.forEach { it.onOffer(sdp) }
                    }
                    "answer" -> {
                        val sdp = gson.fromJson(json, SdpMessage::class.java)
                        listeners.forEach { it.onAnswer(sdp) }
                    }
                    "ice" -> {
                        val ice = gson.fromJson(json, IceCandidateMessage::class.java)
                        listeners.forEach { it.onIceCandidate(ice) }
                    }
                    "renegotiate" -> {
                        val ren = gson.fromJson(json, RenegotiateMessage::class.java)
                        listeners.forEach { it.onRenegotiate(ren) }
                    }
                    "screen_request" -> {
                        val req = gson.fromJson(json, ScreenRequestMessage::class.java)
                        listeners.forEach { it.onScreenRequest(req) }
                    }
                    "screen_request_reply" -> {
                        val rep = gson.fromJson(json, ScreenRequestReplyMessage::class.java)
                        listeners.forEach { it.onScreenRequestReply(rep) }
                    }
                    "screen_share_started", "screen_share_stopped" -> {
                        val state = gson.fromJson(json, ScreenShareStateMessage::class.java)
                        listeners.forEach { it.onScreenShareState(state) }
                    }
                    "call_end" -> {
                        val end = gson.fromJson(json, CallEndMessage::class.java)
                        activeCallId = null
                        listeners.forEach { it.onCallEnd(end) }
                    }
                    "presence" -> {
                        val pres = gson.fromJson(json, PresenceMessage::class.java)
                        listeners.forEach { it.onPresence(pres) }
                    }
                    "contacts_update" -> {
                        val update = gson.fromJson(json, ContactsUpdateMessage::class.java)
                        listeners.forEach { it.onContactsUpdate(update.contacts) }
                    }
                    "error" -> {
                        val map = gson.fromJson(json, Map::class.java)
                        listeners.forEach {
                            it.onError(map["code"]?.toString() ?: "UNKNOWN", map["message"]?.toString() ?: "")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: $json", e)
        }
    }
}
