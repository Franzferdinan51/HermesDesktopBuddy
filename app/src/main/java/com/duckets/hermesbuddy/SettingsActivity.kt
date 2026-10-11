package com.duckets.hermesbuddy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.duckets.hermesbuddy.databinding.ActivitySettingsBinding

/** Host URL, device name, TTS toggle, mic rate. Saved to DeviceStore. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var service: GadgetService? = null

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as GadgetService.LocalBinder).getService()
            loadValues()
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

        b.btnSave.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            svc.store.hostUrl = b.hostUrl.text.toString().trim().ifEmpty {
                DeviceStore.DEFAULT_HOST_URL
            }
            svc.store.deviceName = b.deviceName.text.toString().trim().ifEmpty {
                DeviceStore.DEFAULT_DEVICE_NAME
            }
            svc.store.ttsEnabled = b.ttsSwitch.isChecked
            val rate = b.micRate.text.toString().toIntOrNull()
            if (rate != null && rate in 8000..48000) {
                svc.store.micRate = rate
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        b.btnReconnect.setOnClickListener {
            service?.reconnect()
            Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
        }

        b.deviceIdRow.setOnClickListener {
            // Show the derived device id for `hermes gadget forget` etc.
            val svc = service ?: return@setOnClickListener
            android.app.AlertDialog.Builder(this)
                .setTitle("Device ID")
                .setMessage(svc.store.deviceId())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, GadgetService::class.java), svcConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
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

    private fun loadValues() {
        val svc = service ?: return
        b.hostUrl.setText(svc.store.hostUrl)
        b.deviceName.setText(svc.store.deviceName)
        b.ttsSwitch.isChecked = svc.store.ttsEnabled
        b.micRate.setText(svc.store.micRate.toString())
        b.deviceIdValue.text = svc.store.deviceId()
    }
}
