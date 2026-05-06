package team.hex.meshlink.groups

import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MsgType
import team.hex.meshlink.storage.GroupRow
import team.hex.meshlink.storage.MeshDb
import java.security.SecureRandom
import java.util.UUID

/**
 * Group chats. A group has:
 *   - a randomly-generated 16-char id
 *   - a 256-bit AES key shared by all members (encrypted GROUP_TEXT)
 *   - an opaque CSV of member node ids
 *
 * Group invites are sent as 1:1 ENCRYPTED messages of type GROUP_INVITE
 * (encrypted with the recipient's X25519 ECDH session key, same as text).
 * That payload contains {groupId, name, sharedKeyB64, members}.
 *
 * Group messages are sent broadcast (recipientId = null, groupId set).
 * Every node in the mesh relays them; only members holding the shared
 * key can decrypt.
 *
 * The shared key is symmetric; this is "good enough" for an MVP and
 * matches what most LAN-mesh group chats do (Bridgefy/Briar/Bitchat).
 * Forward-secrecy upgrades (Signal-style ratchets, MLS) are noted in
 * android/TODO.md.
 */
class Groups(
    private val db: MeshDb,
    private val sendEncrypted: suspend (peerId: String, type: Int, payload: ByteArray) -> Unit,
) {
    private val rng = SecureRandom()

    /** Create a group locally and invite [members] (1:1 invites). */
    suspend fun createAndInvite(name: String, members: List<String>): String {
        val groupId = "g-" + UUID.randomUUID().toString().replace("-", "").take(14)
        val sharedKey = ByteArray(32).also(rng::nextBytes)
        db.groupDao().upsert(GroupRow(
            groupId = groupId,
            name = name,
            sharedKey = sharedKey,
            membersCsv = members.joinToString(","),
        ))
        val invite = GroupInvitePayload(
            groupId = groupId,
            name = name,
            sharedKeyB64 = Crypto.b64(sharedKey),
            members = members,
        )
        for (m in members) {
            sendEncrypted(m, MsgType.GROUP_INVITE, invite.toBytes())
        }
        return groupId
    }

    /** Persist a remotely-issued invite. */
    suspend fun acceptInvite(invite: GroupInvitePayload) {
        db.groupDao().upsert(GroupRow(
            groupId = invite.groupId,
            name = invite.name,
            sharedKey = Crypto.unb64(invite.sharedKeyB64),
            membersCsv = invite.members.joinToString(","),
        ))
    }

    suspend fun encryptGroupText(groupId: String, text: String): ByteArray? {
        val row = db.groupDao().byId(groupId) ?: return null
        return Crypto.aesGcmEncrypt(row.sharedKey, text.toByteArray(Charsets.UTF_8))
    }

    suspend fun decryptGroupText(groupId: String, ciphertext: ByteArray): String? {
        val row = db.groupDao().byId(groupId) ?: return null
        return runCatching {
            Crypto.aesGcmDecrypt(row.sharedKey, ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Add new members to an existing group. We rotate the shared key so
     * past ciphertext stays unreadable to the new joiners (post-compromise
     * security on add) and re-issue [GroupInvitePayload]s carrying the
     * fresh key. Existing members are also re-keyed via a 1:1 invite.
     */
    suspend fun addMembers(groupId: String, newMembers: List<String>): Boolean {
        val row = db.groupDao().byId(groupId) ?: return false
        val current = row.membersCsv.split(",").filter { it.isNotEmpty() }.toSet()
        val merged = (current + newMembers).toList()
        val freshKey = ByteArray(32).also(rng::nextBytes)
        db.groupDao().upsert(row.copy(sharedKey = freshKey, membersCsv = merged.joinToString(",")))
        val invite = GroupInvitePayload(
            groupId = groupId,
            name = row.name,
            sharedKeyB64 = Crypto.b64(freshKey),
            members = merged,
        )
        // Send the new shared key to *all* members, not just the new ones,
        // otherwise the existing members can't decrypt new messages.
        for (m in merged) sendEncrypted(m, MsgType.GROUP_INVITE, invite.toBytes())
        return true
    }

    /**
     * Remove [removed] members from the group. Forward secrecy is the
     * goal: rotate the shared key so the removed members' future ciphertext
     * (recovered from the air) is unreadable to them. We do not invite the
     * removed members.
     */
    suspend fun removeMembers(groupId: String, removed: List<String>): Boolean {
        val row = db.groupDao().byId(groupId) ?: return false
        val current = row.membersCsv.split(",").filter { it.isNotEmpty() }.toSet()
        val remaining = (current - removed.toSet()).toList()
        val freshKey = ByteArray(32).also(rng::nextBytes)
        db.groupDao().upsert(row.copy(sharedKey = freshKey, membersCsv = remaining.joinToString(",")))
        val invite = GroupInvitePayload(
            groupId = groupId,
            name = row.name,
            sharedKeyB64 = Crypto.b64(freshKey),
            members = remaining,
        )
        for (m in remaining) sendEncrypted(m, MsgType.GROUP_INVITE, invite.toBytes())
        return true
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
