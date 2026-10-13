package com.duckets.hermesbuddy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists chat history and the offline outbox as JSON in internal storage.
 * Small files (<= 100 messages); read synchronously, written off the main
 * thread by the caller.
 */
object ChatStore {

    private const val TAG = "ChatStore"
    private const val CHAT_FILE = "chat_history.json"
    private const val OUTBOX_FILE = "chat_outbox.json"
    const val MAX_MESSAGES = 100

    fun loadChat(dir: File): List<ChatMessage> {
        return try {
            val f = File(dir, CHAT_FILE)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = try {
                        ChatMessage.Role.valueOf(o.optString("role", "SYSTEM"))
                    } catch (_: Exception) {
                        ChatMessage.Role.SYSTEM
                    }
                    add(
                        ChatMessage(
                            role = role,
                            text = o.optString("text", ""),
                            ts = o.optLong("ts", System.currentTimeMillis()),
                            turn = o.optString("turn").ifEmpty { null },
                        )
                    )
                }
            }.takeLast(MAX_MESSAGES)
        } catch (e: Exception) {
            Log.w(TAG, "loadChat failed", e)
            emptyList()
        }
    }

    fun saveChat(dir: File, msgs: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            for (m in msgs.takeLast(MAX_MESSAGES)) {
                arr.put(
                    JSONObject()
                        .put("role", m.role.name)
                        .put("text", m.text)
                        .put("ts", m.ts)
                        .put("turn", m.turn)
                )
            }
            // Atomic write: tmp + rename, so a crash never leaves half a file.
            val tmp = File(dir, "$CHAT_FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(dir, CHAT_FILE))
        } catch (e: Exception) {
            Log.w(TAG, "saveChat failed", e)
        }
    }

    fun loadOutbox(dir: File): List<String> {
        return try {
            val f = File(dir, OUTBOX_FILE)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) add(s)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadOutbox failed", e)
            emptyList()
        }
    }

    fun saveOutbox(dir: File, msgs: List<String>) {
        try {
            val arr = JSONArray(msgs)
            val tmp = File(dir, "$OUTBOX_FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(dir, OUTBOX_FILE))
        } catch (e: Exception) {
            Log.w(TAG, "saveOutbox failed", e)
        }
    }
}
