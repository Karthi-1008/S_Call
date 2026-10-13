package com.seniorcall.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build

class Net(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock = wm.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "SeniorCall:WifiLock"
    ).apply {
        setReferenceCounted(false)
    }

    fun acquireWifiLock() {
        try {
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun releaseWifiLock() {
        try {
            if (wifiLock.isHeld) {
                wifiLock.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun registerNetworkCallback(onAvailable: () -> Unit, onLost: () -> Unit) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        cm.bindProcessToNetwork(network)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                onAvailable()
            }

            override fun onLost(network: Network) {
                onLost()
            }
        })
    }
}
