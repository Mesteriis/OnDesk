package app.ondesk

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "queue")
data class QueueEntry(
    @PrimaryKey val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val path: String,
    val serverID: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val offset: Long = 0,
    val state: String = "queued",
    val error: String? = null,
    @ColumnInfo(defaultValue = "NULL") val link: String? = null,
    @ColumnInfo(defaultValue = "NULL") val shareBatchID: String? = null
)
@Dao interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY createdAt, id") fun observe(): Flow<List<QueueEntry>>
    @Query("SELECT * FROM queue ORDER BY createdAt, id") suspend fun all(): List<QueueEntry>
    @Query("SELECT * FROM queue WHERE state IN ('queued', 'sending') ORDER BY createdAt, id LIMIT 1") suspend fun next(): QueueEntry?
    @Insert suspend fun insert(entry: QueueEntry)
    @Query("UPDATE queue SET offset = :offset, state = :state, error = :error WHERE id = :id") suspend fun progress(id: String, offset: Long, state: String, error: String?)
    @Query("UPDATE queue SET state = :state, error = :error WHERE id = :id") suspend fun failure(id: String, state: String, error: String?)
    @Query("DELETE FROM queue WHERE id = :id AND state = 'delivered' AND shareBatchID IS NULL") suspend fun deleteUnsharedDelivered(id: String)
    @Query("UPDATE queue SET shareBatchID = NULL WHERE shareBatchID = :batchID") suspend fun releaseShareBatch(batchID: String)
    @Query("DELETE FROM queue WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE queue SET state = 'queued', error = NULL WHERE state = 'blocked'") suspend fun retryBlocked()
    @Query("UPDATE queue SET serverID = :serverID, state = 'queued', error = NULL WHERE state != 'delivered'") suspend fun retarget(serverID: String)
    @Query("UPDATE queue SET serverID = :serverID WHERE serverID IS NULL") suspend fun assignUnpaired(serverID: String)
}
@Database(entities = [QueueEntry::class], version = 3, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)])
abstract class QueueDatabase : RoomDatabase() { abstract fun queue(): QueueDao }
