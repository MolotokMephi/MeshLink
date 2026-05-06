package team.hex.meshlink.groups

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.crypto.SenderKeys
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerChainRow
import java.util.concurrent.ConcurrentHashMap

/**
 * 1:1 forward-secret message channel.
 *
 * Each direction has its own chain. The chain seed is derived
 * deterministically from `HKDF(session_key, "ml-root:" || writer_node_id)`
 * so both peers compute the same starting key without an explicit
 * handshake. From there:
 *
 *   send: msg_key = HMAC(chain_send, "ml-msg" || counter); chain_send = advance(chain_send)
 *   recv: same, on the receiving side, fast-forwarding the chain to the
 *         message counter so out-of-order delivery still decrypts.
 *
 * Wire payload becomes [PeerChainMessage] (counter + ciphertext); the
 * sender id is already in the [team.hex.meshlink.mesh.MeshMessage]
 * envelope. Falls back to the raw AES-GCM(session_key, plain) path on
 * decode failure so old peers stay reachable.
 */
class PeerChain(
    private val db: MeshDb,
    private val selfNodeId: String,
    private val ourXPriv: ByteArray,
) {
    /**
     * Per-(peer, direction) lock around the read-modify-write ratchet
     * step. Without it two concurrent encrypt() calls would read the
     * same chain key, derive the same AES-GCM message key, and produce
     * a key+nonce reuse — fatal for AES-GCM confidentiality.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(peerId: String, direction: String): Mutex =
        locks.getOrPut("${'$'}direction:${'$'}peerId") { Mutex() }

    /**
     * Encrypt with our send chain to [peerId]. Returns the wire bytes
     * (a [PeerChainMessage] JSON). Initializes the chain on first call.
     */
    suspend fun encrypt(peerId: String, peerXPub: ByteArray, plaintext: ByteArray): ByteArray? {
        return lockFor(peerId, "send").withLock {
            val state = ensureChain(peerId, peerXPub, direction = "send", writerId = selfNodeId)
            val msgKey = SenderKeys.messageKey(state.chainKey, state.counter)
            val ciphertext = Crypto.aesGcmEncrypt(msgKey, plaintext)
            val payload = PeerChainMessage(state.counter, Crypto.b64(ciphertext)).toBytes()
            db.peerChainDao().upsert(state.copy(
                chainKey = SenderKeys.advance(state.chainKey),
                counter = state.counter + 1,
            ))
            payload
        }
    }

    /**
     * Decrypt a payload from [peerId]. Initializes the receive chain on
     * first call. Returns plaintext or null on bad ciphertext / replay /
     * unrecognised wire format.
     */
    suspend fun decrypt(peerId: String, peerXPub: ByteArray, payload: ByteArray): ByteArray? {
        val msg = PeerChainMessage.fromBytes(payload) ?: return null
        return lockFor(peerId, "recv").withLock {
            val state = ensureChain(peerId, peerXPub, direction = "recv", writerId = peerId)
            if (msg.counter < state.counter) return@withLock null
            var chain = state.chainKey
            var counter = state.counter
            while (counter < msg.counter) {
                chain = SenderKeys.advance(chain)
                counter++
                if (counter - state.counter > 1024) return@withLock null
            }
            val key = SenderKeys.messageKey(chain, counter)
            val ct = Crypto.unb64(msg.ciphertextB64)
            val plain = runCatching { Crypto.aesGcmDecrypt(key, ct) }.getOrNull()
                ?: return@withLock null
            db.peerChainDao().upsert(state.copy(
                chainKey = SenderKeys.advance(chain),
                counter = counter + 1,
            ))
            plain
        }
    }

    private suspend fun ensureChain(
        peerId: String, peerXPub: ByteArray,
        direction: String, writerId: String,
    ): PeerChainRow {
        val existing = db.peerChainDao().get(peerId, direction)
        if (existing != null) return existing
        val sessionKey = Crypto.deriveSessionKey(ourXPriv, peerXPub)
        val seed = SenderKeys.deriveOneToOneSeed(sessionKey, writerId)
        val fresh = PeerChainRow(
            peerId = peerId,
            direction = direction,
            chainKey = seed,
            counter = 0L,
        )
        db.peerChainDao().upsert(fresh)
        return fresh
    }
}

/** Wire format for forward-secret 1:1 ciphertext (carried inside TEXT). */
@Serializable
data class PeerChainMessage(
    val counter: Long,
    val ciphertextB64: String,
) {
    fun toBytes(): ByteArray =
        MeshMessage.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(b: ByteArray): PeerChainMessage? = try {
            MeshMessage.json.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        } catch (_: Throwable) { null }
    }
}
