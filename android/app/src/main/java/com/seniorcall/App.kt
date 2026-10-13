package com.seniorcall

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.seniorcall.core.Prefs
import com.seniorcall.rtc.RtcEngine
import com.seniorcall.service.CallService

class App : Application() {
    lateinit var rtcEngine: RtcEngine
        private set

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        rtcEngine = RtcEngine(this)

        setupCrashHandler()
        startCallServiceIfConfigured()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SeniorCallApp", "Uncaught exception: ${throwable.message}", throwable)
            try {
                // Auto-recover by launching CallService (§9.3 A7)
                val intent = Intent(this, CallService::class.java).apply {
                    action = CallService.ACTION_START_ALWAYS_ON
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun startCallServiceIfConfigured() {
        if (prefs.isSetupComplete) {
            val intent = Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_START_ALWAYS_ON
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
