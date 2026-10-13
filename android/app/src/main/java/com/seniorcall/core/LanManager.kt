package com.seniorcall.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object LanManager {
    private const val TAG = "LanManager"
    const val SERVICE_TYPE = "_seniorcall._tcp."
    const val DEFAULT_PORT = 8887

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (hostAddress.startsWith("192.168.") ||
                            hostAddress.startsWith("10.") ||
                            hostAddress.startsWith("172.")
                        ) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }
        return null
    }

    fun registerNsdService(context: Context, port: Int = DEFAULT_PORT, onRegistered: ((String) -> Unit)? = null) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        unregisterNsdService(context)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SeniorCallElder"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service registered: ${serviceInfo.serviceName}")
                Handler(Looper.getMainLooper()).post {
                    onRegistered?.invoke(serviceInfo.serviceName)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    fun unregisterNsdService(context: Context) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering NSD service", e)
            }
            registrationListener = null
        }
    }

    fun discoverElder(
        context: Context,
        timeoutMs: Long = 8000L,
        onFound: (ip: String, port: Int) -> Unit,
        onTimeout: () -> Unit
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            onTimeout()
            return
        }
        stopDiscovery(context)

        val handler = Handler(Looper.getMainLooper())
        var resolved = false

        val timeoutRunnable = Runnable {
            if (!resolved) {
                stopDiscovery(context)
                onTimeout()
            }
        }
        handler.postDelayed(timeoutRunnable, timeoutMs)

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "NSD Service found: ${service.serviceName}")
                if (service.serviceType.contains("seniorcall") || service.serviceName.contains("SeniorCall")) {
                    try {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e(TAG, "NSD Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress
                                val port = serviceInfo.port
                                Log.d(TAG, "NSD Resolved: $host:$port")
                                if (host != null && !resolved) {
                                    resolved = true
                                    handler.removeCallbacks(timeoutRunnable)
                                    handler.post {
                                        stopDiscovery(context)
                                        onFound(host, port)
                                    }
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resolving NSD service", e)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "NSD Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD Start discovery failed: $errorCode")
                stopDiscovery(context)
                handler.removeCallbacks(timeoutRunnable)
                handler.post { onTimeout() }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            handler.removeCallbacks(timeoutRunnable)
            onTimeout()
        }
    }

    fun stopDiscovery(context: Context) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NSD discovery", e)
            }
            discoveryListener = null
        }
    }
}
