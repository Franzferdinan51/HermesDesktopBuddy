package com.duckets.hermesbuddy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.duckets.hermesbuddy.databinding.ActivityChatBinding
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Chat: message list, text input, and a hold-to-talk button that streams
 * PCM16 mono audio (audio.start -> binary frames -> audio.end).
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var b: ActivityChatBinding
    private var service: GadgetService? = null
    private val adapter = ChatAdapter()

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Mic permission needed for voice", Toast.LENGTH_SHORT).show()
    }

    // Recording state
    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private var recThread: Thread? = null
    private var uttId: String = ""
    private var uttStream: Int = 0
    private var uttStartMs: Long = 0

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
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Hermes"

        b.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.messages.adapter = adapter

        b.btnSend.setOnClickListener {
            val text = b.input.text.toString()
            if (text.isNotBlank()) {
                service?.sendText(text)
                b.input.text.clear()
            }
        }
        b.btnStop.setOnClickListener { service?.sendCancel() }

        b.btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTalk()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalk()
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, GadgetService::class.java), svcConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (recording) stopTalk()
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
                    svc.chat.collect { msgs ->
                        adapter.submitList(msgs.toList()) {
                            if (msgs.isNotEmpty()) b.messages.scrollToPosition(msgs.size - 1)
                        }
                    }
                }
                launch {
                    svc.pendingPrompt.collect { prompt ->
                        if (prompt != null) showPromptDialog(prompt)
                    }
                }
            }
        }
    }

    private var promptShownFor: String? = null

    private fun showPromptDialog(prompt: PendingPrompt) {
        if (promptShownFor == prompt.id) return
        promptShownFor = prompt.id
        AlertDialog.Builder(this)
            .setTitle(prompt.title.ifEmpty { "Hermes asks" })
            .setMessage(prompt.text)
            .setPositiveButton("Yes") { _, _ -> service?.answerPrompt(prompt.id, true) }
            .setNegativeButton("No") { _, _ -> service?.answerPrompt(prompt.id, false) }
            .setOnDismissListener {
                if (service?.pendingPrompt?.value?.id == prompt.id) {
                    service?.dismissPrompt()
                }
            }
            .show()
    }

    // ---- Hold-to-talk ----

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startTalk() {
        if (recording) return
        if (!hasMic()) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val svc = service ?: return
        val rate = svc.store.micRate.coerceIn(8000, 48000)
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Toast.makeText(this, "Mic not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                recorder = null
                return
            }
            val (id, stream) = svc.beginUtterance()
            uttId = id
            uttStream = stream
            uttStartMs = SystemClock.elapsedRealtime()
            recorder?.startRecording()
            recording = true
            b.btnTalk.text = "Listening… release to send"
            // 20 ms frames (640 bytes @ 16 kHz mono 16-bit)
            val frameBytes = (rate / 50) * 2
            recThread = thread(name = "mic-stream", isDaemon = true) {
                val buf = ByteArray(frameBytes)
                val rec = recorder
                while (recording && rec != null) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) svc.sendAudioChunk(stream, buf.copyOf(n))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
            recording = false
        }
    }

    private fun stopTalk() {
        if (!recording) return
        recording = false
        b.btnTalk.text = "Hold to talk"
        try {
            recThread?.join(1000)
        } catch (_: Exception) {
        }
        recThread = null
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        val durationMs = SystemClock.elapsedRealtime() - uttStartMs
        val svc = service
        if (svc != null) {
            if (durationMs < 250) {
                svc.cancelUtterance(uttId, uttStream, "too short")
            } else {
                svc.endUtterance(uttId, uttStream, durationMs)
            }
        }
    }
}
