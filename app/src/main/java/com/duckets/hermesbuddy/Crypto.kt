package com.duckets.hermesbuddy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Device identity + auth crypto for the hermes-gadget.v1 handshake.
 *
 * Uses java.util.Base64 (available on API 26+, this app's minSdk) so the
 * object stays unit-testable on the JVM without Robolectric.
 */
object Crypto {

    /** 32 fresh random bytes — the device key. */
    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** device_id = "hg-" + hex(sha256(key))[0:16] */
    fun deviceId(key: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(key).toHex()
        return "hg-" + hex.substring(0, 16)
    }

    /**
     * mac = HMAC-SHA256(key, "hermes-gadget/v1|" + device_id + "|" + nonce),
     * base64-encoded for the `auth` message.
     */
    fun authMac(key: ByteArray, deviceId: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val raw = mac.doFinal("hermes-gadget/v1|$deviceId|$nonce".toByteArray(Charsets.UTF_8))
        return b64encode(raw)
    }

    fun b64encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    fun b64decode(s: String): ByteArray =
        Base64.getMimeDecoder().decode(s)

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
