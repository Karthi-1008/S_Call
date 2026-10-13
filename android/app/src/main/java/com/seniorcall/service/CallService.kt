package com.seniorcall.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seniorcall.App
import com.seniorcall.R
import com.seniorcall.core.*
import com.seniorcall.data.Contact
import com.seniorcall.data.ContactStore
import com.seniorcall.rtc.AudioRouter
import com.seniorcall.rtc.CallSession
import com.seniorcall.rtc.RtcEngine
import com.seniorcall.signaling.*
import com.seniorcall.ui.CallActivity
import com.seniorcall.ui.IncomingCallActivity
import org.webrtc.PeerConnection

class CallService : Service(), SignalingClient.Listener {
    companion object {
        const val ACTION_START_ALWAYS_ON = "com.seniorcall.action.START_ALWAYS_ON"
        const val ACTION_STOP = "com.seniorcall.action.STOP"

        const val NOTIFICATION_ID_SERVICE = 1001
        const val NOTIFICATION_ID_CALL = 1002
        const val CHANNEL_ID_SERVICE = "seniorcall_service"
        const val CHANNEL_ID_CALL = "seniorcall_calls"

        var instance: CallService? = null
            private set
    }

    private val tag = "CallService"
    lateinit var prefs: Prefs
    lateinit var net: Net
    lateinit var tts: Tts
    lateinit var haptics: Haptics
    lateinit var sounds: Sounds
    lateinit var contactStore: ContactStore
    lateinit var audioRouter: AudioRouter

    var signalingClient: ISignalingClient? = null
    var activeCallSession: CallSession? = null
    var currentInvite: CallInviteMessage? = null
    var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelay")
            .setPassword("openrelay")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelay")
            .setPassword("openrelay")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelay")
            .setPassword("openrelay")
            .createIceServer()
    )

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        net = Net(this)
        tts = Tts(this)
        haptics = Haptics(this)
        sounds = Sounds(this)
        contactStore = ContactStore(this)
        audioRouter = AudioRouter(this)

        createNotificationChannels()
        acquireWakeLock()
        net.acquireWifiLock()

        Watchdog.setupWatchdog(this)

        net.registerNetworkCallback(
            onAvailable = {
                Log.d(tag, "Network available, ensuring WebSocket/MQTT connected")
                ensureConnected()
            },
            onLost = {
                Log.d(tag, "Network lost")
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        ensureConnected()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        releaseWakeLock()
        net.releaseWifiLock()
        signalingClient?.disconnect()
        LocalSignalingServer.stopInstance()
        LanManager.unregisterNsdService(this)
        sounds.stopRingtone()
        haptics.stopRingingVibration()
        tts.shutdown()
        super.onDestroy()
    }

    fun ensureConnected() {
        if (prefs.pairKey.isNotEmpty()) {
            if (signalingClient == null) {
                signalingClient = MqttSignalingClient(prefs.pairKey, prefs.role, this)
                signalingClient?.connect()
            } else if (signalingClient?.isConnected == false) {
                signalingClient?.connect()
            }
            return
        }

        // Fallback for custom server or local mode
        if (prefs.role == "senior" && prefs.isLanMode) {
            val code = prefs.pairingCode ?: "123456"
            if (LocalSignalingServer.instance == null) {
                LocalSignalingServer.startInstance(8887, code)
                LanManager.registerNsdService(this, 8887)
            }
        }

        val token = prefs.token ?: "lan"
        if (signalingClient == null) {
            signalingClient = SignalingClient(prefs.wsUrl, token, this)
            signalingClient?.connect()
        } else if (signalingClient?.isConnected == false) {
            signalingClient?.connect()
        }
    }

    fun addSignalingListener(l: SignalingClient.Listener) {
        signalingClient?.addListener(l)
    }

    fun removeSignalingListener(l: SignalingClient.Listener) {
        signalingClient?.removeListener(l)
    }

    private fun startForegroundWithNotification() {
        val notification = createServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification)
        }
    }

    fun promoteToMediaForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification(), type)
        }
    }

    // Signaling Listener Callbacks
    override fun onConnected(hello: HelloMessage) {
        Log.d(tag, "Signaling connected. Contacts: ${hello.contacts.size}")
        if (hello.contacts.isNotEmpty()) {
            contactStore.saveContacts(hello.contacts)
        }
        if (hello.turn.isNotEmpty()) {
            iceServers = hello.turn.flatMap { turn ->
                turn.urls.map { url ->
                    val builder = PeerConnection.IceServer.builder(url)
                    if (turn.username != null) builder.setUsername(turn.username)
                    if (turn.credential != null) builder.setPassword(turn.credential)
                    builder.createIceServer()
                }
            }
        }
    }

    override fun onCallInvite(invite: CallInviteMessage) {
        Log.d(tag, "Incoming call invite from ${invite.callerName ?: invite.from}")
        currentInvite = invite

        val callerName = invite.callerName ?: "Family Member"

        // 1. Full-screen intent notification (§14.3)
        showIncomingCallNotification(invite)

        // 2. Ringtone & Vibration & TTS
        sounds.startRingtone()
        haptics.startRingingVibration()
        tts.speak("$callerName is calling. Press the green button to answer.")
    }

    override fun onCallEnd(end: CallEndMessage) {
        stopRingingAlerts()
        activeCallSession?.dispose()
        activeCallSession = null
        currentInvite = null
    }

    fun stopRingingAlerts() {
        sounds.stopRingtone()
        haptics.stopRingingVibration()
        tts.stop()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID_CALL)
    }

    private fun showIncomingCallNotification(invite: CallInviteMessage) {
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("callId", invite.callId)
            putExtra("callerName", invite.callerName ?: "Family")
            putExtra("media", invite.media)
            putExtra("autoAnswer", invite.autoAnswerAllowed)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 100, fullScreenIntent, flags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming Call")
            .setContentText("${invite.callerName ?: "Family"} is calling")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_CALL, notification)

        // Launch activity directly as well
        startActivity(fullScreenIntent)
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("SeniorCall Active")
            .setContentText("Ready to receive calls")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val callChannel = NotificationChannel(
                CHANNEL_ID_CALL,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority full-screen incoming calls"
                enableVibration(true)
            }

            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "SeniorCall Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Always-on connection status"
            }

            nm.createNotificationChannel(callChannel)
            nm.createNotificationChannel(serviceChannel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeniorCall:CallServiceWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // Safe partial wakelock
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
