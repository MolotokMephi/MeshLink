package team.hex.meshlink.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

/**
 * Symmetric ratchet primitives shared by group "Sender Keys" and 1:1
 * chain ratchets.
 *
 *   chain_key — 32 random bytes seeded from the inviter (groups) or from
 *               an X25519 ECDH session (1:1).
 *   advance(chain_key)            -> HMAC-SHA256(chain_key, "ml-advance")
 *   messageKey(chain_key, counter) -> HMAC-SHA256(chain_key, "ml-msg" || counter)
 *
 * Forward secrecy: deriving the message key for counter N consumes the
 * chain key state and immediately advances; an adversary capturing the
 * post-send state cannot recover earlier message keys without breaking
 * HMAC pre-image resistance.
 */
object SenderKeys {

    private const val ADVANCE_LABEL = "ml-advance"
    private const val MSG_LABEL = "ml-msg"
    private const val ROOT_LABEL = "ml-root"

    /** 32 bytes of cryptographically-strong randomness. */
    fun freshChainKey(): ByteArray {
        val out = ByteArray(32)
        java.security.SecureRandom().nextBytes(out)
        return out
    }

    /** chain_key' = HMAC(chain_key, "ml-advance"). */
    fun advance(chainKey: ByteArray): ByteArray =
        hmacSha256(chainKey, ADVANCE_LABEL.toByteArray())

    /** msg_key = HMAC(chain_key, "ml-msg" || counter_be64). */
    fun messageKey(chainKey: ByteArray, counter: Long): ByteArray {
        val info = ByteBuffer.allocate(MSG_LABEL.length + 8)
            .put(MSG_LABEL.toByteArray())
            .putLong(counter)
            .array()
        return hmacSha256(chainKey, info)
    }

    /**
     * Deterministically derive a 1:1 chain seed from an X25519 session
     * key + the writer's node id. Both peers can compute the same seed
     * because the writer announces the side they're on (the seed is
     * directional — see [team.hex.meshlink.groups.PeerChain]).
     */
    fun deriveOneToOneSeed(sessionKey: ByteArray, writerNodeId: String): ByteArray {
        val info = (ROOT_LABEL + ":" + writerNodeId).toByteArray()
        return hmacSha256(sessionKey, info)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
