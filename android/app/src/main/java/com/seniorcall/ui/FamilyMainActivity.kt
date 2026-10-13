package com.seniorcall.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seniorcall.core.Prefs
import com.seniorcall.databinding.ActivityFamilyMainBinding
import com.seniorcall.service.CallService
import com.seniorcall.signaling.*

class FamilyMainActivity : AppCompatActivity(), SignalingClient.Listener {
    private lateinit var binding: ActivityFamilyMainBinding
    private lateinit var prefs: Prefs

    private var isElderOnline: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        val elderId = prefs.pairedPeerId ?: "usr_elder"
        binding.tvPairedInfo.text = "Paired with: Elder ($elderId)"

        binding.btnFamilyCallVideo.setOnClickListener {
            if (prefs.pairedPeerId.isNullOrEmpty()) {
                Toast.makeText(this, "Please pair with Elder phone first", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SetupWizardActivity::class.java))
                return@setOnClickListener
            }
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("isIncoming", false)
                putExtra("targetUserId", elderId)
                putExtra("targetName", "Elder Phone")
                putExtra("media", "video")
            }
            startActivity(intent)
        }

        binding.btnFamilyCallVoice.setOnClickListener {
            if (prefs.pairedPeerId.isNullOrEmpty()) {
                Toast.makeText(this, "Please pair with Elder phone first", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SetupWizardActivity::class.java))
                return@setOnClickListener
            }
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("isIncoming", false)
                putExtra("targetUserId", elderId)
                putExtra("targetName", "Elder Phone")
                putExtra("media", "audio")
            }
            startActivity(intent)
        }

        binding.btnFamilyRequestScreen.setOnClickListener {
            if (prefs.pairedPeerId.isNullOrEmpty()) {
                Toast.makeText(this, "Please pair with Elder phone first", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, SetupWizardActivity::class.java))
                return@setOnClickListener
            }
            // Start video call with screen share prompt
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("isIncoming", false)
                putExtra("targetUserId", elderId)
                putExtra("targetName", "Elder Phone")
                putExtra("media", "video")
            }
            startActivity(intent)
            Toast.makeText(this, "Calling Elder to request screen share...", Toast.LENGTH_SHORT).show()
        }

        binding.btnRepair.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        CallService.instance?.addSignalingListener(this)
        CallService.instance?.ensureConnected()
        updateStatusDisplay()
    }

    override fun onPause() {
        CallService.instance?.removeSignalingListener(this)
        super.onPause()
    }

    private fun updateStatusDisplay() {
        val service = CallService.instance
        val isConnected = service?.signalingClient?.isConnected == true

        if (!isConnected) {
            binding.tvFamilyElderStatus.text = "🔴 Disconnected from Server / Wi-Fi"
            binding.tvFamilyElderStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
            binding.tvFamilyElderStatus.setTextColor(Color.parseColor("#DC2626"))
        } else {
            if (isElderOnline) {
                binding.tvFamilyElderStatus.text = "🟢 Elder Status: Online"
                binding.tvFamilyElderStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                binding.tvFamilyElderStatus.setTextColor(Color.parseColor("#16A34A"))
            } else {
                binding.tvFamilyElderStatus.text = "🔴 Elder Status: Offline"
                binding.tvFamilyElderStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                binding.tvFamilyElderStatus.setTextColor(Color.parseColor("#DC2626"))
            }
        }
    }

    // SignalingClient.Listener Callbacks
    override fun onConnected(hello: HelloMessage) {
        runOnUiThread {
            updateStatusDisplay()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            isElderOnline = false
            updateStatusDisplay()
        }
    }

    override fun onPresence(presence: PresenceMessage) {
        runOnUiThread {
            val elderId = prefs.pairedPeerId ?: "usr_elder"
            if (presence.userId == elderId || presence.userId == "usr_elder") {
                isElderOnline = presence.online
                updateStatusDisplay()
            }
        }
    }
}
