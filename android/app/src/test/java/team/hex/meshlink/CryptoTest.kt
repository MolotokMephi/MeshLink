package team.hex.meshlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.hex.meshlink.crypto.Crypto

class CryptoTest {

    @Test fun `aes gcm round trips`() {
        val key = ByteArray(32) { it.toByte() }
        val pt = "hello mesh".toByteArray()
        val blob = Crypto.aesGcmEncrypt(key, pt)
        val dec = Crypto.aesGcmDecrypt(key, blob)
        assertArrayEquals(pt, dec)
    }

    @Test fun `ed25519 sign and verify`() {
        val id = Crypto.generateIdentity()
        val data = "payload".toByteArray()
        val sig = Crypto.sign(id.edPriv, data)
        assertTrue(Crypto.verify(id.edPub, data, sig))
        // Tamper detection.
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(Crypto.verify(id.edPub, tampered, sig))
    }

    @Test fun `x25519 ecdh symmetric`() {
        val a = Crypto.generateIdentity()
        val b = Crypto.generateIdentity()
        val ab = Crypto.deriveSessionKey(a.xPriv, b.xPub)
        val ba = Crypto.deriveSessionKey(b.xPriv, a.xPub)
        assertArrayEquals(ab, ba)
        val c = Crypto.generateIdentity()
        val ac = Crypto.deriveSessionKey(a.xPriv, c.xPub)
        assertNotEquals(ab.toList(), ac.toList())
    }
}
