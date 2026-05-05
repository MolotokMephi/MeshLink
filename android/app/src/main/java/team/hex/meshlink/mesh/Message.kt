package team.hex.meshlink.mesh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.hex.meshlink.crypto.Crypto

/**
 * Wire-level message envelope. Mirrors the layout used by core/messaging.py:
 *   type, sender_id, sender_name, payload, timestamp, msg_id, ttl,
 *   relay_path, signature, recipient_id (added for direct addressing).
 *
 * Payload bytes are application-defined (text, file chunk, group ciphertext,
 * etc.) and carried as base64. For 1:1 private messages they're typically
 * AES-GCM ciphertext keyed by the X25519 ECDH session key with the
 * recipient. For group messages they're AES-GCM under a shared group key.
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
    /** Direct recipient node id; null = broadcast / group / mesh-routed by other means. */
    val recipientId: String? = null,
    /** Optional group id for GROUP_TEXT, FILE_OFFER addressed to a group, etc. */
    val groupId: String? = null,
    /** Random nonce for anti-replay checks. */
    val nonce: String = "",
    val signature: String = ""
) {
    fun canonicalBytes(): ByteArray {
        // Sign over everything except the fields that mutate per relay hop:
        // `signature` (filled in after hashing), `relayPath` (each node
        // appends its id), and `ttl` (each node decrements it).
        val canon = json.encodeToString(
            serializer(),
            copy(signature = "", relayPath = emptyList(), ttl = 0)
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

    fun toBytes(): ByteArray = json.encodeToString(serializer(), this)
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
    const val TEXT = 1                // 1:1 ciphertext (X25519 ECDH session key)
    const val FILE_OFFER = 2
    const val FILE_ACCEPT = 3
    const val FILE_REJECT = 4
    const val FILE_CHUNK = 5
    const val FILE_COMPLETE = 6
    const val KEY_EXCHANGE = 20
    const val PING = 30
    const val PONG = 31
    const val TYPING = 40
    const val READ_RECEIPT = 41
    const val DELIVERY_ACK = 42
    const val MESH_RELAY = 60         // unused — flooding handled in router
    const val SEED_PAIR = 70
    const val ANNOUNCE = 80           // identity advertisement (edPub + xPub)
    const val GROUP_TEXT = 90         // group ciphertext (group_id + AES-GCM)
    const val GROUP_INVITE = 91
}
