package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.PendingSyncRequest
import tw.kevinzhang.core.data.model.SyncRequestStatus

@Dao
interface PendingSyncRequestDao {
    /** Returns false when this extension already has a queued or running session. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(request: PendingSyncRequest): Long

    @Query(
        "SELECT * FROM pending_sync_requests " +
            "ORDER BY requestedAt ASC, extensionId ASC",
    )
    fun observeAll(): Flow<List<PendingSyncRequest>>

    @Query(
        "SELECT * FROM pending_sync_requests WHERE status = 'QUEUED' " +
            "ORDER BY requestedAt ASC, extensionId ASC",
    )
    suspend fun getQueued(): List<PendingSyncRequest>

    @Query("SELECT * FROM pending_sync_requests ORDER BY requestedAt ASC, extensionId ASC")
    suspend fun getAll(): List<PendingSyncRequest>

    @Query("SELECT * FROM pending_sync_requests WHERE extensionId = :extensionId")
    suspend fun getByExtensionId(extensionId: String): PendingSyncRequest?

    @Query(
        "UPDATE pending_sync_requests SET status = 'RUNNING', updatedAt = :updatedAt " +
            "WHERE extensionId = :extensionId AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markRunning(extensionId: String, updatedAt: Long): Int

    /** A manual request outranks a queued or just-claimed schedule request. */
    @Query(
        "UPDATE pending_sync_requests SET trigger = 'USER', updatedAt = :updatedAt " +
            "WHERE extensionId = :extensionId AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun promoteQueuedToUser(extensionId: String, updatedAt: Long): Int

    @Query(
        "UPDATE pending_sync_requests SET status = :status, updatedAt = :updatedAt " +
            "WHERE extensionId = :extensionId AND status = 'RUNNING'",
    )
    suspend fun markTerminal(
        extensionId: String,
        status: SyncRequestStatus,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE pending_sync_requests SET status = 'QUEUED', updatedAt = :updatedAt " +
            "WHERE status = 'RUNNING'",
    )
    suspend fun requeueRunning(updatedAt: Long): Int

    @Query("DELETE FROM pending_sync_requests WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)

    @Query("DELETE FROM pending_sync_requests")
    suspend fun deleteAll()

    @Query("DELETE FROM pending_sync_requests WHERE status NOT IN ('QUEUED', 'RUNNING')")
    suspend fun deleteTerminal()
}
