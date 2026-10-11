package com.duckets.hermesbuddy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.duckets.hermesbuddy.databinding.ActivityPairingBinding
import kotlinx.coroutines.launch

/** Shows the server-issued pairing code and the approve command. */
class PairingActivity : AppCompatActivity() {

    private lateinit var b: ActivityPairingBinding
    private var service: GadgetService? = null

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
        b = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Pair with Hermes"

        b.btnRefresh.setOnClickListener { service?.reconnect() }
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

    private fun observeService() {
        val svc = service ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.paired.collect { paired ->
                        b.statusText.text = if (paired) "Paired ✓" else "Not paired"
                    }
                }
                launch {
                    svc.pairingCode.collect { code ->
                        if (code != null) {
                            b.codeText.text = code
                            b.codeText.visibility = View.VISIBLE
                        } else {
                            b.codeText.text = "—"
                        }
                    }
                }
                launch {
                    svc.pairingCommand.collect { cmd ->
                        b.commandText.text = cmd
                            ?: "Connect to the Hermes host to receive a pairing code."
                    }
                }
            }
        }
    }
}
