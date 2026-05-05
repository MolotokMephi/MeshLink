package team.hex.meshlink.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_messages")
data class ChatMessageRow(
    @PrimaryKey val msgId: String,
    /** For 1:1 the remote node id; for group messages the group id. */
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String, // "peer" | "group"
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "outgoing") val outgoing: Boolean,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "ts") val ts: Long,
    /** "pending" | "sent" | "delivered" | "read" | "failed" — outgoing only. */
    @ColumnInfo(name = "delivery") val delivery: String = "delivered",
)

@Entity(tableName = "peers")
data class PeerRow(
    @PrimaryKey val nodeId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "ed_pub") val edPub: ByteArray,
    @ColumnInfo(name = "x_pub") val xPub: ByteArray,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    @ColumnInfo(name = "trusted") val trusted: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is PeerRow && other.nodeId == nodeId
    override fun hashCode(): Int = nodeId.hashCode()
}

/**
 * Persistent dedup cache for received mesh msg_ids. Bounded by eviction
 * (oldest rows pruned when the table exceeds [MeshDb.SEEN_CAP]).
 */
@Entity(tableName = "seen_msgs")
data class SeenMsgRow(
    @PrimaryKey val msgId: String,
    @ColumnInfo(name = "ts") val ts: Long,
)

/**
 * Outbound queue for messages that need an ack (1:1 text, file offers, …).
 * The router pumps this on a timer, retrying with exponential backoff
 * until [acked] flips true or [attempts] exceeds the configured cap.
 */
@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey val msgId: String,
    @ColumnInfo(name = "recipient_id") val recipientId: String,
    @ColumnInfo(name = "type") val type: Int,
    @ColumnInfo(name = "frame") val frame: ByteArray,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "acked") val acked: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A group chat. The shared symmetric key is AES-256 used for all
 * GROUP_TEXT messages addressed to this group. Members are peer ids.
 */
@Entity(tableName = "groups")
data class GroupRow(
    @PrimaryKey val groupId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "shared_key") val sharedKey: ByteArray,
    @ColumnInfo(name = "members_csv") val membersCsv: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/** A file the user has either offered to send or accepted to receive. */
@Entity(tableName = "files")
data class FileRow(
    @PrimaryKey val transferId: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "outgoing") val outgoing: Boolean,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "sha256") val sha256: ByteArray,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0,
    @ColumnInfo(name = "state") val state: String = "pending", // pending|active|done|failed
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ChatMessageRow)

    @Query("SELECT * FROM chat_messages WHERE scope_id = :scopeId AND scope_kind = :kind ORDER BY ts ASC")
    fun streamForScope(scopeId: String, kind: String): Flow<List<ChatMessageRow>>

    @Query("UPDATE chat_messages SET delivery = :state WHERE msgId = :msgId")
    suspend fun setDelivery(msgId: String, state: String)

    @Query("DELETE FROM chat_messages WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PeerRow)

    @Query("SELECT * FROM peers ORDER BY last_seen_ms DESC")
    fun streamAll(): Flow<List<PeerRow>>

    @Query("SELECT * FROM peers WHERE nodeId = :id LIMIT 1")
    suspend fun byId(id: String): PeerRow?

    @Query("UPDATE peers SET trusted = :trusted WHERE nodeId = :id")
    suspend fun setTrusted(id: String, trusted: Boolean)
}

@Dao
interface SeenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SeenMsgRow)

    @Query("SELECT EXISTS(SELECT 1 FROM seen_msgs WHERE msgId = :id)")
    suspend fun contains(id: String): Boolean

    @Query("SELECT msgId FROM seen_msgs ORDER BY ts DESC LIMIT :limit")
    suspend fun topIds(limit: Int): List<String>

    @Query("DELETE FROM seen_msgs WHERE msgId NOT IN (SELECT msgId FROM seen_msgs ORDER BY ts DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: OutboxRow)

    @Query("SELECT * FROM outbox WHERE acked = 0 AND next_attempt_at <= :now ORDER BY created_at ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<OutboxRow>

    @Query("UPDATE outbox SET acked = 1 WHERE msgId = :id")
    suspend fun markAcked(id: String)

    @Query("UPDATE outbox SET attempts = :attempts, next_attempt_at = :nextAt WHERE msgId = :id")
    suspend fun reschedule(id: String, attempts: Int, nextAt: Long)

    @Query("DELETE FROM outbox WHERE acked = 1 AND created_at < :before")
    suspend fun trimAcked(before: Long)

    @Query("DELETE FROM outbox WHERE attempts >= :maxAttempts")
    suspend fun dropExhausted(maxAttempts: Int)
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: GroupRow)

    @Query("SELECT * FROM groups ORDER BY created_at DESC")
    fun streamAll(): Flow<List<GroupRow>>

    @Query("SELECT * FROM groups WHERE groupId = :id LIMIT 1")
    suspend fun byId(id: String): GroupRow?
}

