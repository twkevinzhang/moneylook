package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A deliberately small, append-only diagnostic record.  It must never contain bank payloads,
 * credentials, account identifiers, balances, or raw exception messages.
 */
@Entity(tableName = "sync_diagnostics", indices = [Index(value = ["extensionId", "createdAt"])])
data class SyncDiagnostic(
    @PrimaryKey val id: String,
    val extensionId: String,
    val createdAt: Long,
    /** Stable app/runtime category, for example SCRIPT_ERROR or PARTIAL_KIND. */
    val category: String,
    /** Allow-listed error code only; null where the category is sufficient. */
    val code: String? = null,
    /** First script frame, restricted to a line/column pair. */
    val scriptFrame: String? = null,
)
