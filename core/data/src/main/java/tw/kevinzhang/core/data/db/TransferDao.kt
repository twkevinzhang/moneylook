package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Transfer

/** Redaction-safe facts required to compare transactions across the user's local accounts. */
data class TransferClassificationCandidate(
    @Embedded val transfer: Transfer,
    val currency: String,
    val accountKind: AssetKind,
)

@Dao
interface TransferDao : TransferCursorStore {
    @Query("SELECT * FROM transfers WHERE accountId = :accountId ORDER BY txnDateTime DESC")
    fun observeByAccount(accountId: String): Flow<List<Transfer>>

    @Upsert
    suspend fun upsertAll(transfers: List<Transfer>)

    /** Used by the rule application service after a sync without observing a Flow per transfer. */
    @Query("SELECT * FROM transfers WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Transfer>

    /** Explicit user action can apply enabled rules to the full local transaction history. */
    @Query("SELECT * FROM transfers")
    suspend fun getAll(): List<Transfer>

    /**
     * Internal-transfer detection needs the account currency but deliberately excludes account
     * numbers, source keys, balances, and every other account field.
     */
    @Query(
        """
        SELECT t.*, a.currency AS currency, a.kind AS accountKind
        FROM transfers AS t
        INNER JOIN accounts AS a ON a.id = t.accountId
        """,
    )
    suspend fun getAllClassificationCandidates(): List<TransferClassificationCandidate>

    @Query(
        """
        SELECT a.sourceAccountKey AS sourceAccountKey,
               a.kind AS kind,
               a.currency AS currency,
               MAX(t.txnDateTime) AS latestTxnDateTime
        FROM accounts AS a
        INNER JOIN transfers AS t ON t.accountId = a.id
        WHERE a.extensionId = :extensionId
          AND a.sourceAccountKey IS NOT NULL
          AND length(trim(a.sourceAccountKey)) > 0
          AND length(t.txnDateTime) >= 10
          AND substr(t.txnDateTime, 5, 1) = '-'
          AND substr(t.txnDateTime, 8, 1) = '-'
        GROUP BY a.sourceAccountKey, a.kind, a.currency
        ORDER BY a.sourceAccountKey, a.kind, a.currency
        """,
    )
    override suspend fun latestByExtension(extensionId: String): List<TransferSyncCursor>

    @Query("DELETE FROM transfers WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)
}
