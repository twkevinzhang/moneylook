package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A durable request for one extension sync.
 *
 * The extension id is the primary key deliberately: multiple taps, cron wake-ups, and a manual
 * request while that bank is already running all collapse into one bank session. Terminal rows
 * are removed by the orchestrator after it has rendered their final in-memory notification state.
 */
@Entity(
    tableName = "pending_sync_requests",
    primaryKeys = ["extensionId"],
    foreignKeys = [
        ForeignKey(
            entity = InstalledExtension::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingSyncRequest(
    val extensionId: String,
    val trigger: SyncRequestTrigger,
    val status: SyncRequestStatus = SyncRequestStatus.QUEUED,
    val requestedAt: Long,
    val updatedAt: Long = requestedAt,
)

enum class SyncRequestTrigger {
    USER,
    SCHEDULED,
}

enum class SyncRequestStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    PARTIAL,
    ERROR,
    SKIPPED,
}
