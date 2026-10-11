package com.duckets.hermesbuddy

import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * hermes-gadget.v1 message model. Text frames carry UTF-8 JSON; binary frames
 * carry a 4-byte header (u8 channel, u8 stream, u16 LE seq) + payload.
 *
 * Uses org.json (built into Android) — no extra JSON dependency.
 */
object Proto {
    const val VERSION = 1
    const val SUBPROTOCOL = "hermes-gadget.v1"
    const val FIRMWARE = "0.1.0"

    // Binary channels
    const val CH_AUDIO = 0x01
    const val CH_IMAGE = 0x02
}

/** Incoming (server -> device) messages. Unknown types are kept, not dropped. */
sealed interface InMsg {
    data class Challenge(val nonce: String, val enrolled: Boolean) : InMsg
    data class Welcome(val session: String, val paired: Boolean, val heartbeatS: Int) : InMsg
    data class Pairing(val code: String, val command: String) : InMsg
    object Paired : InMsg
    object Unpaired : InMsg
    data class Ping(val ts: Long) : InMsg
    data class TurnStart(val turn: String) : InMsg
    data class Transcript(val text: String) : InMsg
    data class Status(val text: String) : InMsg
    data class ReplyDelta(val turn: String, val text: String) : InMsg
    data class Reply(val turn: String, val text: String, val interim: Boolean) : InMsg
    data class TurnEnd(val turn: String, val outcome: String) : InMsg
    data class Notice(val text: String) : InMsg
    data class Prompt(val id: String, val title: String, val text: String) : InMsg
    data class PromptClose(val id: String) : InMsg
    data class Display(val title: String?, val body: String?) : InMsg
    data class ImageStart(val stream: Int, val width: Int, val height: Int) : InMsg
    data class ImageEnd(val stream: Int) : InMsg
    data class AudioStart(val stream: Int, val rate: Int, val turn: String?) : InMsg
    data class AudioEnd(val stream: Int) : InMsg
    data class AudioAbort(val stream: Int) : InMsg
    data class Action(val id: String, val name: String, val args: JSONObject) : InMsg
    data class Error(val code: String, val message: String) : InMsg
    data class Unknown(val type: String) : InMsg
}

fun parseInMsg(obj: JSONObject): InMsg {
    val t = obj.optString("type")
    return when (t) {
        "challenge" -> InMsg.Challenge(obj.optString("nonce"), obj.optBoolean("enrolled"))
        "welcome" -> InMsg.Welcome(
            obj.optString("session"), obj.optBoolean("paired"), obj.optInt("heartbeat_s", 20)
        )
        "pairing" -> InMsg.Pairing(obj.optString("code"), obj.optString("command"))
        "paired" -> InMsg.Paired
        "unpaired" -> InMsg.Unpaired
        "ping" -> InMsg.Ping(obj.optLong("ts"))
        "turn.start" -> InMsg.TurnStart(obj.optString("turn"))
        "transcript" -> InMsg.Transcript(obj.optString("text"))
        "status" -> InMsg.Status(obj.optString("text"))
        "reply.delta" -> InMsg.ReplyDelta(obj.optString("turn"), obj.optString("text"))
        "reply" -> InMsg.Reply(
            obj.optString("turn"), obj.optString("text"), obj.optBoolean("interim", false)
        )
        "turn.end" -> InMsg.TurnEnd(obj.optString("turn"), obj.optString("outcome"))
        "notice" -> InMsg.Notice(obj.optString("text"))
        "prompt" -> InMsg.Prompt(obj.optString("id"), obj.optString("title"), obj.optString("text"))
        "prompt.close" -> InMsg.PromptClose(obj.optString("id"))
        "display" -> InMsg.Display(
            obj.optString("title").ifEmpty { null }, obj.optString("body").ifEmpty { null }
        )
        "image.start" -> InMsg.ImageStart(
            obj.optInt("stream"), obj.optInt("width"), obj.optInt("height")
        )
        "image.end" -> InMsg.ImageEnd(obj.optInt("stream"))
        "audio.start" -> InMsg.AudioStart(
            obj.optInt("stream"), obj.optInt("rate", 16000),
            obj.optString("turn").ifEmpty { null }
        )
        "audio.end" -> InMsg.AudioEnd(obj.optInt("stream"))
        "audio.abort" -> InMsg.AudioAbort(obj.optInt("stream"))
        "action" -> InMsg.Action(obj.optString("id"), obj.optString("name"), obj.optJSONObject("args") ?: JSONObject())
        "error" -> InMsg.Error(obj.optString("code"), obj.optString("message"))
        else -> InMsg.Unknown(t)
    }
}

