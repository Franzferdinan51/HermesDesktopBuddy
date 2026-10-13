package com.duckets.hermesbuddy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
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
        b.btnOpenChat.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
        }
        b.btnCopyCode.setOnClickListener {
            val code = b.codeText.text.toString()
            if (code.isNotBlank() && code != "—") copyToClipboard("Pairing code", code)
        }
        b.btnCopyCommand.setOnClickListener {
            val cmd = b.commandText.text.toString()
            if (cmd.isNotBlank()) copyToClipboard("Approve command", cmd)
        }
        b.codeText.setOnClickListener { b.btnCopyCode.performClick() }
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

    private fun copyToClipboard(label: String, text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun observeService() {
        val svc = service ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.paired.collect { paired ->
                        b.statusText.text = if (paired) "Paired ✓" else "Not paired"
                        b.pairStatusDot.setBackgroundResource(
                            if (paired) R.drawable.status_dot_online
                            else R.drawable.status_dot_offline
                        )
                        b.doneCard.visibility = if (paired) View.VISIBLE else View.GONE
                        b.btnRefresh.visibility = if (paired) View.GONE else View.VISIBLE
                        updateCards()
                    }
                }
                launch {
                    svc.pairingCode.collect { code ->
                        if (code != null) {
                            b.codeText.text = code
                        } else {
                            b.codeText.text = "—"
                        }
                        updateCards()
                    }
                }
                launch {
                    svc.pairingCommand.collect { cmd ->
                        b.commandText.text = cmd
                            ?: "Connect to the Hermes host to receive a pairing code."
                    }
                }
                launch {
                    svc.connState.collect { s ->
                        if (svc.paired.value) return@collect
                        b.pairStatusDot.setBackgroundResource(
                            when (s) {
                                GadgetConnection.ConnState.CONNECTED,
                                GadgetConnection.ConnState.PAIRING -> R.drawable.status_dot_pairing
                                GadgetConnection.ConnState.CONNECTING,
                                GadgetConnection.ConnState.HANDSHAKE -> R.drawable.status_dot_pairing
                                GadgetConnection.ConnState.DISCONNECTED -> R.drawable.status_dot_offline
                            }
                        )
                    }
                }
            }
        }
    }

    /** Show how-it-works until a code arrives; hide work cards once paired. */
    private fun updateCards() {
        val paired = service?.paired?.value == true
        val hasCode = b.codeText.text.toString() != "—"
        b.howCard.visibility =
            if (!paired && !hasCode) View.VISIBLE else View.GONE
        b.codeCard.visibility =
            if (!paired && hasCode) View.VISIBLE else View.GONE
        b.commandCard.visibility =
            if (!paired && hasCode) View.VISIBLE else View.GONE
    }
}
