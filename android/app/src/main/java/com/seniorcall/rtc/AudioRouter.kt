package com.seniorcall.rtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class AudioRouter(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalMode = audioManager.mode
    private var originalSpeakerphoneOn = audioManager.isSpeakerphoneOn

    fun startCallAudio() {
        originalMode = audioManager.mode
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true // Loudspeaker defaults ON for senior (§8.3 C)

        enforceCallVolume()
    }

    fun enforceCallVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.9).toInt(), 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun increaseVolume() {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isBluetoothHeadsetConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        }
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothScoOn
    }

    fun stopCallAudio() {
        try {
            audioManager.isSpeakerphoneOn = originalSpeakerphoneOn
            audioManager.mode = originalMode
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
