package team.hex.meshlink.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import team.hex.meshlink.crypto.Crypto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Mesh routing layer: signs outgoing messages, dedups & TTL-decrements
 * incoming ones, and re-broadcasts to other peers (flooding).
 *
 * Transport-agnostic. The BLE layer wires itself up by:
 *   - calling [onIncoming] for every frame received from any peer link
 *   - subscribing to [outgoing] to push frames to all connected peers
 *
 * The router does NOT decrypt application payloads — it just routes the
 * envelope. Decryption happens in the app layer using session keys
 * derived from peer X25519 public keys.
 */
class MeshRouter(
    private val identity: Crypto.IdentityKeys,
    private val displayName: String,
) {
    private val tag = "MeshRouter"
    val nodeId: String = identity.nodeId()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Recently seen msg_ids so we don't loop messages forever.
    // Bounded LRU-ish cache: insertion order via LinkedHashMap.
    private val seen = object : LinkedHashMap<String, Long>(SEEN_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>?): Boolean = size > SEEN_CAP
    }
    private val seenLock = Any()

    // Known peer identities, indexed by nodeId. Populated by ANNOUNCE frames.
    private val peers = ConcurrentHashMap<String, PeerIdentity>()

    private val _outgoing = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    /** Frames the transport must broadcast to all currently-linked peers. */
    val outgoing: SharedFlow<MeshMessage> = _outgoing.asSharedFlow()

    private val _appInbox = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    /** Frames addressed to us (or broadcast) that the app should consume. */
    val appInbox: SharedFlow<MeshMessage> = _appInbox.asSharedFlow()

    private val _peerEvents = MutableSharedFlow<PeerIdentity>(extraBufferCapacity = 32)
    val peerEvents: SharedFlow<PeerIdentity> = _peerEvents.asSharedFlow()

    /** Build, sign, and inject a message into the mesh. */
    fun send(
        type: Int,
        payload: ByteArray,
        recipientId: String? = null,
        ttl: Int = MeshMessage.DEFAULT_TTL,
    ) {
        val msg = MeshMessage(
            type = type,
            senderId = nodeId,
            senderName = displayName,
            payloadB64 = Crypto.b64(payload),
            timestamp = System.currentTimeMillis(),
            msgId = "$nodeId-${UUID.randomUUID()}",
            ttl = ttl,
            relayPath = listOf(nodeId),
            recipientId = recipientId,
        ).signedWith(identity.edPriv)
        markSeen(msg.msgId)
        scope.launch { _outgoing.emit(msg) }
    }

    /** Periodic "I exist, here are my keys" beacon. */
    fun broadcastAnnounce() {
        val payload = AnnouncePayload(
            edPubB64 = Crypto.b64(identity.edPub),
            xPubB64 = Crypto.b64(identity.xPub),
            displayName = displayName,
        ).encode()
        send(MsgType.ANNOUNCE, payload, recipientId = null, ttl = 3)
    }

    /** Called by transport for every received frame. */
    fun onIncoming(raw: ByteArray) {
        val msg = try {
            MeshMessage.fromBytes(raw)
        } catch (t: Throwable) {
            Log.w(tag, "drop: unparseable frame (${t.message})")
            return
        }
        if (msg.senderId == nodeId) return // our own echo
        if (alreadySeen(msg.msgId)) return
        markSeen(msg.msgId)

        // ANNOUNCE frames are authenticated via their advertised edPub.
        // Other frames are verified against the sender's previously-announced edPub.
        val verified = when (msg.type) {
            MsgType.ANNOUNCE -> verifyAnnounce(msg)
            else -> verifyKnownSender(msg)
        }
        if (!verified) {
            Log.w(tag, "drop: signature failed for ${msg.msgId} from ${msg.senderId}")
            return
        }

        // Deliver locally if it concerns us.
        val forUs = msg.recipientId == null || msg.recipientId == nodeId
        if (forUs) scope.launch { _appInbox.emit(msg) }

        // Relay (flooding). Decrement TTL, append our id to path.
        if (msg.ttl > 1 && msg.recipientId != nodeId) {
            val relayed = msg.copy(
                ttl = msg.ttl - 1,
                relayPath = msg.relayPath + nodeId,
            )
            scope.launch { _outgoing.emit(relayed) }
        }
    }

    fun knownPeers(): List<PeerIdentity> = peers.values.toList()
    fun peerById(id: String): PeerIdentity? = peers[id]

    private fun verifyAnnounce(msg: MeshMessage): Boolean {
        val ann = AnnouncePayload.decodeOrNull(Crypto.unb64(msg.payloadB64)) ?: return false
        val edPub = Crypto.unb64(ann.edPubB64)
        if (!msg.verifyWith(edPub)) return false
        val pid = PeerIdentity(
            nodeId = msg.senderId,
            displayName = ann.displayName.ifBlank { msg.senderName },
            edPub = edPub,
            xPub = Crypto.unb64(ann.xPubB64),
            lastSeenMs = System.currentTimeMillis(),
        )
        val prev = peers.put(pid.nodeId, pid)
        if (prev == null || prev.edPub.size != pid.edPub.size ||
            !prev.edPub.contentEquals(pid.edPub)) {
            scope.launch { _peerEvents.emit(pid) }
        }
        return true
    }

    private fun verifyKnownSender(msg: MeshMessage): Boolean {
        val peer = peers[msg.senderId] ?: return false // unknown identity → drop
        val ok = msg.verifyWith(peer.edPub)
        if (ok) peers[msg.senderId] = peer.copy(lastSeenMs = System.currentTimeMillis())
        return ok
    }

    private fun alreadySeen(id: String): Boolean = synchronized(seenLock) { seen.containsKey(id) }
    private fun markSeen(id: String) { synchronized(seenLock) { seen[id] = System.currentTimeMillis() } }

    companion object {
        private const val SEEN_CAP = 4096
    }
}

data class PeerIdentity(
    val nodeId: String,
    val displayName: String,
    val edPub: ByteArray,
    val xPub: ByteArray,
    val lastSeenMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is PeerIdentity && other.nodeId == nodeId
    override fun hashCode(): Int = nodeId.hashCode()
}

@kotlinx.serialization.Serializable
data class AnnouncePayload(
    val edPubB64: String,
    val xPubB64: String,
    val displayName: String,
) {
    fun encode(): ByteArray =
        MeshMessage.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun decodeOrNull(b: ByteArray): AnnouncePayload? = try {
            MeshMessage.json.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        } catch (_: Throwable) { null }
    }
}
