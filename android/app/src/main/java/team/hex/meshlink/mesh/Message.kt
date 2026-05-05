package team.hex.meshlink.mesh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.hex.meshlink.crypto.Crypto

/**
 * Wire-level message. Mirrors the layout used by core/messaging.py:
 *   type, sender_id, sender_name, payload, timestamp, msg_id, ttl,
 *   relay_path, signature.
 *
 * Payload bytes are application-defined (text, file chunk, etc.) and are
 * carried as base64. For private messages they're typically AES-GCM
 * ciphertext keyed by the X25519 ECDH session key with the recipient.
 */
@Serializable
data class MeshMessage(
    val type: Int,
    val senderId: String,
    val senderName: String,
    val payloadB64: String,
    val timestamp: Long,
    val msgId: String,
    val ttl: Int = DEFAULT_TTL,
    val relayPath: List<String> = emptyList(),
    val recipientId: String? = null, // null = broadcast
    val signature: String = ""
) {
    fun canonicalBytes(): ByteArray {
        // Sign over everything except `signature` and `relayPath` (which mutates as
        // the message hops through the mesh).
        val canon = json.encodeToString(
            MeshMessage.serializer(),
            copy(signature = "", relayPath = emptyList())
        )
        return canon.toByteArray(Charsets.UTF_8)
    }

    fun signedWith(edPriv: ByteArray): MeshMessage {
        val sig = Crypto.sign(edPriv, canonicalBytes())
        return copy(signature = Crypto.b64(sig))
    }

    fun verifyWith(edPub: ByteArray): Boolean {
        if (signature.isEmpty()) return false
        return Crypto.verify(edPub, canonicalBytes(), Crypto.unb64(signature))
    }

    fun toBytes(): ByteArray = json.encodeToString(MeshMessage.serializer(), this)
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val DEFAULT_TTL = 8

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromBytes(data: ByteArray): MeshMessage =
            json.decodeFromString(serializer(), data.toString(Charsets.UTF_8))
    }
}

object MsgType {
    const val TEXT = 1
    const val KEY_EXCHANGE = 20
    const val PING = 30
    const val PONG = 31
    const val DELIVERY_ACK = 42
    const val MESH_RELAY = 60
    const val ANNOUNCE = 80 // identity advertisement (edPub + xPub)
}
