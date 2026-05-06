package team.hex.meshlink.groups

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.crypto.SenderKeys
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MsgType
import team.hex.meshlink.mesh.SenderKeyDistribution
import team.hex.meshlink.storage.GroupRow
import team.hex.meshlink.storage.GroupSenderRow
import team.hex.meshlink.storage.MeshDb
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Group chats with **Signal-style sender keys** for forward secrecy.
 *
 * Each member maintains their own per-(group, self) chain key. Outgoing
 * messages derive a fresh per-message AES key from the chain key + a
 * monotonically-increasing counter, then the chain advances. Receivers
 * mirror the chain for every other member of the group: each (group,
 * sender) pair has its own [GroupSenderRow] holding the next-expected
 * chain key + counter.
 *
 * Distribution: when the group is created (or membership changes), every
 * existing member ships a [SenderKeyDistribution] (1:1 encrypted) to
 * everyone else with their own current chain key seed. New joiners send
 * theirs back. Forward secrecy is preserved: a removed member's last
 * cached chain key cannot decrypt anything sent after the next ratchet
 * step on every remaining sender.
 *
 * Wire-level GROUP_TEXT payload becomes [SenderKeyMessage] (sender id +
 * counter + ciphertext) instead of a bare AES blob, so the receiver
 * picks the right chain.
 */
