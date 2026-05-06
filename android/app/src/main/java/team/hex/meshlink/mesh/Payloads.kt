package team.hex.meshlink.mesh

import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto

/**
 * "I'm reachable on this Wi-Fi address" — relayed through the mesh so
 * every peer can promote our connection to a fat-pipe TCP back-channel.
 *
 * Carried in a signed [MeshMessage] of type [MsgType.WIFI_HINT] without
 * 1:1 encryption (it's effectively public information already broadcast
 * by the router). The address is best-effort: if the receiver can't
 * dial it, the mesh keeps working on whatever transports remain.
 */
@Serializable
data class WifiHintPayload(
    val host: String,
    val lanPort: Int,
    val wdPort: Int,
) {
    fun encode(): ByteArray =
        MeshMessage.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decodeOrNull(b: ByteArray): WifiHintPayload? = try {
            MeshMessage.json.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        } catch (_: Throwable) { null }
    }
}

/**
 * "Here is my fresh per-sender chain key for this group" — sent 1:1
 * (encrypted with the recipient's X25519 session key) so each member
 * knows everyone's current sender state. Counter starts at 0 on the
 * issuer side; receivers persist it as the next-expected counter for
 * the sender.
 */
@Serializable
data class SenderKeyDistribution(
    val groupId: String,
    val chainKeyB64: String,
    val counter: Long = 0,
) {
    fun encode(): ByteArray =
        MeshMessage.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    fun chainKey(): ByteArray = Crypto.unb64(chainKeyB64)

    companion object {
        fun decodeOrNull(b: ByteArray): SenderKeyDistribution? = try {
            MeshMessage.json.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        } catch (_: Throwable) { null }
    }
}
