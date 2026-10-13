package com.seniorcall.rtc

import android.os.Handler
import android.os.Looper
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport

class QualityMonitor(
    private val peerConnection: PeerConnection,
    private val callback: Callback
) {
    interface Callback {
        fun onQualityChanged(dots: String, qualityScore: Int)
        fun onLowBandwidthFallback()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var consecutiveLowBitrateCount = 0

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            peerConnection.getStats { report: RTCStatsReport? ->
                report?.let { parseStats(it) }
            }
            handler.postDelayed(this, 2000)
        }
    }

    fun start() {
        isRunning = true
        handler.post(monitorRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(monitorRunnable)
    }

    private fun parseStats(report: RTCStatsReport) {
        var rtt = 100.0
        var packetLossRate = 0.0
        var currentBitrateKbps = 800.0

        for (stats in report.statsMap.values) {
            if (stats.type == "candidate-pair" && stats.members["state"] == "succeeded") {
                val roundTrip = stats.members["currentRoundTripTime"] as? Double
                if (roundTrip != null) {
                    rtt = roundTrip * 1000.0
                }
            }
            if (stats.type == "inbound-rtp") {
                val packetsLost = (stats.members["packetsLost"] as? Number)?.toDouble() ?: 0.0
                val packetsReceived = (stats.members["packetsReceived"] as? Number)?.toDouble() ?: 1.0
                if (packetsReceived > 0) {
                    packetLossRate = (packetsLost / (packetsLost + packetsReceived)) * 100.0
                }
            }
        }

        val dots = when {
            rtt < 120 && packetLossRate < 1.0 -> "●●●●●"
            rtt < 250 && packetLossRate < 4.0 -> "●●●●○"
            rtt < 450 && packetLossRate < 8.0 -> "●●●○○"
            rtt < 700 && packetLossRate < 15.0 -> "●●○○○"
            else -> "●○○○○"
        }

        val score = when (dots) {
            "●●●●●" -> 5
            "●●●●○" -> 4
            "●●●○○" -> 3
            "●●○○○" -> 2
            else -> 1
        }

        handler.post {
            callback.onQualityChanged(dots, score)
        }

        // Auto-drop to voice-only if bitrate < 120 kbps for 3 consecutive intervals (§11.4)
        if (currentBitrateKbps < 120.0) {
            consecutiveLowBitrateCount++
            if (consecutiveLowBitrateCount >= 3) {
                handler.post { callback.onLowBandwidthFallback() }
                consecutiveLowBitrateCount = 0
            }
        } else {
            consecutiveLowBitrateCount = 0
        }
    }
}
