package com.duckets.hermesbuddy

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure on-device storage: the 32-byte device key (base64), the gateway URL,
 * device name, and UI prefs. Backed by EncryptedSharedPreferences.
 */
class DeviceStore(context: Context) {

    companion object {
        const val DEFAULT_HOST_URL = "ws://100.68.208.113:8765/gadget"
        const val DEFAULT_DEVICE_NAME = "Duckets Phone"
        const val DEFAULT_MIC_RATE = 16000
    }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "hermes_buddy_secure",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Returns the stored device key (base64), generating and storing one on first use. */
    @Synchronized
    fun deviceKeyB64(): String {
        var key = prefs.getString("device_key", null)
        if (key == null) {
            key = Crypto.b64encode(Crypto.newKey())
            prefs.edit().putString("device_key", key).apply()
        }
        return key
    }

    fun deviceKey(): ByteArray = Crypto.b64decode(deviceKeyB64())

    fun deviceId(): String = Crypto.deviceId(deviceKey())

    /** Forget the device key; the next read generates a fresh one. */
    @Synchronized
    fun resetKey() {
        prefs.edit().remove("device_key").putBoolean("paired", false).apply()
    }

    var hostUrl: String
        get() = prefs.getString("host_url", DEFAULT_HOST_URL) ?: DEFAULT_HOST_URL
        set(v) = prefs.edit().putString("host_url", v).apply()

    var deviceName: String
        get() = prefs.getString("device_name", DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(v) = prefs.edit().putString("device_name", v).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts_enabled", true)
        set(v) = prefs.edit().putBoolean("tts_enabled", v).apply()

    var micRate: Int
        get() = prefs.getInt("mic_rate", DEFAULT_MIC_RATE)
        set(v) = prefs.edit().putInt("mic_rate", v).apply()

    /** Last known server-side paired flag (informational; the server enforces it). */
    var paired: Boolean
        get() = prefs.getBoolean("paired", false)
        set(v) = prefs.edit().putBoolean("paired", v).apply()
}
