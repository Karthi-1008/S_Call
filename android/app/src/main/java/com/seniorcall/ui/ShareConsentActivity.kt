package com.seniorcall.ui

import android.app.Activity
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.seniorcall.R
import com.seniorcall.core.Haptics
import com.seniorcall.core.Tts
import com.seniorcall.databinding.ActivityShareConsentBinding

class ShareConsentActivity : AppCompatActivity() {
    private lateinit var binding: ActivityShareConsentBinding
    private lateinit var haptics: Haptics
    private lateinit var tts: Tts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        haptics = Haptics(this)
        tts = Tts(this)

        val callerName = intent.getStringExtra("callerName") ?: "Family"
        binding.tvConsentTitle.text = getString(R.string.screen_request_prompt, callerName)

        // Read explanation out loud to senior (§8.3 D)
        tts.speak("${binding.tvConsentTitle.text} ${getString(R.string.screen_request_instructions)}")

        binding.btnConsentYes.setOnClickListener {
            haptics.tick()
            tts.stop()
            setResult(Activity.RESULT_OK)
            finish()
        }

        binding.btnConsentNo.setOnClickListener {
            haptics.tick()
            tts.stop()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}
