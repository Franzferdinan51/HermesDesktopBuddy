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

        b.btnSend.setOnClickListener { sendInput() }
        b.btnStop.setOnClickListener { service?.sendCancel() }
        b.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else {
                false
            }
        }
        b.input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                b.btnSend.alpha = if (s.isNullOrBlank()) 0.45f else 1f
            }
        })
        b.btnSend.alpha = 0.45f

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

    private fun sendInput() {
        val text = b.input.text.toString()
        if (text.isBlank()) return
        b.btnTalk.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        service?.sendText(text)
        b.input.text.clear()
    }

    private fun observeService() {
        val svc = service ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.chat.collect { msgs ->
                        b.emptyView.visibility =
                            if (msgs.isEmpty()) android.view.View.VISIBLE
                            else android.view.View.GONE
                        // Only auto-scroll when the user is already at the
                        // bottom, so reading history isn't yanked by deltas.
                        val lm = b.messages.layoutManager as LinearLayoutManager
                        val atBottom = adapter.itemCount == 0 ||
                            lm.findLastVisibleItemPosition() >= adapter.itemCount - 2
                        adapter.submitList(msgs.toList()) {
                            if (msgs.isNotEmpty() && atBottom) {
                                b.messages.scrollToPosition(msgs.size - 1)
                            }
                        }
                    }
                }
                launch {
                    svc.pendingPrompt.collect { prompt ->
                        if (prompt != null) showPromptDialog(prompt)
                    }
                }
                launch {
                    svc.connState.collect { s ->
                        b.chatStatusDot.setBackgroundResource(
                            when (s) {
                                GadgetConnection.ConnState.CONNECTED -> R.drawable.status_dot_online
                                GadgetConnection.ConnState.DISCONNECTED -> R.drawable.status_dot_offline
                                else -> R.drawable.status_dot_pairing
                            }
                        )
                    }
                }
                launch {
                    svc.caption.collect { caption ->
                        // Surface "thinking / replying / speaking" as a banner;
                        // idle captions stay out of the way.
                        val busy = caption.contains("…") ||
                            caption.startsWith("Speaking") ||
                            caption.startsWith("Hermes is")
                        if (busy && !recording) {
                            b.activityBanner.text = caption
                            b.activityBanner.visibility = android.view.View.VISIBLE
                        } else if (!recording) {
                            b.activityBanner.visibility = android.view.View.GONE
                        }
                    }
                }
            }
        }
    }

    private var promptShownFor: String? = null
    private var promptDialog: AlertDialog? = null

    private fun showPromptDialog(prompt: PendingPrompt) {
        if (promptShownFor == prompt.id) return
        promptShownFor = prompt.id
        promptDialog?.dismiss()
        promptDialog = AlertDialog.Builder(this)
            .setTitle(prompt.title.ifEmpty { "Hermes asks" })
            .setMessage(prompt.text)
            .setPositiveButton("Yes") { _, _ -> service?.answerPrompt(prompt.id, true) }
            .setNegativeButton("No") { _, _ -> service?.answerPrompt(prompt.id, false) }
            .setOnDismissListener {
                promptDialog = null
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
            b.btnTalk.background =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.btn_record)
            b.btnTalk.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            b.activityBanner.text = "Listening…"
            b.activityBanner.visibility = android.view.View.VISIBLE
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
        b.btnTalk.background =
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.btn_primary)
        b.btnTalk.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        b.activityBanner.visibility = android.view.View.GONE
        // Tear down off the UI thread: AudioRecord.read() blocks, so joining
        // the mic thread here would freeze the UI for up to a second.
        val rec = recorder
        val t = recThread
        val id = uttId
        val stream = uttStream
        val durationMs = SystemClock.elapsedRealtime() - uttStartMs
        recorder = null
        recThread = null
        thread(name = "mic-stop", isDaemon = true) {
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            try {
                t?.join(2000)
            } catch (_: Exception) {
            }
            try {
                rec?.release()
            } catch (_: Exception) {
            }
            val svc = service
            if (svc != null) {
                if (durationMs < 250) {
                    svc.cancelUtterance(id, stream, "too short")
                } else {
                    svc.endUtterance(id, stream, durationMs)
                }
            }
        }
    }
}
