package com.duckets.hermesbuddy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
        // The service runs with or without the mic (it picks the matching
        // foreground-service type); voice input just needs the permission.
        startGadgetService()
        if (!granted) {
            android.widget.Toast.makeText(
                this,
                "Microphone denied: chat still works, hold-to-talk is disabled.",
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

        // Keep the gadget link alive in a foreground service. The service
        // itself picks a microphone- or specialUse-type foreground entry
        // depending on the mic permission, so it is always safe to start.
        startGadgetService()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
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
        b.pairCode.setOnClickListener {
            val code = b.pairCode.text.toString()
            if (code.isNotBlank() && code != "—") copyToClipboard("Pairing code", code)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private var pulse: android.animation.ValueAnimator? = null

    /** Gentle alpha pulse on the status dots while a connection is in flight. */
    private fun pulseWhile(active: Boolean) {
        pulse?.cancel()
        pulse = null
        b.statusDot.alpha = 1f
        b.statusDotCard.alpha = 1f
        if (!active) return
        pulse = android.animation.ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                val v = it.animatedValue as Float
                b.statusDot.alpha = v
                b.statusDotCard.alpha = v
            }
            start()
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.entered()
        bindService(
            Intent(this, GadgetService::class.java), svcConn, Context.BIND_AUTO_CREATE
        )
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

    private fun observeService() {
        val svc = service ?: return
        svc.refreshBattery()
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
                        val dot = when (s) {
                            GadgetConnection.ConnState.CONNECTED -> R.drawable.status_dot_online
                            GadgetConnection.ConnState.PAIRING -> R.drawable.status_dot_pairing
                            GadgetConnection.ConnState.CONNECTING,
                            GadgetConnection.ConnState.HANDSHAKE -> R.drawable.status_dot_pairing
                            GadgetConnection.ConnState.DISCONNECTED -> R.drawable.status_dot_offline
                        }
                        b.statusDot.setBackgroundResource(dot)
                        b.statusDotCard.setBackgroundResource(dot)
                        pulseWhile(
                            s == GadgetConnection.ConnState.CONNECTING ||
                                s == GadgetConnection.ConnState.HANDSHAKE
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
                launch {
                    svc.lastError.collect { err ->
                        if (err != null) {
                            b.errorBanner.text = err
                            b.errorBanner.visibility = View.VISIBLE
                        } else {
                            b.errorBanner.visibility = View.GONE
                        }
                    }
                }
                launch {
                    svc.batteryPctFlow.collect { pct ->
                        b.batteryText.text = if (pct in 0..100) "$pct%" else "—"
                    }
                }
            }
        }
    }
}
