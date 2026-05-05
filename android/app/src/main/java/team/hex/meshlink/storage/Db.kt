package team.hex.meshlink.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_messages")
data class ChatMessageRow(
    @PrimaryKey val msgId: String,
    @ColumnInfo(name = "peer_id") val peerId: String,    // remote node id (recipient if outgoing, sender if incoming)
    @ColumnInfo(name = "outgoing") val outgoing: Boolean,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "ts") val ts: Long,
)

@Entity(tableName = "peers")
data class PeerRow(
    @PrimaryKey val nodeId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "ed_pub") val edPub: ByteArray,
    @ColumnInfo(name = "x_pub") val xPub: ByteArray,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is PeerRow && other.nodeId == nodeId
    override fun hashCode(): Int = nodeId.hashCode()
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ChatMessageRow)

    @Query("SELECT * FROM chat_messages WHERE peer_id = :peerId ORDER BY ts ASC")
    fun streamForPeer(peerId: String): Flow<List<ChatMessageRow>>
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PeerRow)

    @Query("SELECT * FROM peers ORDER BY last_seen_ms DESC")
    fun streamAll(): Flow<List<PeerRow>>

    @Query("SELECT * FROM peers WHERE nodeId = :id LIMIT 1")
    suspend fun byId(id: String): PeerRow?
}

@Database(entities = [ChatMessageRow::class, PeerRow::class], version = 1, exportSchema = false)
abstract class MeshDb : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun peerDao(): PeerDao

    companion object {
        @Volatile private var instance: MeshDb? = null
        fun get(ctx: Context): MeshDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                ctx.applicationContext,
                MeshDb::class.java,
                "meshlink.db"
            ).build().also { instance = it }
        }
    }
}
