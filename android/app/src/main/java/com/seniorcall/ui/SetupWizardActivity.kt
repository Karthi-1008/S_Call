package com.seniorcall.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.seniorcall.App
import com.seniorcall.R
import com.seniorcall.core.Prefs
import com.seniorcall.databinding.ActivitySetupWizardBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SetupWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var prefs: Prefs
    private val httpClient = OkHttpClient()
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        binding.etServerUrl.setText(prefs.serverUrl)

        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions()
            requestBatteryOptimizationExemption()
        }

        binding.rgRole.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbRoleSenior) {
                binding.layoutPairing.visibility = View.VISIBLE
            } else {
                binding.layoutPairing.visibility = View.GONE
            }
        }

        binding.btnFinishSetup.setOnClickListener {
            completeSetup()
        }

        // Auto-register and generate code if senior
        registerAndStartPairing()
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

    private fun registerAndStartPairing() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val role = if (binding.rbRoleSenior.isChecked) "senior" else "family"

        Thread {
            try {
                // 1. Register device
                val regBody = mapOf("role" to role, "deviceName" to "Elder Phone")
                val req = Request.Builder()
                    .url("$serverUrl/v1/register")
                    .post(gson.toJson(regBody).toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = httpClient.newCall(req).execute()
                val respStr = resp.body?.string() ?: return@Thread
                val regData = gson.fromJson(respStr, Map::class.java)

                val token = regData["jwt"] as? String ?: return@Thread
                val userId = regData["userId"] as? String ?: return@Thread

                prefs.token = token
                prefs.userId = userId
                prefs.serverUrl = serverUrl

                // 2. Start pairing if senior
                if (role == "senior") {
                    val pairReq = Request.Builder()
                        .url("$serverUrl/v1/pair/start")
                        .header("Authorization", "Bearer $token")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()

                    val pairResp = httpClient.newCall(pairReq).execute()
                    val pairData = gson.fromJson(pairResp.body?.string(), Map::class.java)
                    val code = pairData["code"] as? String ?: "------"
                    val qrPayload = pairData["qrPayload"] as? String ?: code

                    runOnUiThread {
                        binding.tvPairingCode.text = code
                        generateQrCode(qrPayload)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvPairingCode.text = "123456"
                    generateQrCode("123456")
                }
            }
        }.start()
    }

    private fun generateQrCode(content: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 400, 400)
            binding.imgPairingQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun completeSetup() {
        prefs.serverUrl = binding.etServerUrl.text.toString().trim()
        prefs.role = if (binding.rbRoleSenior.isChecked) "senior" else "family"
        prefs.isSetupComplete = true

        (application as App).startCallServiceIfConfigured()

        if (prefs.role == "senior") {
            startActivity(Intent(this, HomeActivity::class.java))
        } else {
            startActivity(Intent(this, FamilyMainActivity::class.java))
        }
        finish()
    }
}
