package com.duckets.hermesbuddy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.duckets.hermesbuddy.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/** Host URL, device name, TTS toggle, mic rate. Saved to DeviceStore. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var service: GadgetService? = null

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as GadgetService.LocalBinder).getService()
            loadValues()
            observeDiag()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"

        b.btnSave.setOnClickListener { save() }
        b.btnReconnect.setOnClickListener {
            if (save()) {
                service?.reconnect()
                Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
            }
        }

        b.deviceIdRow.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Device ID", svc.store.deviceId()))
                Toast.makeText(this, "Device ID copied", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }

        b.btnResetKey.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Reset device key?")
                .setMessage(
                    "This forgets the pairing and re-enrolls as a brand-new " +
                        "device. You will need to approve a new pairing code."
                )
                .setPositiveButton("Reset") { _, _ ->
                    service?.resetDeviceKey()
                    loadValues()
                    Toast.makeText(this, "Key reset — re-pair this device", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.entered()
        bindService(Intent(this, GadgetService::class.java), svcConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.exited()
        try {
            unbindService(svcConn)
        } catch (_: Exception) {
        }
        service = null
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Validates and saves every field. Returns false when a field is invalid
     * (nothing is saved). Reconnects automatically when the host URL changed.
     */
    private fun save(): Boolean {
        val svc = service ?: return false
        val host = b.hostUrl.text.toString().trim()
        if (host.isNotEmpty() && !isValidWsUrl(host)) {
            b.hostUrl.error = "Use ws://host:port/path or wss://…"
            Toast.makeText(this, "Invalid host URL", Toast.LENGTH_SHORT).show()
            return false
        }
        val rate = b.micRate.text.toString().trim().toIntOrNull()
        if (b.micRate.text.toString().trim().isNotEmpty() && (rate == null || rate !in 8000..48000)) {
            b.micRate.error = "8000 – 48000"
            Toast.makeText(this, "Invalid mic rate (8000 – 48000 Hz)", Toast.LENGTH_SHORT).show()
            return false
        }
        val hostChanged = host.isNotEmpty() && host != svc.store.hostUrl
        if (host.isNotEmpty()) svc.store.hostUrl = host
        svc.store.deviceName = b.deviceName.text.toString().trim().ifEmpty {
            DeviceStore.DEFAULT_DEVICE_NAME
        }
        svc.store.ttsEnabled = b.ttsSwitch.isChecked
        if (rate != null) svc.store.micRate = rate
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        if (hostChanged) {
            svc.reconnect()
            Toast.makeText(this, "Host changed — reconnecting…", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun isValidWsUrl(url: String): Boolean {
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) return false
        return try {
            val uri = android.net.Uri.parse(url)
            !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun loadValues() {
        val svc = service ?: return
        b.hostUrl.setText(svc.store.hostUrl)
        b.deviceName.setText(svc.store.deviceName)
        b.ttsSwitch.isChecked = svc.store.ttsEnabled
        b.micRate.setText(svc.store.micRate.toString())
        b.deviceIdValue.text = svc.store.deviceId()
        refreshDiag()
    }

    private fun observeDiag() {
        val svc = service ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { svc.connState.collect { refreshDiag() } }
                launch { svc.lastError.collect { refreshDiag() } }
                launch { svc.sessionId.collect { refreshDiag() } }
            }
        }
    }

    private fun refreshDiag() {
        val svc = service ?: return
        b.diagText.text = buildString {
            appendLine("state: ${svc.connState.value.name.lowercase()}")
            appendLine("paired: ${svc.paired.value}")
            appendLine("proto: ${Proto.SUBPROTOCOL} (v${Proto.VERSION})")
            appendLine("server: ${svc.serverInfo.value ?: "—"}")
            appendLine("session: ${svc.sessionId.value ?: "—"}")
            appendLine("battery: ${svc.batteryPct()}%")
            append("last error: ${svc.lastError.value ?: "none"}")
        }
    }
}
