package com.duckets.hermesbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Vectors generated independently with Python hashlib/hmac (see task log):
 * key = bytes(range(32)), nonce = "test-nonce-1".
 */
class CryptoTest {

    private val key = Base64.getDecoder().decode(
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    )

    @Test
    fun `device id matches protocol spec`() {
        assertEquals("hg-630dcd2966c43366", Crypto.deviceId(key))
    }

    @Test
    fun `auth mac matches protocol spec`() {
        assertEquals(
            "8EGaHJkP/8WWAAbstjvZj6QH9d+CjR5TMYDRi+ELgsc=",
            Crypto.authMac(key, "hg-630dcd2966c43366", "test-nonce-1")
        )
    }

    @Test
    fun `device id is stable and well formed`() {
        val a = Crypto.deviceId(key)
        val b = Crypto.deviceId(key.copyOf())
        assertEquals(a, b)
        assertTrue(a.matches(Regex("hg-[0-9a-f]{16}")))
    }

    @Test
    fun `new key is 32 random bytes`() {
        val k1 = Crypto.newKey()
        val k2 = Crypto.newKey()
        assertEquals(32, k1.size)
        assertTrue(!k1.contentEquals(k2))
    }

    @Test
    fun `base64 round trip`() {
        val bytes = ByteArray(64) { (it * 7 % 251).toByte() }
        assertTrue(Crypto.b64decode(Crypto.b64encode(bytes)).contentEquals(bytes))
    }
}
