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
import com.seniorcall.core.LanManager
import com.seniorcall.core.Prefs
import com.seniorcall.databinding.ActivitySetupWizardBinding
import com.seniorcall.signaling.LocalSignalingServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class SetupWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var prefs: Prefs
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

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

        // Initialize Senior LAN setup by default
        refreshSeniorLanSetup()
    }

    private fun setupRoleSelection() {
        binding.rgRole.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbRoleSenior) {
                binding.layoutSeniorSetup.visibility = View.VISIBLE
                binding.layoutFamilySetup.visibility = View.GONE
                refreshSeniorLanSetup()
            } else {
                binding.layoutSeniorSetup.visibility = View.GONE
                binding.layoutFamilySetup.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSeniorMode() {
        binding.rgSeniorMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbSeniorLan) {
                binding.layoutSeniorServerInput.visibility = View.GONE
                refreshSeniorLanSetup()
            } else {
                binding.layoutSeniorServerInput.visibility = View.VISIBLE
                binding.tvSeniorStatus.text = "🟡 Enter Server URL and tap Connect"
                binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))
                binding.tvSeniorStatus.setTextColor(Color.parseColor("#D97706"))
            }
        }

        binding.btnSeniorConnectServer.setOnClickListener {
            connectSeniorToServer()
        }
    }

    private fun refreshSeniorLanSetup() {
        val ip = LanManager.getLocalIpAddress()
        val code = String.format("%06d", Random.nextInt(100000, 999999))
        prefs.pairingCode = code
        prefs.isLanMode = true
        prefs.userId = "usr_elder"
        prefs.serverUrl = "ws://127.0.0.1:8887"

        binding.tvSeniorPairCode.text = code
        binding.tvSeniorIp.text = if (ip != null) "Wi-Fi IP: $ip:8887" else "Wi-Fi: Not connected (connect to Wi-Fi)"

        if (ip != null) {
            binding.tvSeniorStatus.text = "🟢 Local Wi-Fi Active: Ready to Pair"
            binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
            binding.tvSeniorStatus.setTextColor(Color.parseColor("#16A34A"))

            // Start LocalSignalingServer and broadcast NSD
            val server = LocalSignalingServer.startInstance(8887, code)
            LanManager.registerNsdService(this, 8887)

            server.onPairListener = { clientIp ->
                runOnUiThread {
                    binding.tvSeniorStatus.text = "🟢 Family Phone Paired ($clientIp)!"
                    Toast.makeText(this, "Family paired successfully!", Toast.LENGTH_SHORT).show()
                }
            }

            // QR Code payload
            val qrData = mapOf(
                "mode" to "lan",
                "ip" to ip,
                "port" to 8887,
                "code" to code,
                "peerId" to "usr_elder",
                "server" to "ws://$ip:8887"
            )
            generateQrCode(gson.toJson(qrData))
        } else {
            binding.tvSeniorStatus.text = "⚠️ Connect this phone to home Wi-Fi"
            binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
            binding.tvSeniorStatus.setTextColor(Color.parseColor("#DC2626"))
        }
    }

    private fun connectSeniorToServer() {
        val url = binding.etSeniorServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a valid server URL", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvSeniorStatus.text = "🟡 Registering with server..."
        binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))

        Thread {
            try {
                val regBody = mapOf("role" to "senior", "deviceName" to "Elder Phone")
                val regReq = Request.Builder()
                    .url("$url/v1/register")
                    .post(gson.toJson(regBody).toRequestBody("application/json".toMediaType()))
                    .build()

                val regResp = httpClient.newCall(regReq).execute()
                val regStr = regResp.body?.string() ?: throw IOException("Empty response")
                val regData = gson.fromJson(regStr, Map::class.java)

                val token = regData["jwt"] as? String ?: throw IOException("No JWT")
                val userId = regData["userId"] as? String ?: throw IOException("No userId")

                // Start pairing
                val pairReq = Request.Builder()
                    .url("$url/v1/pair/start")
                    .header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()

                val pairResp = httpClient.newCall(pairReq).execute()
                val pairStr = pairResp.body?.string() ?: throw IOException("Empty pair response")
                val pairData = gson.fromJson(pairStr, Map::class.java)

                val code = pairData["code"] as? String ?: "------"

                prefs.serverUrl = url
                prefs.isLanMode = false
                prefs.token = token
                prefs.userId = userId
                prefs.pairingCode = code

                val qrPayload = mapOf(
                    "mode" to "server",
                    "server" to url,
                    "code" to code,
                    "peerId" to userId
                )

                runOnUiThread {
                    binding.tvSeniorStatus.text = "🟢 Connected to Server: Ready to Pair"
                    binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    binding.tvSeniorStatus.setTextColor(Color.parseColor("#16A34A"))
                    binding.tvSeniorPairCode.text = code
                    generateQrCode(gson.toJson(qrPayload))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvSeniorStatus.text = "🔴 Cannot reach server: ${e.message}"
                    binding.tvSeniorStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                    binding.tvSeniorStatus.setTextColor(Color.parseColor("#DC2626"))
                }
            }
        }.start()
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

        binding.btnFamilyAutoDiscover.setOnClickListener {
            binding.tvFamilyPairStatus.text = "🔍 Searching for Elder phone on Wi-Fi..."
            binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))

            LanManager.discoverElder(
                this,
                onFound = { host, port ->
                    binding.etFamilyServerOrIp.setText("$host:$port")
                    binding.tvFamilyPairStatus.text = "✅ Found Elder at $host:$port! Enter PIN or Scan QR."
                    binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                },
                onTimeout = {
                    binding.tvFamilyPairStatus.text = "⚠️ Auto-discovery timed out. Enter IP manually or Scan QR."
                    binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                }
            )
        }

        binding.btnFamilyPairManual.setOnClickListener {
            val hostOrUrl = binding.etFamilyServerOrIp.text.toString().trim()
            val code = binding.etFamilyPairCode.text.toString().trim()

            if (hostOrUrl.isEmpty() || code.isEmpty()) {
                Toast.makeText(this, "Enter both Elder IP/Server and 6-digit PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairFamily(hostOrUrl, code, "usr_elder")
        }
    }

    private fun handleScannedQr(contents: String) {
        try {
            val map = gson.fromJson(contents, Map::class.java)
            val code = map["code"]?.toString() ?: ""
            val peerId = map["peerId"]?.toString() ?: "usr_elder"
            val server = map["server"]?.toString() ?: map["ip"]?.toString() ?: ""

            binding.etFamilyServerOrIp.setText(server)
            binding.etFamilyPairCode.setText(code)

            pairFamily(server, code, peerId)
        } catch (e: Exception) {
            // Raw text fallback
            binding.etFamilyPairCode.setText(contents)
            Toast.makeText(this, "Scanned code: $contents", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pairFamily(hostOrUrl: String, code: String, targetPeerId: String) {
        binding.tvFamilyPairStatus.text = "🟡 Pairing with Elder..."
        binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))

        val isLan = !hostOrUrl.startsWith("http://") && !hostOrUrl.startsWith("https://")

        if (isLan) {
            // Direct Local Wi-Fi Pairing
            var wsTarget = hostOrUrl
            if (!wsTarget.startsWith("ws://") && !wsTarget.startsWith("wss://")) {
                wsTarget = "ws://$wsTarget"
            }
            if (!wsTarget.contains(":")) {
                wsTarget = "$wsTarget:8887"
            }

            val request = Request.Builder().url(wsTarget).build()
            val tempClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()

            tempClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val pairMsg = mapOf("t" to "pair", "code" to code, "role" to "family")
                    webSocket.send(gson.toJson(pairMsg))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val reply = gson.fromJson(text, Map::class.java)
                        if (reply["t"] == "pair_success" || reply["t"] == "hello") {
                            prefs.isLanMode = true
                            prefs.serverUrl = wsTarget
                            prefs.userId = "usr_family"
                            prefs.pairedPeerId = targetPeerId
                            prefs.pairingCode = code
                            prefs.token = "family"

                            runOnUiThread {
                                binding.tvFamilyPairStatus.text = "✅ Paired with Elder Phone ($wsTarget)"
                                binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                                binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#16A34A"))
                                Toast.makeText(this@SetupWizardActivity, "Successfully paired with Elder!", Toast.LENGTH_SHORT).show()
                            }
                            webSocket.close(1000, "Paired")
                        } else if (reply["t"] == "error") {
                            runOnUiThread {
                                val msg = reply["message"]?.toString() ?: "Invalid PIN"
                                binding.tvFamilyPairStatus.text = "🔴 Pairing Failed: $msg"
                                binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                                binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#DC2626"))
                            }
                            webSocket.close(1000, "Error")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread {
                        binding.tvFamilyPairStatus.text = "🔴 Connection Failed: ${t.message}"
                        binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                        binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#DC2626"))
                    }
                }
            })
        } else {
            // Central HTTP Server Pairing
            Thread {
                try {
                    val regBody = mapOf("role" to "family", "deviceName" to "Family Phone")
                    val regReq = Request.Builder()
                        .url("$hostOrUrl/v1/register")
                        .post(gson.toJson(regBody).toRequestBody("application/json".toMediaType()))
                        .build()

                    val regResp = httpClient.newCall(regReq).execute()
                    val regData = gson.fromJson(regResp.body?.string(), Map::class.java)
                    val token = regData["jwt"] as? String ?: throw IOException("Registration failed")
                    val userId = regData["userId"] as? String ?: throw IOException("No userId")

                    // Confirm pairing
                    val confBody = mapOf("code" to code)
                    val confReq = Request.Builder()
                        .url("$hostOrUrl/v1/pair/confirm")
                        .header("Authorization", "Bearer $token")
                        .post(gson.toJson(confBody).toRequestBody("application/json".toMediaType()))
                        .build()

                    val confResp = httpClient.newCall(confReq).execute()
                    val confStr = confResp.body?.string() ?: ""
                    val confData = gson.fromJson(confStr, Map::class.java)

                    if (confData["success"] == true) {
                        val elderId = confData["elderId"] as? String ?: targetPeerId
                        prefs.isLanMode = false
                        prefs.serverUrl = hostOrUrl
                        prefs.token = token
                        prefs.userId = userId
                        prefs.pairedPeerId = elderId
                        prefs.pairingCode = code

                        runOnUiThread {
                            binding.tvFamilyPairStatus.text = "✅ Paired with Elder (Server: $hostOrUrl)"
                            binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                            binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#16A34A"))
                            Toast.makeText(this@SetupWizardActivity, "Successfully paired with Elder!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val err = confData["error"]?.toString() ?: "Invalid code"
                        runOnUiThread {
                            binding.tvFamilyPairStatus.text = "🔴 Pairing Failed: $err"
                            binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                            binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#DC2626"))
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.tvFamilyPairStatus.text = "🔴 Server Error: ${e.message}"
                        binding.tvFamilyPairStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                        binding.tvFamilyPairStatus.setTextColor(Color.parseColor("#DC2626"))
                    }
                }
            }.start()
        }
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

        if (!isSenior && prefs.pairedPeerId.isNullOrEmpty()) {
            val code = binding.etFamilyPairCode.text.toString().trim()
            if (code.isNotEmpty()) {
                prefs.pairedPeerId = "usr_elder"
                prefs.pairingCode = code
            } else {
                Toast.makeText(this, "Please pair with Elder phone before completing setup", Toast.LENGTH_LONG).show()
                return
            }
        }

        prefs.isSetupComplete = true
        (application as App).startCallServiceIfConfigured()

        if (isSenior) {
            startActivity(Intent(this, HomeActivity::class.java))
        } else {
            startActivity(Intent(this, FamilyMainActivity::class.java))
        }
        finish()
    }
}
