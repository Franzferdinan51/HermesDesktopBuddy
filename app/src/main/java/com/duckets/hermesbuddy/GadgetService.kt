package com.duckets.hermesbuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One chat row. */
data class ChatMessage(val role: Role, val text: String, val ts: Long = System.currentTimeMillis()) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

data class PendingPrompt(val id: String, val title: String, val text: String)

/**
 * Foreground service that owns the GadgetConnection for the whole app.
 * Activities bind to it for state, chat history, and sending messages.
 *
 * Implements the three registered device actions:
 *  - buddy.vibrate : vibrate the phone briefly
 *  - buddy.notify  : post a notification with title/body
 *  - buddy.battery : return the battery percentage
 */
class GadgetService : Service() {

    companion object {
        private const val TAG = "GadgetService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "hermes_buddy"
        private const val ACTION_CHANNEL_ID = "hermes_buddy_actions"
    }

    inner class LocalBinder : Binder() {
        fun getService(): GadgetService = this@GadgetService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var store: DeviceStore
        private set

    private lateinit var conn: GadgetConnection
    private lateinit var player: AudioPlayer
    private lateinit var tts: TtsSpeaker

    // ---- Observable state ----
    private val _connState = MutableStateFlow(GadgetConnection.ConnState.DISCONNECTED)
    val connState: StateFlow<GadgetConnection.ConnState> = _connState.asStateFlow()

    private val _caption = MutableStateFlow("Not connected")
    val caption: StateFlow<String> = _caption.asStateFlow()

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    private val _pairingCommand = MutableStateFlow<String?>(null)
    val pairingCommand: StateFlow<String?> = _pairingCommand.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _pendingPrompt = MutableStateFlow<PendingPrompt?>(null)
    val pendingPrompt: StateFlow<PendingPrompt?> = _pendingPrompt.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Turn ids for which the server sent an audio stream (so TTS stays quiet). */
    private val audioTurns = mutableSetOf<String>()

    /** Turn id currently accumulating streaming reply text. */
    private var streamingTurn: String? = null

    // ---- Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        player = AudioPlayer()
        tts = TtsSpeaker(this)
        createChannels()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification("Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, buildNotification("Connecting…"))
        }

        conn = GadgetConnection(store, connListener, ::sensorsJson, serviceScope)
        conn.start()

        // Telemetry: report sensors about once a minute.
        serviceScope.launch {
            while (true) {
                delay(60_000)
                if (conn.currentState() == GadgetConnection.ConnState.CONNECTED) {
                    conn.sendState()
                }
            }
        }
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        conn.stop()
        player.stopStream()
        tts.shutdown()
        super.onDestroy()
    }

    // ---- Public API for activities ----

