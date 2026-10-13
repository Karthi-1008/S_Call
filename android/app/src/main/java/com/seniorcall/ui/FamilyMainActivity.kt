package com.seniorcall.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seniorcall.core.Prefs
import com.seniorcall.databinding.ActivityFamilyMainBinding
import com.seniorcall.service.CallService
import com.seniorcall.signaling.ScreenRequestMessage

class FamilyMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFamilyMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        val elderId = prefs.pairedPeerId ?: "usr_elder"

        binding.btnFamilyCallVideo.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("isIncoming", false)
                putExtra("targetUserId", elderId)
                putExtra("targetName", "Elder Phone")
                putExtra("media", "video")
            }
            startActivity(intent)
        }

        binding.btnFamilyCallVoice.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("isIncoming", false)
                putExtra("targetUserId", elderId)
                putExtra("targetName", "Elder Phone")
                putExtra("media", "audio")
            }
            startActivity(intent)
        }

        binding.btnFamilyRequestScreen.setOnClickListener {
            CallService.instance?.signalingClient?.send(
                ScreenRequestMessage(
                    callId = "call_${System.currentTimeMillis()}",
                    from = prefs.userId ?: ""
                )
            )
            Toast.makeText(this, "Screen share requested from Elder", Toast.LENGTH_SHORT).show()
        }
    }
}
