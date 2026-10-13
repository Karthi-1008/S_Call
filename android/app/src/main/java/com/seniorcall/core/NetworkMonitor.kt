package com.seniorcall.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class NetworkMonitor(private val context: Context) {
    private val tag = "NetworkMonitor"
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val handler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var isConnected = false

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock = wm.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "SeniorCall:HighPerfWifi"
    ).apply {
        setReferenceCounted(false)
    }

    interface Listener {
        fun onNetworkAvailable()
        fun onNetworkLost()
    }

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        this.listener = l
    }

    fun start() {
        acquireWifiLock()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register network callback", e)
        }
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(tag, "Failed to unregister network callback", e)
        }
        releaseWifiLock()
    }

    fun isOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun acquireWifiLock() {
        try {
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
                Log.d(tag, "WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to acquire WifiLock", e)
        }
    }

    fun releaseWifiLock() {
        try {
            if (wifiLock.isHeld) {
                wifiLock.release()
                Log.d(tag, "WifiLock released")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to release WifiLock", e)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Note: Never call cm.bindProcessToNetwork(network) as per Spec v2 §4.2!
            scheduleDebouncedNotify(true)
        }

        override fun onLost(network: Network) {
            scheduleDebouncedNotify(false)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            scheduleDebouncedNotify(hasInternet)
        }
    }

    private fun scheduleDebouncedNotify(available: Boolean) {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (isConnected != available) {
                isConnected = available
                Log.i(tag, "Network status changed: connected=$isConnected")
                if (isConnected) {
                    listener?.onNetworkAvailable()
                } else {
                    listener?.onNetworkLost()
                }
            }
        }
        debounceRunnable = r
        handler.postDelayed(r, 500) // 500ms debounce
    }
}
