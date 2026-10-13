package com.seniorcall.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.seniorcall.R
import com.seniorcall.core.Haptics
import com.seniorcall.databinding.ActivityIncomingCallBinding
import com.seniorcall.service.CallService
import com.seniorcall.signaling.CallAcceptMessage
import com.seniorcall.signaling.CallRejectMessage

class IncomingCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIncomingCallBinding
    private lateinit var haptics: Haptics
    private var callId: String = ""
    private var callerName: String = "Family"
    private var mediaType: String = "video"
    private var autoAnswer: Boolean = false
    private var autoAnswerTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unlockScreen()

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        haptics = Haptics(this)

        callId = intent.getStringExtra("callId") ?: ""
        callerName = intent.getStringExtra("callerName") ?: "Family"
        mediaType = intent.getStringExtra("media") ?: "video"
        autoAnswer = intent.getBooleanExtra("autoAnswer", false)

        setupUI()
        handleAutoAnswerCountdown()
    }

    private fun unlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun setupUI() {
        binding.tvCallerName.text = callerName.uppercase()
        binding.tvCallType.text = if (mediaType == "video") "Video Call" else "Voice Call"

        binding.btnAnswerCall.setOnClickListener {
            haptics.tick()
            autoAnswerTimer?.cancel()
            answerCall()
        }

        binding.btnDeclineCall.setOnClickListener {
            haptics.tick()
            autoAnswerTimer?.cancel()
            declineCall()
        }

        binding.btnStopAutoAnswer.setOnClickListener {
            haptics.tick()
            autoAnswerTimer?.cancel()
            binding.layoutAutoAnswer.visibility = View.GONE
        }
    }

    private fun handleAutoAnswerCountdown() {
        if (!autoAnswer) return

        binding.layoutAutoAnswer.visibility = View.VISIBLE
        autoAnswerTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt() + 1
                binding.tvAutoAnswerText.text = getString(R.string.auto_answer_countdown, callerName, seconds)
            }

            override fun onFinish() {
                answerCall()
            }
        }.start()
    }

    private fun answerCall() {
        val service = CallService.instance
        service?.stopRingingAlerts()
        service?.promoteToMediaForeground()

        // Send accept to signaling
        service?.signalingClient?.send(CallAcceptMessage(callId = callId))

        val callIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("isIncoming", true)
            putExtra("callId", callId)
            putExtra("targetName", callerName)
            putExtra("media", mediaType)
        }
        startActivity(callIntent)
        finish()
    }

    private fun declineCall() {
        val service = CallService.instance
        service?.stopRingingAlerts()
        service?.signalingClient?.send(CallRejectMessage(callId = callId, reason = "declined"))
        finish()
    }

    override fun onDestroy() {
        autoAnswerTimer?.cancel()
        super.onDestroy()
    }
}
