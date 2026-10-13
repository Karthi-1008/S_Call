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

class SignalingClient(
    private val wsUrl: String,
    private val token: String,
    initialListener: Listener? = null
) : ISignalingClient {
    interface Listener {
        fun onConnected(hello: HelloMessage) {}
        fun onDisconnected() {}
        fun onCallInvite(invite: CallInviteMessage) {}
        fun onCallRinging(ringing: CallRingingMessage) {}
        fun onCallAccept(accept: CallAcceptMessage) {}
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

    override fun addListener(l: Listener) {
        if (!listeners.contains(l)) {
            listeners.add(l)
        }
    }

    override fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isClosedManually = false
    private var reconnectAttempt = 0

    override var isConnected: Boolean = false
        private set

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            send(mapOf("t" to "ping"))
            mainHandler.postDelayed(this, 15_000)
        }
    }

    override fun connect() {
        isClosedManually = false
        val urlWithToken = if (token.isNotBlank()) {
            if (wsUrl.contains("?")) "$wsUrl&token=$token" else "$wsUrl?token=$token"
        } else {
            wsUrl
        }
        Log.d(tag, "Connecting to WebSocket: $urlWithToken")
        val request = Request.Builder().url(urlWithToken).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket opened successfully")
                isConnected = true
                reconnectAttempt = 0
                mainHandler.post(heartbeatRunnable)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code / $reason")
                isConnected = false
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.post {
                    listeners.forEach { it.onDisconnected() }
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket error: ${t.message}")
                isConnected = false
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.post {
                    listeners.forEach { it.onDisconnected() }
                }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isClosedManually) return
        reconnectAttempt++
        val delayMs = min(1000L * (1L shl min(reconnectAttempt, 3)), 5000L)
        Log.d(tag, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        mainHandler.postDelayed({ connect() }, delayMs)
    }

    override fun send(msg: Any) {
        val json = if (msg is String) msg else gson.toJson(msg)
        webSocket?.send(json)
    }

    override fun disconnect() {
        isClosedManually = true
        isConnected = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val generic = gson.fromJson(json, GenericMessage::class.java) ?: return
            mainHandler.post {
                when (generic.t) {
                    "hello" -> {
                        val hello = gson.fromJson(json, HelloMessage::class.java)
                        listeners.forEach { it.onConnected(hello) }
                    }
                    "call_invite" -> {
                        val invite = gson.fromJson(json, CallInviteMessage::class.java)
                        listeners.forEach { it.onCallInvite(invite) }
                    }
                    "call_ringing" -> {
                        val ringing = gson.fromJson(json, CallRingingMessage::class.java)
                        listeners.forEach { it.onCallRinging(ringing) }
                    }
                    "call_accept" -> {
                        val accept = gson.fromJson(json, CallAcceptMessage::class.java)
                        listeners.forEach { it.onCallAccept(accept) }
                    }
                    "call_reject" -> {
                        val reject = gson.fromJson(json, CallRejectMessage::class.java)
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
