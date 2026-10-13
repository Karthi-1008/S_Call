package com.seniorcall.core

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class Tts(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val pendingUtterances = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isReady = true
            synchronized(pendingUtterances) {
                pendingUtterances.forEach { speak(it) }
                pendingUtterances.clear()
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!isReady) {
            synchronized(pendingUtterances) {
                pendingUtterances.add(text)
            }
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, "SeniorCall_TTS_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
