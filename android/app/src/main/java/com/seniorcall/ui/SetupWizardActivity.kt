package com.seniorcall.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.seniorcall.App
import com.seniorcall.R
import com.seniorcall.core.Prefs
import com.seniorcall.databinding.ActivitySetupWizardBinding
import com.seniorcall.signaling.MqttSignalingClient
import com.seniorcall.signaling.SignalingClient
import kotlin.random.Random

class SetupWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var prefs: Prefs
    private val gson = Gson()
    private var testSignalingClient: MqttSignalingClient? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQr(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        setupRoleSelection()
        setupSeniorMode()
        setupFamilyMode()

        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions()
            requestBatteryOptimizationExemption()
        }

        binding.btnFinishSetup.setOnClickListener {
            completeSetup()
        }

        // Initialize Senior QR code by default
        refreshSeniorQr()
    }

    private fun setupRoleSelection() {
        binding.rgRole.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbRoleSenior) {
                binding.layoutSeniorSetup.visibility = View.VISIBLE
                binding.layoutFamilySetup.visibility = View.GONE
                refreshSeniorQr()
            } else {
                binding.layoutSeniorSetup.visibility = View.GONE
                binding.layoutFamilySetup.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSeniorMode() {
        refreshSeniorQr()
    }

    private fun refreshSeniorQr() {
        val key = if (prefs.pairKey.isNotEmpty()) prefs.pairKey else "SC-" + String.format("%06d", Random.nextInt(100000, 999999))
        prefs.pairKey = key
        prefs.userId = "usr_elder"
        prefs.role = "senior"

        binding.tvSeniorPairCode.text = key

        // Generate clean QR code
        val qrPayload = gson.toJson(
            mapOf(
                "type" to "seniorcall",
                "key" to key,
                "role" to "elder"
            )
        )
        generateQrCode(qrPayload)

        // Connect temporary client to acknowledge when family pairs
        testSignalingClient?.disconnect()
        testSignalingClient = MqttSignalingClient(key, "senior", object : SignalingClient.Listener {
            override fun onPresence(presence: com.seniorcall.signaling.PresenceMessage) {
                if (presence.online) {
                    runOnUiThread {
                        binding.tvSeniorStatus.text = "🟢 Family Phone Connected & Paired!"
                        binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                        binding.tvSeniorStatus.setTextColor(Color.parseColor("#16A34A"))
                    }
                }
            }
        }).apply { connect() }
    }

    private fun setupFamilyMode() {
        binding.btnFamilyScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan QR Code on Elder's Phone Screen")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }

        binding.btnFamilyPairManual.setOnClickListener {
            val code = binding.etFamilyPairCode.text.toString().trim()
            if (code.isNotEmpty()) {
                applyFamilyPairing(code)
            } else {
                Toast.makeText(this, "Please enter a pair code", Toast.LENGTH_SHORT).show()
            }
        }

        if (prefs.pairKey.isNotEmpty() && prefs.role == "family") {
            binding.tvFamilyPairStatus.text = "✅ Already Paired (Code: ${prefs.pairKey})"
            binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
            binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#16A34A"))
        }
    }

    private fun handleScannedQr(contents: String) {
        val extractedKey = try {
            val map = gson.fromJson(contents, Map::class.java)
            map["key"]?.toString() ?: contents
        } catch (e: Exception) {
            contents
        }

        val cleanKey = extractedKey.trim()
        binding.etFamilyPairCode.setText(cleanKey)
        applyFamilyPairing(cleanKey)
    }

    private fun applyFamilyPairing(key: String) {
        prefs.pairKey = key
        prefs.pairedPeerId = "usr_elder"
        prefs.userId = "usr_family"
        prefs.role = "family"

        binding.tvFamilyPairStatus.text = "✅ Successfully Paired with Elder Phone ($key)"
        binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
        binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#16A34A"))

        Toast.makeText(this, "Paired with Elder! You can now complete setup.", Toast.LENGTH_LONG).show()

        // Notify elder immediately over MQTT
        testSignalingClient?.disconnect()
        testSignalingClient = MqttSignalingClient(key, "family").apply { connect() }
    }

    private fun generateQrCode(content: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 450, 450)
            binding.imgSeniorQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun completeSetup() {
        val isSenior = binding.rbRoleSenior.isChecked
        prefs.role = if (isSenior) "senior" else "family"

        if (!isSenior && prefs.pairKey.isEmpty()) {
            val manualCode = binding.etFamilyPairCode.text.toString().trim()
            if (manualCode.isNotEmpty()) {
                applyFamilyPairing(manualCode)
            } else {
                Toast.makeText(this, "Please scan Elder's QR code before completing setup", Toast.LENGTH_LONG).show()
                return
            }
        }

        testSignalingClient?.disconnect()
        testSignalingClient = null

        prefs.isSetupComplete = true
        (application as App).startCallServiceIfConfigured()

        if (isSenior) {
            startActivity(Intent(this, HomeActivity::class.java))
        } else {
            startActivity(Intent(this, FamilyMainActivity::class.java))
        }
        finish()
    }

    override fun onDestroy() {
        testSignalingClient?.disconnect()
        super.onDestroy()
    }
}
