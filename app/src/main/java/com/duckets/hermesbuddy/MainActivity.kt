package com.duckets.hermesbuddy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.duckets.hermesbuddy.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Dashboard: connection status dot, last caption, pairing code when unpaired,
 * and buttons for Chat / Pair / Settings / Reconnect.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var service: GadgetService? = null

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort */ }

    private val audioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startGadgetService()
        } else {
            android.widget.Toast.makeText(
                this,
                "Microphone permission is required for Hermes Buddy voice features.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startGadgetService() {
        val svc = Intent(this, GadgetService::class.java)
        ContextCompat.startForegroundService(this, svc)
    }

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as GadgetService.LocalBinder).getService()
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Keep the gadget link alive in a foreground service.
        // The service runs as a microphone-type FGS (hold-to-talk), which on
        // Android 14+ requires RECORD_AUDIO to be granted before startForeground.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startGadgetService()
        } else {
            audioPerm.launch(Manifest.permission.RECORD_AUDIO)
        }

        b.btnChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        b.btnPair.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnReconnect.setOnClickListener { service?.reconnect() }
        b.btnNewSession.setOnClickListener { service?.sendSessionNew() }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, GadgetService::class.java), svcConn, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(svcConn)
        } catch (_: Exception) {
        }
        service = null
    }

    private fun observeService() {
        val svc = service ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.connState.collect { s ->
                        b.statusText.text = when (s) {
                            GadgetConnection.ConnState.DISCONNECTED -> "Disconnected"
                            GadgetConnection.ConnState.CONNECTING -> "Connecting…"
                            GadgetConnection.ConnState.HANDSHAKE -> "Handshaking…"
                            GadgetConnection.ConnState.PAIRING -> "Waiting for pairing"
                            GadgetConnection.ConnState.CONNECTED -> "Connected"
                        }
                        b.statusDot.setBackgroundColor(
                            when (s) {
                                GadgetConnection.ConnState.CONNECTED -> Color.parseColor("#2ecc71")
                                GadgetConnection.ConnState.PAIRING -> Color.parseColor("#f39c12")
                                GadgetConnection.ConnState.CONNECTING,
                                GadgetConnection.ConnState.HANDSHAKE -> Color.parseColor("#3498db")
                                GadgetConnection.ConnState.DISCONNECTED -> Color.parseColor("#e74c3c")
                            }
                        )
                    }
                }
                launch {
                    svc.caption.collect { b.captionText.text = it }
                }
                launch {
                    svc.pairingCode.collect { code ->
                        if (code != null) {
                            b.pairCard.visibility = View.VISIBLE
                            b.pairCode.text = code
                        } else {
                            b.pairCard.visibility = View.GONE
                        }
                    }
                }
                launch {
                    svc.paired.collect { paired ->
                        b.pairedLabel.text = if (paired) "Paired ✓" else "Not paired"
                    }
                }
            }
        }
    }
}
