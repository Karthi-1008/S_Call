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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import com.seniorcall.data.Contact
import com.seniorcall.databinding.ActivitySetupWizardBinding
import com.seniorcall.signaling.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class SetupWizardActivity : AppCompatActivity(), SignalingClient.Listener {
    private val tag = "SetupWizard"
    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var prefs: Prefs
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var signalingClient: SignalingClient? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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

    private fun refreshSeniorQr() {
        prefs.role = "senior"
        binding.tvSeniorStatus.text = "⏳ Generating Secure Pairing Code..."
        binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))
        binding.tvSeniorStatus.setTextColor(Color.parseColor("#D97706"))

        // Register elder if needed, then request pair code
        Thread {
            try {
                ensureRegistered("senior", "Elder Phone") { token, userId ->
                    requestPairCode(token) { code ->
                        mainHandler.post {
                            binding.tvSeniorPairCode.text = code
                            binding.tvSeniorStatus.text = "🟢 Ready to Pair. Point Family camera at QR code."
                            binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                            binding.tvSeniorStatus.setTextColor(Color.parseColor("#16A34A"))

                            // Generate QR Payload
                            val qrMap = mapOf(
                                "serverUrl" to prefs.serverUrl,
                                "code" to code,
                                "elderId" to userId
                            )
                            val qrPayload = gson.toJson(qrMap)
                            generateQrCode(qrPayload)

                            // Connect signaling to detect live pairing event
                            listenForPairingSuccess(token)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to prepare senior QR", e)
                mainHandler.post {
                    binding.tvSeniorStatus.text = "⚠️ Network error. Check Wi-Fi connection."
                    binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                    binding.tvSeniorStatus.setTextColor(Color.parseColor("#DC2626"))
                }
            }
        }.start()
    }

    private fun listenForPairingSuccess(token: String) {
        signalingClient?.disconnect()
        signalingClient = SignalingClient(prefs.wsUrl, token, prefs.deviceId, this)
        signalingClient?.connect()
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

        if (prefs.pairedPeerId != null && prefs.role == "family") {
            binding.tvFamilyPairStatus.text = "✅ Already Paired with Elder (${prefs.pairedPeerId})"
            binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
            binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#16A34A"))
        }
    }

    private fun handleScannedQr(contents: String) {
        try {
            val map = gson.fromJson(contents, Map::class.java)
            val serverUrl = map["serverUrl"]?.toString() ?: prefs.serverUrl
            val code = map["code"]?.toString() ?: ""
            val elderId = map["elderId"]?.toString() ?: ""

            if (code.isEmpty()) {
                Toast.makeText(this, "Invalid SeniorCall QR Code", Toast.LENGTH_LONG).show()
                return
            }

            if (serverUrl.isNotEmpty()) {
                prefs.serverUrl = serverUrl
            }

            binding.tvFamilyPairStatus.text = "⏳ Confirming Pairing with Elder..."
            binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))
            binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#D97706"))

            // Perform registration & pair confirmation
            Thread {
                try {
                    ensureRegistered("family", "Family Phone") { token, userId ->
                        confirmPairingWithServer(token, code) { confirmedElderId ->
                            mainHandler.post {
                                prefs.role = "family"
                                prefs.pairedPeerId = confirmedElderId.ifEmpty { elderId }
                                binding.tvFamilyPairStatus.text = "✅ Successfully Paired with Elder Phone!"
                                binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                                binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#16A34A"))
                                Toast.makeText(this@SetupWizardActivity, "Paired! Tap Complete Setup.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Pair confirmation failed", e)
                    mainHandler.post {
                        binding.tvFamilyPairStatus.text = "❌ Pairing Failed: ${e.message}"
                        binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                        binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#DC2626"))
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse QR code", e)
            Toast.makeText(this, "Invalid QR Code format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureRegistered(role: String, deviceName: String, onRegistered: (token: String, userId: String) -> Unit) {
        val existingToken = prefs.token
        val existingUserId = prefs.userId

        if (!existingToken.isNullOrEmpty() && !existingUserId.isNullOrEmpty()) {
            onRegistered(existingToken, existingUserId)
            return
        }

        val url = "${prefs.serverUrl}/v1/register"
        val json = gson.toJson(mapOf("role" to role, "deviceName" to deviceName))
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IOException("Registration failed with HTTP ${resp.code}")
        }
        val resBody = resp.body?.string() ?: ""
        val data = gson.fromJson(resBody, Map::class.java)
        val token = data["jwt"]?.toString() ?: throw IOException("Missing jwt in register response")
        val userId = data["userId"]?.toString() ?: throw IOException("Missing userId in register response")

        prefs.token = token
        prefs.userId = userId
        prefs.role = role
        onRegistered(token, userId)
    }

    private fun requestPairCode(token: String, onCodeReady: (code: String) -> Unit) {
        val url = "${prefs.serverUrl}/v1/pair/start"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IOException("Pair start failed with HTTP ${resp.code}")
        }
        val resBody = resp.body?.string() ?: ""
        val data = gson.fromJson(resBody, Map::class.java)
        val code = data["code"]?.toString() ?: throw IOException("Missing code in pair/start response")
        prefs.pairingCode = code
        onCodeReady(code)
    }

    private fun confirmPairingWithServer(token: String, code: String, onSuccess: (elderId: String) -> Unit) {
        val url = "${prefs.serverUrl}/v1/pair/confirm"
        val json = gson.toJson(mapOf("code" to code))
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw IOException("Pair confirm failed with HTTP ${resp.code}")
        }
        val resBody = resp.body?.string() ?: ""
        val data = gson.fromJson(resBody, Map::class.java)
        val elderId = data["elderId"]?.toString() ?: ""
        onSuccess(elderId)
    }

    private fun generateQrCode(content: String) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, 450, 450)
            binding.imgSeniorQr.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(tag, "Failed to render QR bitmap", e)
        }
    }

    // Signaling Listener Callbacks
    override fun onContactsUpdate(contacts: List<Contact>) {
        if (contacts.isNotEmpty()) {
            runOnUiThread {
                binding.tvSeniorStatus.text = "🟢 Family Phone Connected & Paired!"
                binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                binding.tvSeniorStatus.setTextColor(Color.parseColor("#16A34A"))
            }
        }
    }

    override fun onPresence(presence: PresenceMessage) {
        if (presence.online) {
            runOnUiThread {
                binding.tvSeniorStatus.text = "🟢 Family Phone Connected & Paired!"
                binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                binding.tvSeniorStatus.setTextColor(Color.parseColor("#16A34A"))
            }
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

        if (!isSenior && prefs.pairedPeerId.isNullOrEmpty()) {
            Toast.makeText(this, "Please scan Elder's QR code before completing setup", Toast.LENGTH_LONG).show()
            return
        }

        signalingClient?.disconnect()
        signalingClient = null

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
        signalingClient?.disconnect()
        super.onDestroy()
    }
}