/** Outgoing (device -> server) message builders. */
object OutMsg {

    fun hello(
        deviceId: String,
        name: String,
        micRate: Int,
        sensors: JSONObject,
    ): JSONObject = JSONObject().apply {
        put("type", "hello")
        put("proto", Proto.VERSION)
        put("device_id", deviceId)
        put("name", name)
        put("board", "android")
        put("firmware", Proto.FIRMWARE)
        put("caps", JSONObject().apply {
            put("mic", JSONObject().apply {
                put("rate", micRate)
                put("format", "pcm16")
            })
            put("speaker", JSONObject().apply {
                put("rate", 16000)
                put("format", "pcm16")
            })
            put("inputs", JSONArray(listOf("talk", "cancel")))
            put("talk_mode", "hold")
        })
        put("actions", JSONArray().apply {
            put(JSONObject().apply {
                put("name", "buddy.vibrate")
                put("description", "Vibrate the phone briefly.")
                put("params", JSONObject().apply { put("type", "object") })
            })
            put(JSONObject().apply {
                put("name", "buddy.notify")
                put("description", "Post a notification on the phone.")
                put(
                    "params", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("title", JSONObject().apply { put("type", "string") })
                            put("body", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray(listOf("title", "body")))
                    }
                )
            })
            put(JSONObject().apply {
                put("name", "buddy.battery")
                put("description", "Return the phone battery percentage.")
                put("params", JSONObject().apply { put("type", "object") })
            })
        })
        put("sensors", sensors)
    }

    fun authKey(keyB64: String): JSONObject = JSONObject().apply {
        put("type", "auth")
        put("key", keyB64)
    }

    fun authMac(macB64: String): JSONObject = JSONObject().apply {
        put("type", "auth")
        put("mac", macB64)
    }

    fun pong(ts: Long): JSONObject = JSONObject().apply {
        put("type", "pong")
        put("ts", ts)
    }

    fun ping(ts: Long): JSONObject = JSONObject().apply {
        put("type", "ping")
        put("ts", ts)
    }

    fun text(id: String, text: String): JSONObject = JSONObject().apply {
        put("type", "text")
        put("id", id)
        put("text", text)
    }

    fun audioStart(id: String, stream: Int, rate: Int): JSONObject = JSONObject().apply {
        put("type", "audio.start")
        put("id", id)
        put("stream", stream)
        put("rate", rate)
        put("format", "pcm16")
        put("mode", "hold")
    }

    fun audioEnd(id: String, stream: Int, durationMs: Long): JSONObject = JSONObject().apply {
        put("type", "audio.end")
        put("id", id)
        put("stream", stream)
        put("duration_ms", durationMs)
    }

    fun audioCancel(id: String, stream: Int, reason: String): JSONObject = JSONObject().apply {
        put("type", "audio.cancel")
        put("id", id)
        put("stream", stream)
        put("reason", reason)
    }

    fun cancel(): JSONObject = JSONObject().apply { put("type", "cancel") }

    fun sessionNew(): JSONObject = JSONObject().apply { put("type", "session.new") }

    fun promptReply(id: String, answer: String): JSONObject = JSONObject().apply {
        put("type", "prompt.reply")
        put("id", id)
        put("answer", answer) // "yes" or "no"
    }

    fun actionResult(id: String, ok: Boolean, result: JSONObject?, error: String?): JSONObject =
        JSONObject().apply {
            put("type", "action.result")
            put("id", id)
            put("ok", ok)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }

    fun state(sensors: JSONObject): JSONObject = JSONObject().apply {
        put("type", "state")
        put("sensors", sensors)
    }
}

/** Binary frame encode/decode: u8 channel, u8 stream, u16 LE seq, then payload. */
object BinaryFrame {

    data class Decoded(val channel: Int, val stream: Int, val seq: Int, val payload: ByteArray)

    fun encode(channel: Int, stream: Int, seq: Int, payload: ByteArray): ByteString {
        val buf = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(channel.toByte())
        buf.put(stream.toByte())
        buf.putShort(seq.toShort())
        buf.put(payload)
        return ByteString.of(*buf.array())
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channel = buf.get().toInt() and 0xFF
        val stream = buf.get().toInt() and 0xFF
        val seq = buf.short.toInt() and 0xFFFF
        val payload = bytes.copyOfRange(4, bytes.size)
        return Decoded(channel, stream, seq, payload)
    }
}
