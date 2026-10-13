package com.seniorcall.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.seniorcall.data.Contact
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class MqttSignalingClient(
    private val pairKey: String,
    private val role: String,
    initialListener: SignalingClient.Listener? = null
) : ISignalingClient {
    companion object {
        private const val TAG = "MqttSignaling"
        private const val BROKER_URL = "tcp://broker.emqx.io:1883"
    }

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<SignalingClient.Listener>()

    private val isSenior = (role == "senior" || role == "elder")
    private val myRoleName = if (isSenior) "elder" else "family"
    private val peerRoleName = if (isSenior) "family" else "elder"

    val myTopic = "seniorcall/$pairKey/$myRoleName"
    val peerTopic = "seniorcall/$pairKey/$peerRoleName"

    private var mqttClient: MqttAsyncClient? = null

    override var isConnected: Boolean = false
        private set

    init {
        initialListener?.let { listeners.add(it) }
    }

    override fun addListener(l: SignalingClient.Listener) {
        if (!listeners.contains(l)) {
            listeners.add(l)
        }
    }

    override fun removeListener(l: SignalingClient.Listener) {
        listeners.remove(l)
    }

    override fun connect() {
        val clientId = "sc_${myRoleName}_${System.currentTimeMillis()}"
        try {
            mqttClient?.disconnect()
        } catch (e: Exception) {
            // ignore
        }

        try {
            val client = MqttAsyncClient(BROKER_URL, clientId, MemoryPersistence())
            mqttClient = client

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
                isAutomaticReconnect = true

                // Last Will and Testament: Notify peer if we drop offline
                val lwtJson = gson.toJson(
                    mapOf(
                        "t" to "presence",
                        "userId" to if (isSenior) "usr_elder" else "usr_family",
                        "online" to false
                    )
                )
                setWill(peerTopic, lwtJson.toByteArray(StandardCharsets.UTF_8), 1, false)
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "MQTT Connected to $serverURI (reconnect=$reconnect)")
                    isConnected = true

                    // Subscribe to my topic
                    try {
                        client.subscribe(myTopic, 1)
                        Log.d(TAG, "Subscribed to $myTopic")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to subscribe to $myTopic", e)
                    }

                    // Announce online presence to peer
                    sendPresence(true)

                    mainHandler.post {
                        val hello = HelloMessage(
                            t = "hello",
                            turn = emptyList(),
                            contacts = listOf(
                                Contact(
                                    id = if (isSenior) "usr_family" else "usr_elder",
                                    name = if (isSenior) "Family" else "Elder Phone",
                                    role = peerRoleName,
                                    priority = 1,
                                    autoAnswerAllowed = true
                                )
                            ),
                            userId = if (isSenior) "usr_elder" else "usr_family"
                        )
                        listeners.forEach { it.onConnected(hello) }
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT Connection lost: ${cause?.message}")
                    isConnected = false
                    mainHandler.post {
                        listeners.forEach { it.onDisconnected() }
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.let { String(it, StandardCharsets.UTF_8) } ?: return
                    Log.d(TAG, "Incoming message on $topic: $payload")
                    handleIncomingMessage(payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client.connect(options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate MQTT connection", e)
            isConnected = false
            mainHandler.post {
                listeners.forEach { it.onDisconnected() }
            }
        }
    }

    fun sendPresence(online: Boolean) {
        val msg = mapOf(
            "t" to "presence",
            "userId" to if (isSenior) "usr_elder" else "usr_family",
            "online" to online,
            "lastSeen" to System.currentTimeMillis()
        )
        send(msg)
    }

    override fun send(msg: Any) {
        val json = if (msg is String) msg else gson.toJson(msg)
        try {
            val mqttMsg = MqttMessage(json.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = 1
                isRetained = false
            }
            mqttClient?.publish(peerTopic, mqttMsg)
            Log.d(TAG, "Published to $peerTopic: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish to $peerTopic", e)
        }
    }

    override fun disconnect() {
        try {
            sendPresence(false)
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting MQTT", e)
        }
        mqttClient = null
        isConnected = false
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
            Log.e(TAG, "Failed to parse message: $json", e)
        }
    }
}
