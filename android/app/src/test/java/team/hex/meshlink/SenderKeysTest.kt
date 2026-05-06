package team.hex.meshlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.crypto.SenderKeys

class SenderKeysTest {

    @Test fun `advance is deterministic and irreversible-looking`() {
        val seed = ByteArray(32) { it.toByte() }
        val k1 = SenderKeys.advance(seed)
        val k2 = SenderKeys.advance(seed)
        assertArrayEquals("advance must be deterministic", k1, k2)
        // The advanced key cannot trivially be the input itself.
        assertFalse(k1.contentEquals(seed))
    }

    @Test fun `messageKey differs per counter`() {
        val seed = ByteArray(32) { it.toByte() }
        val k0 = SenderKeys.messageKey(seed, 0)
        val k1 = SenderKeys.messageKey(seed, 1)
        val k2 = SenderKeys.messageKey(seed, 2)
        assertEquals(32, k0.size)
        assertNotEquals(k0.toList(), k1.toList())
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test fun `roundtrip ratchet end to end`() {
        // Sender holds chain S, advances per message and encrypts.
        // Receiver derives same chain from the same seed.
        var sChain = SenderKeys.freshChainKey()
        var rChain = sChain.copyOf()
        var counter = 0L
        repeat(5) { i ->
            val key = SenderKeys.messageKey(sChain, counter)
            val pt = "msg-$i".toByteArray()
            val ct = Crypto.aesGcmEncrypt(key, pt)
            // Receiver reproduces the same key.
            val rKey = SenderKeys.messageKey(rChain, counter)
            val plain = Crypto.aesGcmDecrypt(rKey, ct)
            assertArrayEquals(pt, plain)
            sChain = SenderKeys.advance(sChain)
            rChain = SenderKeys.advance(rChain)
            counter++
        }
    }

    @Test fun `1to1 seed is symmetric for the same writer id`() {
        val a = Crypto.generateIdentity()
        val b = Crypto.generateIdentity()
        val ab = Crypto.deriveSessionKey(a.xPriv, b.xPub)
        val ba = Crypto.deriveSessionKey(b.xPriv, a.xPub)
        // Both derive the same seed when they agree on the writer id.
        val seed1 = SenderKeys.deriveOneToOneSeed(ab, "alice")
        val seed2 = SenderKeys.deriveOneToOneSeed(ba, "alice")
        assertArrayEquals(seed1, seed2)
        // Different writer id → different seed.
        val seed3 = SenderKeys.deriveOneToOneSeed(ab, "bob")
        assertNotEquals(seed1.toList(), seed3.toList())
    }
}
