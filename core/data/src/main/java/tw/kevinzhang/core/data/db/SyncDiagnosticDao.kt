package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.SyncDiagnostic

@Dao
interface SyncDiagnosticDao {
    @Insert
    suspend fun insert(diagnostic: SyncDiagnostic)

    @Query("SELECT * FROM sync_diagnostics WHERE extensionId = :extensionId ORDER BY createdAt DESC")
    fun observeByExtension(extensionId: String): Flow<List<SyncDiagnostic>>
}