    fun reconnect() = conn.reconnectNow()

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        appendChat(ChatMessage(ChatMessage.Role.USER, clean))
        conn.sendTextMessage(clean)
    }

    fun sendCancel() = conn.sendCancel()

    fun sendSessionNew() {
        appendChat(ChatMessage(ChatMessage.Role.SYSTEM, "Started a new session"))
        conn.sendSessionNew()
    }

    fun answerPrompt(id: String, yes: Boolean) {
        conn.sendPromptReply(id, if (yes) "yes" else "no")
        _pendingPrompt.value = null
        appendChat(
            ChatMessage(
                ChatMessage.Role.SYSTEM,
                "Answered ${if (yes) "yes" else "no"}"
            )
        )
    }

    fun dismissPrompt() {
        _pendingPrompt.value = null
    }

    fun beginUtterance(): Pair<String, Int> = conn.beginUtterance()
    fun sendAudioChunk(stream: Int, payload: ByteArray) = conn.sendAudioChunk(stream, payload)
    fun endUtterance(id: String, stream: Int, durationMs: Long) = conn.endUtterance(id, stream, durationMs)
    fun cancelUtterance(id: String, stream: Int, reason: String) = conn.cancelUtterance(id, stream, reason)

    fun batteryPct(): Int {
        return try {
            val bm = getSystemService(BatteryManager::class.java)
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun sensorsJson(): JSONObject = JSONObject().apply {
        put("battery_pct", batteryPct())
    }

    // ---- Connection listener ----

    private val connListener = object : GadgetConnection.Listener {
        override fun onConnState(s: GadgetConnection.ConnState) {
            serviceScope.launch {
                _connState.value = s
                _caption.value = when (s) {
                    GadgetConnection.ConnState.DISCONNECTED -> "Disconnected"
                    GadgetConnection.ConnState.CONNECTING -> "Connecting…"
                    GadgetConnection.ConnState.HANDSHAKE -> "Handshaking…"
                    GadgetConnection.ConnState.PAIRING -> "Waiting for pairing approval"
                    GadgetConnection.ConnState.CONNECTED ->
                        if (_paired.value) "Connected" else "Connected (unpaired)"
                }
                updateNotification(_caption.value)
            }
        }

        override fun onMessage(m: InMsg) {
            serviceScope.launch { handleMessage(m) }
        }

        override fun onAudioFrame(stream: Int, seq: Int, payload: ByteArray) {
            player.write(stream, payload)
        }
    }

    private fun handleMessage(m: InMsg) {
        when (m) {
            is InMsg.Welcome -> {
                _paired.value = m.paired
                store.paired = m.paired
                if (!m.paired) _caption.value = "Connected — approve pairing on the Hermes host"
            }
            is InMsg.Pairing -> {
                _pairingCode.value = m.code
                _pairingCommand.value = m.command
                _caption.value = "Pairing code: ${m.code}"
            }
            is InMsg.Paired -> {
                _paired.value = true
                store.paired = true
                _pairingCode.value = null
                _pairingCommand.value = null
                _caption.value = "Paired — ready"
                appendChat(ChatMessage(ChatMessage.Role.SYSTEM, "Paired with Hermes"))
            }
            is InMsg.Unpaired -> {
                _paired.value = false
                store.paired = false
                _caption.value = "Pairing revoked"
            }
            is InMsg.TurnStart -> {
                streamingTurn = m.turn
                _caption.value = "Hermes is thinking…"
            }
            is InMsg.Transcript -> {
                appendChat(ChatMessage(ChatMessage.Role.SYSTEM, "Heard: ${m.text}"))
            }
            is InMsg.Status -> {
                _caption.value = m.text.ifEmpty { "Hermes is thinking…" }
            }
            is InMsg.ReplyDelta -> {
                streamingTurn = m.turn
                upsertAssistant(m.turn, m.text)
                _caption.value = "Hermes is replying…"
            }
            is InMsg.Reply -> {
                if (m.interim) {
                    appendChat(ChatMessage(ChatMessage.Role.SYSTEM, m.text))
                    _caption.value = m.text.take(120)
                } else {
                    upsertAssistant(m.turn, m.text)
                    streamingTurn = null
                    _caption.value = "Ready"
                    if (store.ttsEnabled && !audioTurns.contains(m.turn)) {
                        tts.speak(m.text)
                    }
                }
            }
            is InMsg.TurnEnd -> {
                if (streamingTurn == m.turn) streamingTurn = null
                audioTurns.remove(m.turn)
                _caption.value = if (m.outcome == "success") "Ready" else "Turn ${m.outcome}"
            }
            is InMsg.Notice -> {
                _caption.value = m.text
                appendChat(ChatMessage(ChatMessage.Role.SYSTEM, m.text))
            }
            is InMsg.Prompt -> {
                _pendingPrompt.value = PendingPrompt(m.id, m.title, m.text)
                appendChat(ChatMessage(ChatMessage.Role.SYSTEM, "${m.title}: ${m.text}"))
            }
            is InMsg.PromptClose -> {
                if (_pendingPrompt.value?.id == m.id) _pendingPrompt.value = null
            }
            is InMsg.Display -> {
                val body = listOfNotNull(m.title, m.body).joinToString(" — ")
                _caption.value = body.take(160)
            }
            is InMsg.AudioStart -> {
                m.turn?.let { audioTurns.add(it) }
                tts.stop()
                player.startStream(m.stream, m.rate)
                _caption.value = "Speaking…"
            }
            is InMsg.AudioEnd -> {
                player.stopStream()
                if (_caption.value == "Speaking…") _caption.value = "Ready"
            }
            is InMsg.AudioAbort -> {
                player.stopStream()
                _caption.value = "Ready"
            }
            is InMsg.Action -> handleAction(m)
            is InMsg.Error -> {
                _lastError.value = "${m.code}: ${m.message}"
                _caption.value = "Error: ${m.code}"
                Log.w(TAG, "server error ${m.code}: ${m.message}")
            }
            else -> {
                // Image frames and unknown types are ignored for now.
            }
        }
    }

    /** Replace the in-progress assistant message for `turn`, or append a new one. */
    private fun upsertAssistant(turn: String, text: String) {
        val cur = _chat.value.toMutableList()
        val idx = cur.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        val msg = ChatMessage(ChatMessage.Role.ASSISTANT, text)
        if (idx >= 0 && streamingTurn == turn) {
            cur[idx] = msg
        } else {
            cur.add(msg)
        }
        _chat.value = cur.takeLast(100)
    }

    private fun appendChat(msg: ChatMessage) {
        _chat.value = (_chat.value + msg).takeLast(100)
    }

    // ---- Device actions ----

    private fun handleAction(m: InMsg.Action) {
        when (m.name) {
            "buddy.vibrate" -> {
                try {
                    val vib = if (Build.VERSION.SDK_INT >= 31) {
                        getSystemService(Vibrator::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    }
                    vib?.vibrate(
                        VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                    conn.sendActionResult(m.id, true, JSONObject().put("vibrated", true), null)
                } catch (e: Exception) {
                    conn.sendActionResult(m.id, false, null, e.message)
                }
            }
            "buddy.notify" -> {
                try {
                    val title = m.args.optString("title", "Hermes")
                    val body = m.args.optString("body", "")
                    postActionNotification(title, body)
                    conn.sendActionResult(m.id, true, JSONObject().put("notified", true), null)
                } catch (e: Exception) {
                    conn.sendActionResult(m.id, false, null, e.message)
                }
            }
            "buddy.battery" -> {
                conn.sendActionResult(
                    m.id, true, JSONObject().put("battery_pct", batteryPct()), null
                )
            }
            else -> conn.sendActionResult(m.id, false, null, "unknown action: ${m.name}")
        }
    }

    // ---- Notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Hermes Buddy", NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ACTION_CHANNEL_ID, "Hermes actions", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Buddy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun postActionNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, ACTION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}
