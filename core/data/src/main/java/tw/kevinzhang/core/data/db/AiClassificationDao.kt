package tw.kevinzhang.core.data.db

import androidx.room.*
import tw.kevinzhang.core.data.model.*

@Dao
interface AiClassificationDao {
    @Query("SELECT * FROM ai_classification_state WHERE id=1") suspend fun state(): AiClassificationState?
    @Upsert suspend fun putState(state: AiClassificationState)
    @Query("SELECT * FROM ai_classification_work WHERE transferId=:id AND completed=0") suspend fun work(id: String): AiClassificationWork?
    @Query("SELECT transferId FROM ai_classification_work WHERE completed=0 ORDER BY transferId") suspend fun pending(): List<String>
    @Query("UPDATE ai_classification_work SET completed=1 WHERE transferId=:id") suspend fun complete(id: String)
    @Query("SELECT t.id FROM transfers t INNER JOIN accounts ac ON ac.id=t.accountId LEFT JOIN transfer_annotations a ON a.transferId=t.id WHERE a.categoryId IS NULL AND COALESCE(a.manualOverride,0)=0 ORDER BY t.id")
    suspend fun uncategorized(): List<String>
}
