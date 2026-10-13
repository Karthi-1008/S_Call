package com.seniorcall.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class LocalSignalingServer(port: Int = 8887) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "LocalSignalingServer"

        var instance: LocalSignalingServer? = null
            private set

        fun startInstance(port: Int = 8887, pairingCode: String): LocalSignalingServer {
            instance?.stopServer()
            val server = LocalSignalingServer(port).apply {
                this.pairingCode = pairingCode
                isReuseAddr = true
                start()
            }
            instance = server
            Log.d(TAG, "LocalSignalingServer started on port $port with code $pairingCode")
            return server
        }

        fun stopInstance() {
            instance?.stopServer()
            instance = null
        }
    }

    var pairingCode: String = "123456"
    var onPairListener: ((clientIp: String) -> Unit)? = null
    var onPresenceChangeListener: ((isFamilyOnline: Boolean) -> Unit)? = null

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var seniorSocket: WebSocket? = null
        private set

    @Volatile
    var familySocket: WebSocket? = null
        private set

    override fun onStart() {
        Log.d(TAG, "WebSocketServer successfully started on $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val path = handshake.resourceDescriptor ?: ""
        Log.d(TAG, "New connection from ${conn.remoteSocketAddress} descriptor: $path")

        // If connection is from local senior client (e.g. 127.0.0.1 or token=senior)
        if (path.contains("senior") || conn.remoteSocketAddress?.address?.isLoopbackAddress == true) {
            seniorSocket = conn
            val hello = mapOf(
                "t" to "hello",
                "turn" to emptyList<Any>(),
                "contacts" to listOf(mapOf("id" to "usr_family", "name" to "Family Member", "priority" to 1, "autoAnswer" to true)),
                "userId" to "usr_elder",
                "serverTime" to System.currentTimeMillis()
            )
            conn.send(gson.toJson(hello))

            // Notify elder of family presence
            val isFamOnline = familySocket?.isOpen == true
            val pres = mapOf("t" to "presence", "userId" to "usr_family", "online" to isFamOnline)
            conn.send(gson.toJson(pres))
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Connection closed: ${conn.remoteSocketAddress}, code=$code, reason=$reason")
        if (conn == familySocket) {
            familySocket = null
            seniorSocket?.let {
                if (it.isOpen) {
                    it.send(gson.toJson(mapOf("t" to "presence", "userId" to "usr_family", "online" to false)))
                }
            }
            mainHandler.post { onPresenceChangeListener?.invoke(false) }
        } else if (conn == seniorSocket) {
            seniorSocket = null
            familySocket?.let {
                if (it.isOpen) {
                    it.send(gson.toJson(mapOf("t" to "presence", "userId" to "usr_elder", "online" to false)))
                }
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val map = gson.fromJson(message, Map::class.java) ?: return
            val type = map["t"] as? String ?: return

            when (type) {
                "pair" -> {
                    val code = map["code"] as? String ?: ""
                    if (code == pairingCode) {
                        familySocket = conn
                        Log.d(TAG, "Pairing successful for family client: ${conn.remoteSocketAddress}")

                        val reply = mapOf(
                            "t" to "pair_success",
                            "elderId" to "usr_elder",
                            "userId" to "usr_family"
                        )
                        conn.send(gson.toJson(reply))

                        val hello = mapOf(
                            "t" to "hello",
                            "turn" to emptyList<Any>(),
                            "contacts" to listOf(mapOf("id" to "usr_elder", "name" to "Elder Phone", "priority" to 1)),
                            "userId" to "usr_family",
                            "serverTime" to System.currentTimeMillis()
                        )
                        conn.send(gson.toJson(hello))

                        // Broadcast mutual presence
                        conn.send(gson.toJson(mapOf("t" to "presence", "userId" to "usr_elder", "online" to true)))
                        seniorSocket?.let {
                            if (it.isOpen) {
                                it.send(gson.toJson(mapOf("t" to "presence", "userId" to "usr_family", "online" to true)))
                            }
                        }

                        val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "Family"
                        mainHandler.post {
                            onPairListener?.invoke(ip)
                            onPresenceChangeListener?.invoke(true)
                        }
                    } else {
                        Log.w(TAG, "Pairing failed: expected $pairingCode but got $code")
                        val err = mapOf(
                            "t" to "error",
                            "code" to "INVALID_PIN",
                            "message" to "Pairing PIN does not match. Please verify the code."
                        )
                        conn.send(gson.toJson(err))
                    }
                }

                "ping" -> {
                    conn.send(gson.toJson(mapOf("t" to "pong", "serverTime" to System.currentTimeMillis())))
                }

                "pong" -> {
                    // Heartbeat acknowledged
                }

                // Signaling message relays
                "call_invite", "call_ringing", "call_accept", "call_reject",
                "offer", "answer", "ice", "renegotiate",
                "screen_request", "screen_request_reply",
                "screen_share_started", "screen_share_stopped", "call_end" -> {
                    val target = if (conn == seniorSocket) familySocket else seniorSocket
                    if (target != null && target.isOpen) {
                        target.send(message)
                        Log.d(TAG, "Relayed $type from ${conn.remoteSocketAddress} to ${target.remoteSocketAddress}")
                    } else {
                        Log.w(TAG, "Cannot relay $type: destination socket not connected")
                        if (type == "call_invite") {
                            val err = mapOf(
                                "t" to "error",
                                "code" to "CALLEE_OFFLINE",
                                "message" to "The other device is not currently connected."
                            )
                            conn.send(gson.toJson(err))
                        }
                    }
                }

                else -> {
                    Log.d(TAG, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Server error on connection ${conn?.remoteSocketAddress}", ex)
    }

    fun stopServer() {
        try {
            stop(1000)
            Log.d(TAG, "LocalSignalingServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}
