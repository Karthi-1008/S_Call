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
import java.util.concurrent.TimeUnit
import kotlin.math.min

class SignalingClient(
    private val wsUrl: String,
    private val token: String,
    private val listener: Listener
) {
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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isClosedManually = false
    private var reconnectAttempt = 0

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            send(mapOf("t" to "ping"))
            mainHandler.postDelayed(this, 15_000)
        }
    }

    fun connect() {
        isClosedManually = false
        val urlWithToken = if (wsUrl.contains("?")) "$wsUrl&token=$token" else "$wsUrl?token=$token"
        val request = Request.Builder().url(urlWithToken).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket opened successfully")
                reconnectAttempt = 0
                mainHandler.post(heartbeatRunnable)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code / $reason")
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.post { listener.onDisconnected() }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket error: ${t.message}")
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.post { listener.onDisconnected() }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isClosedManually) return
        reconnectAttempt++
        // Exponential backoff capped at 5s (§13.2 / §9.3 A1)
        val delayMs = min(1000L * (1L shl min(reconnectAttempt, 3)), 5000L)
        Log.d(tag, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        mainHandler.postDelayed({ connect() }, delayMs)
    }

    fun send(msg: Any) {
        val json = if (msg is String) msg else gson.toJson(msg)
        webSocket?.send(json)
    }

    fun disconnect() {
        isClosedManually = true
        mainHandler.removeCallbacks(heartbeatRunnable)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val generic = gson.fromJson(json, GenericMessage::class.java) ?: return
            mainHandler.post {
                when (generic.t) {
                    "hello" -> listener.onConnected(gson.fromJson(json, HelloMessage::class.java))
                    "call_invite" -> listener.onCallInvite(gson.fromJson(json, CallInviteMessage::class.java))
                    "call_ringing" -> listener.onCallRinging(gson.fromJson(json, CallRingingMessage::class.java))
                    "call_accept" -> listener.onCallAccept(gson.fromJson(json, CallAcceptMessage::class.java))
                    "call_reject" -> listener.onCallReject(gson.fromJson(json, CallRejectMessage::class.java))
                    "offer" -> listener.onOffer(gson.fromJson(json, SdpMessage::class.java))
                    "answer" -> listener.onAnswer(gson.fromJson(json, SdpMessage::class.java))
                    "ice" -> listener.onIceCandidate(gson.fromJson(json, IceCandidateMessage::class.java))
                    "renegotiate" -> listener.onRenegotiate(gson.fromJson(json, RenegotiateMessage::class.java))
                    "screen_request" -> listener.onScreenRequest(gson.fromJson(json, ScreenRequestMessage::class.java))
                    "screen_request_reply" -> listener.onScreenRequestReply(gson.fromJson(json, ScreenRequestReplyMessage::class.java))
                    "screen_share_started", "screen_share_stopped" -> listener.onScreenShareState(gson.fromJson(json, ScreenShareStateMessage::class.java))
                    "call_end" -> listener.onCallEnd(gson.fromJson(json, CallEndMessage::class.java))
                    "presence" -> listener.onPresence(gson.fromJson(json, PresenceMessage::class.java))
                    "contacts_update" -> {
                        val update = gson.fromJson(json, ContactsUpdateMessage::class.java)
                        listener.onContactsUpdate(update.contacts)
                    }
                    "error" -> {
                        val map = gson.fromJson(json, Map::class.java)
                        listener.onError(map["code"]?.toString() ?: "UNKNOWN", map["message"]?.toString() ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: $json", e)
        }
    }
}
