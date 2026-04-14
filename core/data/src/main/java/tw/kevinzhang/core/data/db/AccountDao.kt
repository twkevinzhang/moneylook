package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Account

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY extensionName, accountName")
    fun observeAll(): Flow<List<Account>>

    @Upsert
    suspend fun upsertAll(accounts: List<Account>)

    @Query("DELETE FROM accounts WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)
}
