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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Mesh routing layer. Transport-agnostic.
 *
 *   - signs outgoing messages with our Ed25519 key
 *   - dedups incoming by msg_id (in-memory LRU + optional persistent backing)
 *   - enforces a clock-skew window to defeat replay of old envelopes
 *   - rate-limits per-sender to defend against single-source flooding
 *   - decrements TTL and rebroadcasts (flooding) for relay
 *
 * The transport layer wires itself up by:
 *   - calling [onIncoming] for every frame received from any peer link
 *   - subscribing to [outgoing] to push frames to all connected peers
 *
 * The router does NOT decrypt application payloads — it just routes the
 * envelope. Decryption happens in the app layer using session keys
 * derived from peer X25519 public keys.
 */
class MeshRouter(
    private val identity: Crypto.IdentityKeys,
    initialDisplayName: String,
    /** Optional persistent dedup store. Called best-effort, off the hot path. */
    private val seenStore: SeenStore = SeenStore.NoOp,
) {
    private val tag = "MeshRouter"
    val nodeId: String = identity.nodeId()

    /**
     * Display name advertised in announces. Mutable so the user can rename
     * themselves at runtime — the next announce picks up the new value
     * without requiring a service restart.
     */
    @Volatile var displayName: String = initialDisplayName

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rng = SecureRandom()

    // Recently seen msg_ids — bounded LRU.
    private val seen = object : LinkedHashMap<String, Long>(SEEN_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>?): Boolean = size > SEEN_CAP
    }
    private val seenLock = Any()

    // Anti-replay nonce window per sender (last N nonces seen).
    private val nonceWindows = ConcurrentHashMap<String, ArrayDeque<String>>()

    // Per-sender token bucket for rate limiting.
    private val rateBuckets = ConcurrentHashMap<String, RateBucket>()

    // Known peer identities, indexed by nodeId.
    private val peers = ConcurrentHashMap<String, PeerIdentity>()

    /** Adjacency graph used for shortest-path next-hop hints. */
    val graph = MeshGraph(nodeId)

    /** Per-sender temporary ban (set when a sender persistently exceeds rate). */
    private val banUntil = ConcurrentHashMap<String, Long>()

    private val _outgoing = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    val outgoing: SharedFlow<MeshMessage> = _outgoing.asSharedFlow()

    private val _appInbox = MutableSharedFlow<MeshMessage>(extraBufferCapacity = 64)
    val appInbox: SharedFlow<MeshMessage> = _appInbox.asSharedFlow()

    private val _peerEvents = MutableSharedFlow<PeerIdentity>(extraBufferCapacity = 32)
    val peerEvents: SharedFlow<PeerIdentity> = _peerEvents.asSharedFlow()

    private val _drops = MutableSharedFlow<DropReason>(extraBufferCapacity = 32)
    val drops: SharedFlow<DropReason> = _drops.asSharedFlow()

    /**
     * Emitted when a previously-seen [PeerIdentity] presents a different
     * Ed25519 public key under the same node id (i.e. the device was
     * factory-reset, or someone is impersonating). UI surfaces this as a
     * trust-on-first-use mismatch.
     */
    private val _identityConflicts = MutableSharedFlow<IdentityConflict>(extraBufferCapacity = 8)
    val identityConflicts: SharedFlow<IdentityConflict> = _identityConflicts.asSharedFlow()

    /** Hydrate dedup state from persistent storage. Call once at startup. */
    suspend fun hydrate() {
        val ids = seenStore.recentIds(SEEN_CAP)
        synchronized(seenLock) {
            val now = System.currentTimeMillis()
            for (id in ids) seen[id] = now
        }
    }

    /**
     * Build & sign an envelope without emitting. Use [emit] to actually
     * inject it; the outbox does this so it can persist the exact bytes
     * before they hit the wire.
     */
    fun buildSigned(
        type: Int,
        payload: ByteArray,
        recipientId: String? = null,
        groupId: String? = null,
        ttl: Int = MeshMessage.DEFAULT_TTL,
    ): MeshMessage {
        val msgId = "$nodeId-${UUID.randomUUID()}"
        val nonce = ByteArray(8).also(rng::nextBytes).let(Crypto::b64)
        // If we know a shortest path to the recipient, trim TTL so we don't
        // flood the entire mesh for a message we know how to deliver in 3
        // hops. Add slack so a single flapping link doesn't drop the message.
        val effectiveTtl = if (recipientId != null) {
            val dist = graph.distanceTo(recipientId)
            if (dist != null && dist > 0) (dist + 2).coerceIn(2, ttl) else ttl
        } else ttl
        return MeshMessage(
            type = type,
            senderId = nodeId,
            senderName = displayName,
            payloadB64 = Crypto.b64(payload),
            timestamp = System.currentTimeMillis(),
            msgId = msgId,
            ttl = effectiveTtl,
            relayPath = listOf(nodeId),
            recipientId = recipientId,
            groupId = groupId,
            nonce = nonce,
        ).signedWith(identity.edPriv)
    }

    /**
     * Build, sign, and inject a message into the mesh. Convenience for
     * fire-and-forget broadcasts (announce/ping/typing) that don't go
     * through the outbox. Returns the msg_id used.
     */
    fun send(
        type: Int,
        payload: ByteArray,
        recipientId: String? = null,
        groupId: String? = null,
        ttl: Int = MeshMessage.DEFAULT_TTL,
    ): String {
        val msg = buildSigned(type, payload, recipientId, groupId, ttl)
        emit(msg)
        return msg.msgId
    }

    /** Inject a pre-built signed envelope into the mesh. */
    fun emit(envelope: MeshMessage) {
        markSeen(envelope.msgId)
        scope.launch { _outgoing.emit(envelope) }
    }

    /** Re-emit a previously-built signed envelope (used by outbox retries). */
    fun resend(envelope: MeshMessage) {
        scope.launch { _outgoing.emit(envelope) }
    }

    /**
     * Re-sign [envelope] with a fresh timestamp and nonce while keeping
     * its msg_id (so DELIVERY_ACK still matches). Used by the outbox so
     * retries don't get rejected by recipients' clock-skew window.
     */
    fun refreshAndSign(envelope: MeshMessage): MeshMessage {
        val nonce = ByteArray(8).also(rng::nextBytes).let(Crypto::b64)
        return envelope.copy(
            timestamp = System.currentTimeMillis(),
            nonce = nonce,
            relayPath = listOf(nodeId),
            signature = "",
        ).signedWith(identity.edPriv)
    }

    /**
     * Periodic "I exist, here are my keys" beacon. The default TTL is
     * deliberately generous so identities propagate across long mesh
     * paths — without this, peers more than three hops away never learn
     * each others' edPub keys and can't validate signed envelopes.
     */
    fun broadcastAnnounce() {
        val payload = AnnouncePayload(
            edPubB64 = Crypto.b64(identity.edPub),
            xPubB64 = Crypto.b64(identity.xPub),
            displayName = displayName,
        ).encode()
        send(MsgType.ANNOUNCE, payload, recipientId = null, ttl = MeshMessage.DEFAULT_TTL)
    }

    /** Called by transport for every received frame. */
    fun onIncoming(raw: ByteArray) {
        val msg = try {
            MeshMessage.fromBytes(raw)
        } catch (t: Throwable) {
            drop(DropReason.Unparseable, null, t.message)
            return
        }
        if (msg.senderId == nodeId) return // our own echo
        if (alreadySeen(msg.msgId)) return
        markSeen(msg.msgId)

        // Anti-replay: timestamp must be within MAX_CLOCK_SKEW_MS of now.
        val skew = abs(System.currentTimeMillis() - msg.timestamp)
        if (skew > MAX_CLOCK_SKEW_MS) {
            drop(DropReason.ClockSkew, msg.senderId, "skew=${skew}ms")
            return
        }
        // Anti-replay: the same (sender, nonce) must not appear twice.
        if (msg.nonce.isNotEmpty() && !registerNonce(msg.senderId, msg.nonce)) {
            drop(DropReason.NonceReplay, msg.senderId, msg.nonce)
            return
        }
        // Loop-detect: if our id is already in the relay path, drop.
        if (msg.relayPath.contains(nodeId)) {
            drop(DropReason.LoopDetected, msg.senderId, null)
            return
        }
        // Rate limit per sender (token bucket); persistent abusers get banned.
        val banUnt = banUntil[msg.senderId]
        if (banUnt != null && System.currentTimeMillis() < banUnt) {
            drop(DropReason.RateLimited, msg.senderId, "banned")
            return
        }
        if (!allowRate(msg.senderId)) {
            // Sustained abuse → temporary ban (escalating).
            val bucket = rateBuckets[msg.senderId]
            if (bucket != null && bucket.consecutiveDenies > 32) {
                banUntil[msg.senderId] = System.currentTimeMillis() + RATE_BAN_MS
            }
            drop(DropReason.RateLimited, msg.senderId, null)
            return
        }

        // Signature verification.
        val verified = when (msg.type) {
            MsgType.ANNOUNCE -> verifyAnnounce(msg)
            else -> verifyKnownSender(msg)
        }
        if (!verified) {
            drop(DropReason.BadSignature, msg.senderId, msg.msgId)
            return
        }

        // Topology: an empty incoming relay path means the sender wrote
        // straight to us; observe a direct edge. Always feed the path
        // itself so we learn distant edges as well.
        val now = System.currentTimeMillis()
        if (msg.relayPath.isEmpty() || (msg.relayPath.size == 1 && msg.relayPath[0] == msg.senderId)) {
            graph.observeDirect(msg.senderId, now)
        }
        graph.observeRelayPath(msg.relayPath + listOf(nodeId), now)

        // Deliver locally if this concerns us.
        val forUs =
            msg.recipientId == null || msg.recipientId == nodeId || msg.groupId != null
        if (forUs) scope.launch { _appInbox.emit(msg) }

        // Relay (flooding). Decrement TTL, append our id.
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

    /** Manually trust an identity learned out-of-band (e.g. QR pairing). */
    fun trustPeer(p: PeerIdentity) {
        peers[p.nodeId] = p.copy(lastSeenMs = System.currentTimeMillis())
        scope.launch { _peerEvents.emit(p) }
    }

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
        if (prev != null && !prev.edPub.contentEquals(pid.edPub)) {
            scope.launch {
                _identityConflicts.emit(IdentityConflict(prev, pid))
            }
        }
        if (prev == null || !prev.edPub.contentEquals(pid.edPub)) {
            scope.launch { _peerEvents.emit(pid) }
        }
        return true
    }

    private fun verifyKnownSender(msg: MeshMessage): Boolean {
        val peer = peers[msg.senderId] ?: return false
        val ok = msg.verifyWith(peer.edPub)
        if (ok) peers[msg.senderId] = peer.copy(lastSeenMs = System.currentTimeMillis())
        return ok
    }

    private fun alreadySeen(id: String): Boolean = synchronized(seenLock) { seen.containsKey(id) }

    private fun markSeen(id: String) {
        val now = System.currentTimeMillis()
        synchronized(seenLock) { seen[id] = now }
        scope.launch { runCatching { seenStore.record(id, now) } }
    }

    private fun registerNonce(senderId: String, nonce: String): Boolean {
        val window = nonceWindows.getOrPut(senderId) { ArrayDeque() }
        synchronized(window) {
            if (window.contains(nonce)) return false
            window.addLast(nonce)
            while (window.size > NONCE_WINDOW) window.removeFirst()
            return true
        }
    }

    private fun allowRate(senderId: String): Boolean {
        val now = System.currentTimeMillis()
        val bucket = rateBuckets.getOrPut(senderId) { RateBucket() }
        return bucket.allow(now)
    }

    private fun drop(reason: DropReason, senderId: String?, detail: String?) {
        Log.w(tag, "drop $reason from=$senderId detail=$detail")
        scope.launch { _drops.emit(reason) }
    }

    companion object {
        private const val SEEN_CAP = 4096
        private const val NONCE_WINDOW = 64

        /** ±5 minutes is generous; tighten if device clocks are reliable. */
        const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

        /** Sustained-abuse ban duration (mirrors core/security in Python). */
        const val RATE_BAN_MS: Long = 30 * 1000L
    }

    private class RateBucket(
        private val capacity: Int = 64,
        private val refillPerSec: Int = 16,
    ) {
        private var tokens: Double = capacity.toDouble()
        private var lastRefillMs: Long = System.currentTimeMillis()
        @Volatile var consecutiveDenies: Int = 0
            private set

        @Synchronized
        fun allow(now: Long): Boolean {
            val elapsed = (now - lastRefillMs).coerceAtLeast(0L)
            tokens = (tokens + elapsed / 1000.0 * refillPerSec).coerceAtMost(capacity.toDouble())
            lastRefillMs = now
            if (tokens >= 1.0) {
                tokens -= 1.0
                consecutiveDenies = 0
                return true
            }
            consecutiveDenies++
            return false
        }
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

/**
 * Trust-on-first-use mismatch: same node id, different Ed25519 public key.
 * Either the peer reset their identity (legitimate but lossy) or someone
 * is spoofing — UI should warn loudly before letting messages through.
 */
data class IdentityConflict(val previous: PeerIdentity, val current: PeerIdentity)

enum class DropReason {
    Unparseable,
    BadSignature,
    NonceReplay,
    ClockSkew,
    LoopDetected,
    RateLimited,
}

/** Persistent dedup backing for [MeshRouter]. */
interface SeenStore {
    suspend fun recentIds(limit: Int): List<String>
    suspend fun record(msgId: String, ts: Long)
    object NoOp : SeenStore {
        override suspend fun recentIds(limit: Int): List<String> = emptyList()
        override suspend fun record(msgId: String, ts: Long) {}
    }
}