@Dao
interface FileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FileRow)

    @Query("SELECT * FROM files WHERE transferId = :id LIMIT 1")
    suspend fun byId(id: String): FileRow?

    @Query("SELECT * FROM files WHERE peer_id = :peerId ORDER BY created_at DESC")
    fun streamForPeer(peerId: String): Flow<List<FileRow>>

    @Query("UPDATE files SET bytes_done = :done, state = :state WHERE transferId = :id")
    suspend fun progress(id: String, done: Long, state: String)
}

@Database(
    entities = [
        ChatMessageRow::class,
        PeerRow::class,
        SeenMsgRow::class,
        OutboxRow::class,
        GroupRow::class,
        FileRow::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MeshDb : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun peerDao(): PeerDao
    abstract fun seenDao(): SeenDao
    abstract fun outboxDao(): OutboxDao
    abstract fun groupDao(): GroupDao
    abstract fun fileDao(): FileDao

    companion object {
        const val SEEN_CAP = 4096

        @Volatile private var instance: MeshDb? = null

        fun get(ctx: Context): MeshDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext,
                MeshDb::class.java,
                "meshlink.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        /**
         * v1 had `chat_messages(msgId, peer_id, outgoing, body, ts)` and
         * `peers(nodeId, name, ed_pub, x_pub, last_seen_ms)`. v2 adds:
         *   - chat_messages: scope_kind, sender_id, delivery; renames peer_id -> scope_id
         *   - peers: trusted
         *   - seen_msgs, outbox, groups, files
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE chat_messages_new (
                        msgId TEXT NOT NULL PRIMARY KEY,
                        scope_id TEXT NOT NULL,
                        scope_kind TEXT NOT NULL,
                        sender_id TEXT NOT NULL,
                        outgoing INTEGER NOT NULL,
                        body TEXT NOT NULL,
                        ts INTEGER NOT NULL,
                        delivery TEXT NOT NULL DEFAULT 'delivered'
                    )""".trimIndent()
                )
                db.execSQL(
                    """INSERT INTO chat_messages_new (msgId, scope_id, scope_kind, sender_id, outgoing, body, ts, delivery)
                       SELECT msgId, peer_id, 'peer',
                              CASE WHEN outgoing = 1 THEN '' ELSE peer_id END,
                              outgoing, body, ts, 'delivered'
                       FROM chat_messages""".trimIndent()
                )
                db.execSQL("DROP TABLE chat_messages")
                db.execSQL("ALTER TABLE chat_messages_new RENAME TO chat_messages")

                db.execSQL("ALTER TABLE peers ADD COLUMN trusted INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """CREATE TABLE seen_msgs (
                        msgId TEXT NOT NULL PRIMARY KEY,
                        ts INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE outbox (
                        msgId TEXT NOT NULL PRIMARY KEY,
                        recipient_id TEXT NOT NULL,
                        type INTEGER NOT NULL,
                        frame BLOB NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at INTEGER NOT NULL DEFAULT 0,
                        acked INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE `groups` (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        shared_key BLOB NOT NULL,
                        members_csv TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE files (
                        transferId TEXT NOT NULL PRIMARY KEY,
                        peer_id TEXT NOT NULL,
                        outgoing INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        sha256 BLOB NOT NULL,
                        local_path TEXT NOT NULL,
                        bytes_done INTEGER NOT NULL DEFAULT 0,
                        state TEXT NOT NULL DEFAULT 'pending',
                        created_at INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
        }
    }
}
