package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Transfer

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers WHERE accountId = :accountId ORDER BY txnDateTime DESC LIMIT 50")
    fun observeByAccount(accountId: String): Flow<List<Transfer>>

    @Upsert
    suspend fun upsertAll(transfers: List<Transfer>)

    @Query("DELETE FROM transfers WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)
}
