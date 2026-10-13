package com.seniorcall.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seniorcall.R
import com.seniorcall.core.Haptics
import com.seniorcall.core.Net
import com.seniorcall.core.Prefs
import com.seniorcall.data.Contact
import com.seniorcall.data.ContactStore
import com.seniorcall.databinding.ActivityHomeBinding
import com.seniorcall.databinding.ItemContactTileBinding
import com.seniorcall.service.CallService
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private lateinit var contactStore: ContactStore
    private lateinit var haptics: Haptics
    private lateinit var net: Net

    private val handler = Handler(Looper.getMainLooper())
    private var logoTapCount = 0
    private var lastLogoTapTime = 0L

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (!prefs.isSetupComplete) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            finish()
            return
        }

        if (prefs.role == "family") {
            startActivity(Intent(this, FamilyMainActivity::class.java))
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactStore = ContactStore(this)
        haptics = Haptics(this)
        net = Net(this)

        setupUI()
        setupLockTaskIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        updateStatusBanner()
        loadContacts()
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
    }

    private fun setupUI() {
        // Logo 5-tap listener for hidden family settings (§12)
        binding.tvHeaderLogo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastLogoTapTime < 800) {
                logoTapCount++
            } else {
                logoTapCount = 1
            }
            lastLogoTapTime = now

            if (logoTapCount >= 5) {
                logoTapCount = 0
                showHiddenSettingsPinDialog()
            }
        }

        // SOS Button
        binding.btnSos.setOnClickListener {
            haptics.tick()
            val contacts = contactStore.getContacts()
            val priorityContact = contacts.minByOrNull { it.priority }
            if (priorityContact != null) {
                placeCall(priorityContact.id, priorityContact.name, "video")
            } else {
                Toast.makeText(this, "No family contacts set", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rvContactsGrid.layoutManager = GridLayoutManager(this, 2)
    }

    private fun loadContacts() {
        val contacts = contactStore.getContacts()
        binding.rvContactsGrid.adapter = ContactsAdapter(contacts) { contact, mediaType ->
            haptics.tick()
            placeCall(contact.id, contact.name, mediaType)
        }
    }

    private fun placeCall(targetId: String, name: String, media: String) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("isIncoming", false)
            putExtra("targetUserId", targetId)
            putExtra("targetName", name)
            putExtra("media", media)
        }
        startActivity(intent)
    }

    private fun updateClock() {
        val sdf = SimpleDateFormat("EEE d MMM   h:mm a", Locale.getDefault())
        binding.tvClock.text = sdf.format(Date())
    }

    private fun updateStatusBanner() {
        if (net.isOnline()) {
            binding.tvStatusBanner.text = getString(R.string.status_ready)
            binding.tvStatusBanner.setTextColor(resources.getColor(R.color.status_ready))
            binding.tvStatusBanner.setBackgroundColor(0xFFDCFCE7.toInt())
        } else {
            binding.tvStatusBanner.text = getString(R.string.status_offline)
            binding.tvStatusBanner.setTextColor(resources.getColor(R.color.status_offline))
            binding.tvStatusBanner.setBackgroundColor(0xFFFEE2E2.toInt())
        }
    }

    private fun showHiddenSettingsPinDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_hidden_settings, null)
        val etPin = view.findViewById<EditText>(R.id.etPin)

        AlertDialog.Builder(this)
            .setTitle("Family Settings Access")
            .setView(view)
            .setPositiveButton("Enter") { _, _ ->
                val entered = etPin.text.toString().trim()
                if (entered == prefs.kioskPin) {
                    // Open setup / settings
                    startActivity(Intent(this, SetupWizardActivity::class.java))
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupLockTaskIfNeeded() {
        try {
            // Screen pinning or device owner lock task mode (§12)
            startLockTask()
        } catch (e: Exception) {
            // Handled safely on non-whitelisted/non-owner devices
        }
    }

    inner class ContactsAdapter(
        private val list: List<Contact>,
        private val onAction: (Contact, String) -> Unit
    ) : RecyclerView.Adapter<ContactsAdapter.Holder>() {

        inner class Holder(val binding: ItemContactTileBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemContactTileBinding.inflate(layoutInflater, parent, false)
            return Holder(b)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val contact = list[position]
            holder.binding.tvContactName.text = contact.name.uppercase()

            holder.binding.btnTileVideo.setOnClickListener {
                onAction(contact, "video")
            }

            holder.binding.btnTileVoice.setOnClickListener {
                onAction(contact, "audio")
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
