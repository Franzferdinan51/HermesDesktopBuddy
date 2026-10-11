package com.duckets.hermesbuddy

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp WebSocket client for the hermes-gadget.v1 protocol.
 *
 * Handles the hello/challenge/auth handshake, pairing state, ping/pong
 * heartbeat, and auto-reconnect with exponential backoff. Media frames are
 * passed through to the listener.
 */
class GadgetConnection(
    private val store: DeviceStore,
    private val listener: Listener,
    private val sensorsProvider: () -> JSONObject,
    parentScope: CoroutineScope,
) {

    interface Listener {
        fun onConnState(s: ConnState)
        fun onMessage(m: InMsg)
        fun onAudioFrame(stream: Int, seq: Int, payload: ByteArray)
    }

    enum class ConnState { DISCONNECTED, CONNECTING, HANDSHAKE, PAIRING, CONNECTED }

    companion object {
        private const val TAG = "GadgetConnection"
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.MILLISECONDS) // we run our own heartbeat
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wantStop = false
    @Volatile private var state = ConnState.DISCONNECTED
        private set

    @Volatile private var lastInboundMs = 0L
    @Volatile private var heartbeatS = 20

    private val msgIdGen = AtomicInteger(1)
    private val streamIdGen = AtomicInteger(1)
    private var audioSeq = 0

    private fun now() = System.currentTimeMillis()

    private fun setState(s: ConnState) {
        state = s
        try {
            listener.onConnState(s)
        } catch (e: Exception) {
            Log.w(TAG, "listener.onConnState threw", e)
        }
    }

    fun currentState(): ConnState = state

    /** Start the connect loop (idempotent). */
    fun start() {
        if (wantStop) wantStop = false
        scope.launch { connectLoop() }
    }

    /** Stop everything; no reconnect. */
    fun stop() {
        wantStop = true
        closeWs(1000, "stopped")
        setState(ConnState.DISCONNECTED)
    }

    /** Drop the socket; the loop reconnects immediately. */
    fun reconnectNow() {
        closeWs(1000, "manual reconnect")
    }

    private fun closeWs(code: Int, reason: String) {
        try {
            ws?.close(code, reason)
        } catch (_: Exception) {
        }
        ws = null
    }

    private suspend fun connectLoop() {
        var backoffMs = 1000L
        while (!wantStop) {
            try {
                setState(ConnState.CONNECTING)
                doConnect() // returns when the socket dies
                backoffMs = 1000L // clean close: reset backoff
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
            }
            if (wantStop) break
            setState(ConnState.DISCONNECTED)
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 60_000L)
        }
    }

    private suspend fun doConnect() {
        val url = store.hostUrl.trim().trimEnd('/')
        val keyB64 = store.deviceKeyB64()
        val key = Crypto.b64decode(keyB64)
        val deviceId = Crypto.deviceId(key)

        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", Proto.SUBPROTOCOL)
            .build()

        val done = CompletableDeferred<Unit>()
        val sockListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                lastInboundMs = now()
                setState(ConnState.HANDSHAKE)
                send(OutMsg.hello(deviceId, store.deviceName, store.micRate, sensorsProvider()))
                Log.i(TAG, "ws open, hello sent as $deviceId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundMs = now()
                handleText(text, key, deviceId)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastInboundMs = now()
                val f = BinaryFrame.decode(bytes.toByteArray()) ?: return
                when (f.channel) {
                    Proto.CH_AUDIO -> {
                        try {
                            listener.onAudioFrame(f.stream, f.seq, f.payload)
                        } catch (e: Exception) {
                            Log.w(TAG, "onAudioFrame threw", e)
                        }
                    }
                    Proto.CH_IMAGE -> {
                        // Image display not implemented on this build; ignore frames.
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closing $code $reason")
                done.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed $code $reason")
                done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                done.complete(Unit)
            }
        }

        ws = client.newWebSocket(request, sockListener)
        val hb = scope.launch { heartbeatLoop() }
        try {
            done.await()
        } finally {
            hb.cancel()
            ws = null
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            val intervalS = maxOf(5, heartbeatS / 2)
            delay(intervalS * 1000L)
            if (ws == null) return
            send(OutMsg.ping(now()))
            if (now() - lastInboundMs > heartbeatS * 3 * 1000L) {
                Log.w(TAG, "heartbeat timeout, dropping socket")
                closeWs(1001, "heartbeat timeout")
                return
            }
        }
    }

    private fun handleText(text: String, key: ByteArray, deviceId: String) {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "bad json frame", e)
            return
        }
        val type = obj.optString("type")
        when (type) {
            "challenge" -> {
                val nonce = obj.optString("nonce")
                val enrolled = obj.optBoolean("enrolled")
                val auth = if (enrolled) {
                    OutMsg.authMac(Crypto.authMac(key, deviceId, nonce))
                } else {
                    OutMsg.authKey(Crypto.b64encode(key))
                }
                send(auth)
            }
            "welcome" -> {
                heartbeatS = obj.optInt("heartbeat_s", 20).coerceAtLeast(5)
                val paired = obj.optBoolean("paired")
                setState(if (paired) ConnState.CONNECTED else ConnState.PAIRING)
                dispatch(InMsg.Welcome(obj.optString("session"), paired, heartbeatS))
            }
            "pairing" -> dispatch(InMsg.Pairing(obj.optString("code"), obj.optString("command")))
            "paired" -> {
                setState(ConnState.CONNECTED)
                dispatch(InMsg.Paired)
            }
            "unpaired" -> {
                setState(ConnState.PAIRING)
                dispatch(InMsg.Unpaired)
            }
            "ping" -> send(OutMsg.pong(obj.optLong("ts", now())))
            else -> dispatch(parseInMsg(obj))
        }
    }

    private fun dispatch(m: InMsg) {
        try {
            listener.onMessage(m)
        } catch (e: Exception) {
            Log.w(TAG, "listener.onMessage threw", e)
        }
    }

    @Synchronized
    private fun send(obj: JSONObject) {
        val s = ws ?: return
        try {
            s.send(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
        }
    }

    // ---- Outbound API used by the service / UI ----

    fun sendTextMessage(text: String): String {
        val id = "t${msgIdGen.getAndIncrement()}"
        send(OutMsg.text(id, text))
        return id
    }

    fun sendCancel() = send(OutMsg.cancel())

    fun sendSessionNew() = send(OutMsg.sessionNew())

    fun sendPromptReply(id: String, answer: String) = send(OutMsg.promptReply(id, answer))

    fun sendActionResult(id: String, ok: Boolean, result: JSONObject?, error: String?) =
        send(OutMsg.actionResult(id, ok, result, error))

    fun sendState() = send(OutMsg.state(sensorsProvider()))

    /** Returns (utterance id, stream id). Follow with sendAudioChunk, then endUtterance. */
    fun beginUtterance(): Pair<String, Int> {
        val id = "a${msgIdGen.getAndIncrement()}"
        val stream = streamIdGen.getAndIncrement() and 0xFF
        audioSeq = 0
        send(OutMsg.audioStart(id, stream, store.micRate))
        return id to stream
    }

    fun sendAudioChunk(stream: Int, payload: ByteArray) {
        val s = ws ?: return
        val frame = BinaryFrame.encode(Proto.CH_AUDIO, stream, audioSeq and 0xFFFF, payload)
        audioSeq++
        try {
            s.send(frame)
        } catch (e: Exception) {
            Log.w(TAG, "audio chunk send failed", e)
        }
    }

    fun endUtterance(id: String, stream: Int, durationMs: Long) =
        send(OutMsg.audioEnd(id, stream, durationMs))

    fun cancelUtterance(id: String, stream: Int, reason: String) =
        send(OutMsg.audioCancel(id, stream, reason))
}
