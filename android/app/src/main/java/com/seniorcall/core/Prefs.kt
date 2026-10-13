package com.seniorcall.core

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("seniorcall_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SERVER_URL = "https://seniorcall-signaling.local"
    }

    var isSetupComplete: Boolean
        get() = sp.getBoolean("setup_complete", false)
        set(value) = sp.edit().putBoolean("setup_complete", value).apply()

    var role: String
        get() = sp.getString("user_role", "senior") ?: "senior"
        set(value) = sp.edit().putString("user_role", value).apply()

    var pairKey: String
        get() = sp.getString("pair_key", "") ?: ""
        set(value) = sp.edit().putString("pair_key", value).apply()

    var userId: String?
        get() = sp.getString("user_id", null)
        set(value) = sp.edit().putString("user_id", value).apply()

    var deviceId: String
        get() {
            var id = sp.getString("device_id", null)
            if (id.isNullOrEmpty()) {
                id = UUID.randomUUID().toString().replace("-", "").take(16)
                sp.edit().putString("device_id", id).apply()
            }
            return id
        }
        set(value) = sp.edit().putString("device_id", value).apply()

    var token: String?
        get() = sp.getString("jwt_token", "") ?: ""
        set(value) = sp.edit().putString("jwt_token", value).apply()

    var serverUrl: String
        get() = sp.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = sp.edit().putString("server_url", value).apply()

    var pairingCode: String?
        get() = sp.getString("pairing_code", null)
        set(value) = sp.edit().putString("pairing_code", value).apply()

    var kioskPin: String
        get() = sp.getString("kiosk_pin", "1234") ?: "1234"
        set(value) = sp.edit().putString("kiosk_pin", value).apply()

    var simpleMode: Boolean
        get() = sp.getBoolean("simple_mode", true)
        set(value) = sp.edit().putBoolean("simple_mode", value).apply()

    var pairedPeerId: String?
        get() = sp.getString("paired_peer_id", null)
        set(value) = sp.edit().putString("paired_peer_id", value).apply()

    val wsUrl: String
        get() {
            var url = serverUrl.trim()
            if (url.isEmpty()) {
                url = DEFAULT_SERVER_URL
            }
            if (url.startsWith("https://")) {
                url = url.replace("https://", "wss://")
            } else if (url.startsWith("http://")) {
                url = url.replace("http://", "ws://")
            } else if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
                url = "wss://$url"
            }
            if (!url.endsWith("/ws")) {
                url = if (url.endsWith("/")) "${url}ws" else "$url/ws"
            }
            return url
        }
}
