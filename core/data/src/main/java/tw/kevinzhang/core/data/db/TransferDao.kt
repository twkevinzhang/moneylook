package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Transfer

@Dao
interface TransferDao : TransferCursorStore {
    @Query("SELECT * FROM transfers WHERE accountId = :accountId ORDER BY txnDateTime DESC")
    fun observeByAccount(accountId: String): Flow<List<Transfer>>

    @Upsert
    suspend fun upsertAll(transfers: List<Transfer>)

    @Query(
        """
        SELECT a.accountNo AS accountNo,
               a.currency AS currency,
               MAX(t.txnDateTime) AS latestTxnDateTime
        FROM accounts AS a
        INNER JOIN transfers AS t ON t.accountId = a.id
        WHERE a.extensionId = :extensionId
          AND a.accountNo IS NOT NULL
          AND length(trim(a.accountNo)) > 0
          AND length(t.txnDateTime) >= 10
          AND substr(t.txnDateTime, 5, 1) = '-'
          AND substr(t.txnDateTime, 8, 1) = '-'
        GROUP BY a.accountNo, a.currency
        ORDER BY a.accountNo, a.currency
        """,
    )
    override suspend fun latestByExtension(extensionId: String): List<TransferSyncCursor>

    @Query("DELETE FROM transfers WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)
}