class Groups(
    private val db: MeshDb,
    private val selfNodeId: String,
    private val sendEncrypted: suspend (peerId: String, type: Int, payload: ByteArray) -> Unit,
) {
    /**
     * Per-(group, sender) lock so concurrent encrypt/decrypt of the same
     * sender chain can't race into key reuse. We key on (groupId, senderId)
     * because send and receive are completely independent chains held by
     * different peers; they only share the same lock when self happens to
     * be the sender on both sides (impossible by construction).
     */
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(groupId: String, senderId: String): Mutex =
        locks.getOrPut("${'$'}groupId|${'$'}senderId") { Mutex() }

    /** Create a group locally and send sender keys to every invitee. */
    suspend fun createAndInvite(name: String, members: List<String>): String {
        val groupId = "g-" + UUID.randomUUID().toString().replace("-", "").take(14)
        val sharedKey = SenderKeys.freshChainKey()           // legacy key, unused for new chats
        db.groupDao().upsert(GroupRow(
            groupId = groupId,
            name = name,
            sharedKey = sharedKey,
            membersCsv = members.joinToString(","),
        ))
        seedSelfChain(groupId)
        // Invite invitees with the legacy GROUP_INVITE that carries the
        // group metadata, then ship our sender chain key 1:1.
        val invite = GroupInvitePayload(
            groupId = groupId,
            name = name,
            sharedKeyB64 = Crypto.b64(sharedKey),
            members = members,
        )
        for (m in members) {
            sendEncrypted(m, MsgType.GROUP_INVITE, invite.toBytes())
            shipOurChainTo(m, groupId)
        }
        return groupId
    }

    /** Persist a remotely-issued invite + reciprocate with our chain key. */
    suspend fun acceptInvite(invite: GroupInvitePayload, fromPeer: String) {
        db.groupDao().upsert(GroupRow(
            groupId = invite.groupId,
            name = invite.name,
            sharedKey = Crypto.unb64(invite.sharedKeyB64),
            membersCsv = invite.members.joinToString(","),
        ))
        // Generate our own sender chain (idempotent — keeps existing on
        // re-invite) and ship to everyone, including the inviter, so the
        // group converges on a full chain matrix.
        seedSelfChain(invite.groupId)
        val recipients = invite.members + fromPeer
        for (m in recipients.distinct()) {
            if (m == selfNodeId) continue
            shipOurChainTo(m, invite.groupId)
        }
    }

    /** Persist a sender chain key received from [senderId] (1:1 GROUP_SENDER_KEY). */
    suspend fun acceptSenderKey(groupId: String, senderId: String, dist: SenderKeyDistribution) {
        if (db.groupDao().byId(groupId) == null) return
        db.groupSenderDao().upsert(GroupSenderRow(
            groupId = groupId,
            senderId = senderId,
            chainKey = dist.chainKey(),
            counter = dist.counter,
        ))
    }

    /**
     * Encrypt [text] under our current sender chain. Advances the chain
     * by one step on the way out. Returns the wire payload bytes (a
     * [SenderKeyMessage] JSON), or null if our chain isn't seeded yet.
     */
    suspend fun encryptGroupText(groupId: String, text: String): ByteArray? {
        return lockFor(groupId, selfNodeId).withLock {
            val state = db.groupSenderDao().get(groupId, selfNodeId) ?: run {
                seedSelfChain(groupId)
                db.groupSenderDao().get(groupId, selfNodeId) ?: return@withLock null
            }
            val key = SenderKeys.messageKey(state.chainKey, state.counter)
            val ciphertext = Crypto.aesGcmEncrypt(key, text.toByteArray(Charsets.UTF_8))
            val payload = SenderKeyMessage(
                senderId = selfNodeId,
                counter = state.counter,
                ciphertextB64 = Crypto.b64(ciphertext),
            ).toBytes()
            db.groupSenderDao().upsert(state.copy(
                chainKey = SenderKeys.advance(state.chainKey),
                counter = state.counter + 1,
            ))
            payload
        }
    }

    /**
     * Decrypt a GROUP_TEXT payload. Falls back to the legacy [GroupRow.sharedKey]
     * when the payload is bare AES-GCM (so chats from older builds still work
     * during a deployment).
     */
    suspend fun decryptGroupText(groupId: String, payload: ByteArray): String? {
        // Try sender-key path first.
        val msg = SenderKeyMessage.fromBytes(payload)
        if (msg != null) return decryptSenderKey(groupId, msg)
        // Legacy fallback (single shared key, no forward secrecy).
        val legacy = db.groupDao().byId(groupId) ?: return null
        return runCatching {
            Crypto.aesGcmDecrypt(legacy.sharedKey, payload).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun decryptSenderKey(groupId: String, msg: SenderKeyMessage): String? {
        return lockFor(groupId, msg.senderId).withLock {
            val state = db.groupSenderDao().get(groupId, msg.senderId)
                ?: return@withLock null
            if (msg.counter < state.counter) return@withLock null   // anti-replay
            // Fast-forward the chain to the message counter (skipping holes).
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
            db.groupSenderDao().upsert(state.copy(
                chainKey = SenderKeys.advance(chain),
                counter = counter + 1,
            ))
            plain.toString(Charsets.UTF_8)
        }
    }

    /**
     * Add new members to an existing group. Re-roll our sender chain so
     * past traffic is unrecoverable to anyone who later compromises us,
     * and ship the fresh seed to *every* member — old and new — so they
     * can decrypt our subsequent messages.
     */
    suspend fun addMembers(groupId: String, newMembers: List<String>): Boolean {
        val row = db.groupDao().byId(groupId) ?: return false
        val current = row.membersCsv.split(",").filter { it.isNotEmpty() }.toSet()
        val merged = (current + newMembers).toList()
        db.groupDao().upsert(row.copy(membersCsv = merged.joinToString(",")))
        rotateSelfChain(groupId)
        // Send the legacy invite (carries group metadata + member list) to
        // brand-new members so they bootstrap the group locally.
        val legacyInvite = GroupInvitePayload(
            groupId = groupId, name = row.name,
            sharedKeyB64 = Crypto.b64(row.sharedKey),
            members = merged,
        )
        for (m in newMembers) {
            sendEncrypted(m, MsgType.GROUP_INVITE, legacyInvite.toBytes())
        }
        for (m in merged) {
            if (m == selfNodeId) continue
            shipOurChainTo(m, groupId)
        }
        return true
    }

    /** Remove [removed] members and rotate our sender chain. */
    suspend fun removeMembers(groupId: String, removed: List<String>): Boolean {
        val row = db.groupDao().byId(groupId) ?: return false
        val current = row.membersCsv.split(",").filter { it.isNotEmpty() }.toSet()
        val remaining = (current - removed.toSet()).toList()
        db.groupDao().upsert(row.copy(membersCsv = remaining.joinToString(",")))
        rotateSelfChain(groupId)
        for (m in remaining) {
            if (m == selfNodeId) continue
            shipOurChainTo(m, groupId)
        }
        return true
    }

    private suspend fun seedSelfChain(groupId: String) {
        if (db.groupSenderDao().get(groupId, selfNodeId) != null) return
        db.groupSenderDao().upsert(GroupSenderRow(
            groupId = groupId,
            senderId = selfNodeId,
            chainKey = SenderKeys.freshChainKey(),
            counter = 0L,
        ))
    }

    private suspend fun rotateSelfChain(groupId: String) {
        db.groupSenderDao().upsert(GroupSenderRow(
            groupId = groupId,
            senderId = selfNodeId,
            chainKey = SenderKeys.freshChainKey(),
            counter = 0L,
        ))
    }

    private suspend fun shipOurChainTo(peerId: String, groupId: String) {
        val state = db.groupSenderDao().get(groupId, selfNodeId) ?: return
        val dist = SenderKeyDistribution(
            groupId = groupId,
            chainKeyB64 = Crypto.b64(state.chainKey),
            counter = state.counter,
        )
        sendEncrypted(peerId, MsgType.GROUP_SENDER_KEY, dist.encode())
    }
}

@Serializable
data class GroupInvitePayload(
    val groupId: String,
    val name: String,
    val sharedKeyB64: String,
    val members: List<String>,
) {
    fun toBytes(): ByteArray =
        MeshMessage.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(b: ByteArray): GroupInvitePayload? = try {
            MeshMessage.json.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        } catch (_: Throwable) { null }
    }
}

/** Wire format for forward-secret group ciphertext (carried inside GROUP_TEXT). */
@Serializable
data class SenderKeyMessage(
    val senderId: String,
    val counter: Long,
    val ciphertextB64: String,
) {
    fun toBytes(): ByteArray =
        MeshMessage.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(b: ByteArray): SenderKeyMessage? = try {
            MeshMessage.json.decodeFromString(serializer(), b.toString(Charsets.UTF_8))
        } catch (_: Throwable) { null }
    }
}
