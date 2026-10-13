package com.seniorcall.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.seniorcall.core.Haptics
import com.seniorcall.databinding.ActivityPostCallBinding

class PostCallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPostCallBinding
    private lateinit var haptics: Haptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        haptics = Haptics(this)

        val peerName = intent.getStringExtra("peerName") ?: "Family"
        val peerId = intent.getStringExtra("peerId") ?: ""
        val media = intent.getStringExtra("media") ?: "video"

        binding.tvPostCallPeerName.text = peerName.uppercase()

        binding.btnCallBack.setOnClickListener {
            haptics.tick()
            val callIntent = Intent(this, CallActivity::class.java).apply {
                putExtra("isIncoming", false)
                putExtra("targetUserId", peerId)
                putExtra("targetName", peerName)
                putExtra("media", media)
            }
            startActivity(callIntent)
            finish()
        }

        binding.btnPostCallOk.setOnClickListener {
            haptics.tick()
            finish()
        }
    }
}
