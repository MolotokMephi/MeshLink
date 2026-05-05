package team.hex.meshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MsgType

class MeshMessageTest {

    @Test fun `signed envelope round-trips`() {
        val id = Crypto.generateIdentity()
        val original = MeshMessage(
            type = MsgType.TEXT,
            senderId = id.nodeId(),
            senderName = "alice",
            payloadB64 = Crypto.b64("hi".toByteArray()),
            timestamp = 1_700_000_000_000L,
            msgId = "m-1",
            recipientId = "bob",
            nonce = "n",
        ).signedWith(id.edPriv)

        val wire = original.toBytes()
        val parsed = MeshMessage.fromBytes(wire)
        assertEquals(original, parsed)
        assertTrue(parsed.verifyWith(id.edPub))
    }

    @Test fun `signature ignores relay path mutations`() {
        val id = Crypto.generateIdentity()
        val msg = MeshMessage(
            type = MsgType.TEXT,
            senderId = id.nodeId(),
            senderName = "alice",
            payloadB64 = Crypto.b64("hi".toByteArray()),
            timestamp = 1L, msgId = "m", nonce = "n",
        ).signedWith(id.edPriv)

        // Simulate a relay decrement-and-append.
        val relayed = msg.copy(ttl = msg.ttl - 1, relayPath = msg.relayPath + "relay-1")
        assertTrue(relayed.verifyWith(id.edPub))
    }

    @Test fun `tampered payload fails verification`() {
        val id = Crypto.generateIdentity()
        val msg = MeshMessage(
            type = MsgType.TEXT,
            senderId = id.nodeId(), senderName = "alice",
            payloadB64 = Crypto.b64("hi".toByteArray()),
            timestamp = 1L, msgId = "m", nonce = "n",
        ).signedWith(id.edPriv)
        val tampered = msg.copy(payloadB64 = Crypto.b64("bye".toByteArray()))
        assertFalse(tampered.verifyWith(id.edPub))
    }
}
