package team.hex.meshlink.storage

import team.hex.meshlink.mesh.SeenStore

/** Backs [SeenStore] with the Room `seen_msgs` table. */
class RoomSeenStore(private val db: MeshDb) : SeenStore {
    override suspend fun recentIds(limit: Int): List<String> =
        db.seenDao().topIds(limit)

    override suspend fun record(msgId: String, ts: Long) {
        db.seenDao().insert(SeenMsgRow(msgId, ts))
        // Trim opportunistically; cheap because only fires on insert.
        if (msgId.hashCode() and 0x3F == 0) {
            db.seenDao().trim(MeshDb.SEEN_CAP)
        }
    }
}
