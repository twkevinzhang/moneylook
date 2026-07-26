package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.CreditCardInstrument

data class CardInstrumentCount(val accountId: String, val count: Int)

/** UI projection. PAN ciphertext, IV, and fingerprints stay in the DAO; source lineage is shown. */
data class CreditCardInstrumentMetadata(
    val id: String,
    val displayName: String?,
    val maskedPan: String?,
    val lastFour: String?,
    val network: String?,
    val productType: String?,
    val holderRole: String?,
    val holderName: String?,
    val status: String?,
    val expiryMonth: Int?,
    val expiryYear: Int?,
    val creditLimit: Double?,
    val availableCredit: Double?,
    val canRevealPan: Boolean,
    val sourceRecordJson: String? = null,
    val sourceFieldsJson: String? = null,
    val sourceFactsJson: String? = null,
    val parserVersion: String? = null,
)

@Dao
interface CreditCardInstrumentDao {
    @Query(
        """
        SELECT id, displayName, maskedPan, lastFour, network, productType, holderRole,
               holderName, status, expiryMonth, expiryYear, creditLimit, availableCredit,
               sourceRecordJson, sourceFieldsJson, sourceFactsJson, parserVersion,
               CASE
                   WHEN panCiphertext IS NOT NULL AND panIv IS NOT NULL THEN 1
                   ELSE 0
               END AS canRevealPan
        FROM credit_card_instruments
        WHERE accountId = :accountId
        ORDER BY displayName, id
        """,
    )
    fun observeByAccount(accountId: String): Flow<List<CreditCardInstrumentMetadata>>

    @Query(
        """
        SELECT id, displayName, maskedPan, lastFour, network, productType, holderRole,
               holderName, status, expiryMonth, expiryYear, creditLimit, availableCredit,
               sourceRecordJson, sourceFieldsJson, sourceFactsJson, parserVersion,
               CASE
                   WHEN panCiphertext IS NOT NULL AND panIv IS NOT NULL THEN 1
                   ELSE 0
               END AS canRevealPan
        FROM credit_card_instruments
        WHERE id IN (:ids)
        """,
    )
    fun observeByIds(ids: List<String>): Flow<List<CreditCardInstrumentMetadata>>

    @Query("SELECT accountId, COUNT(*) AS count FROM credit_card_instruments GROUP BY accountId")
    fun observeCountsByAccount(): Flow<List<CardInstrumentCount>>

    @Query("SELECT * FROM credit_card_instruments WHERE id = :id")
    suspend fun getById(id: String): CreditCardInstrument?

    @Upsert
    suspend fun upsertAll(instruments: List<CreditCardInstrument>)
}
