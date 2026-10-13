package com.seniorcall.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("seniorcall_prefs", Context.MODE_PRIVATE)

    var isSetupComplete: Boolean
        get() = sp.getBoolean("setup_complete", false)
        set(value) = sp.edit().putBoolean("setup_complete", value).apply()

    var role: String
        get() = sp.getString("user_role", "senior") ?: "senior"
        set(value) = sp.edit().putString("user_role", value).apply()

    var userId: String?
        get() = sp.getString("user_id", null)
        set(value) = sp.edit().putString("user_id", value).apply()

    var token: String?
        get() = sp.getString("jwt_token", null)
        set(value) = sp.edit().putString("jwt_token", value).apply()

    var serverUrl: String
        get() = sp.getString("server_url", "http://10.0.2.2:8080") ?: "http://10.0.2.2:8080"
        set(value) = sp.edit().putString("server_url", value).apply()

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
            val base = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
            return "$base/ws"
        }
}
