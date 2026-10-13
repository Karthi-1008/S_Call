package com.seniorcall.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

class Sounds(private val context: Context) {
    private var ringtonePlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun enforceSeniorVolume() {
        try {
            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, (maxRing * 0.9).toInt(), 0)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVoice * 0.9).toInt(), 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startRingtone() {
        stopRingtone()
        try {
            enforceSeniorVolume()
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = MediaPlayer().apply {
                setDataSource(context, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRingtone() {
        try {
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            ringtonePlayer = null
        }
    }
}
