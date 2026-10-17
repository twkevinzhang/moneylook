package tw.kevinzhang.core.data.db

import androidx.room.*
import tw.kevinzhang.core.data.model.*

data class ManualLearningRow(@Embedded val transfer: Transfer, val learningCategoryId: String)

@Dao
abstract class LocalClassificationDao {
    @Query("SELECT * FROM classification_learning_examples WHERE transferId = :id")
    abstract suspend fun example(id: String): ClassificationLearningExample?
    @Upsert abstract suspend fun putExample(value: ClassificationLearningExample)
    @Upsert abstract suspend fun putTokens(values: List<ClassificationLearningToken>)
    @Query("DELETE FROM classification_learning_tokens WHERE transferId = :id")
    abstract suspend fun deleteTokens(id: String)
    @Query("DELETE FROM classification_learning_examples WHERE transferId = :id")
    abstract suspend fun deleteExample(id: String)
    @Query("DELETE FROM classification_learning_examples") abstract suspend fun clearExamples()
    @Query("DELETE FROM classification_learning_tokens") abstract suspend fun clearTokens()
    @Query("SELECT t.*, a.categoryId AS learningCategoryId FROM transfers t INNER JOIN transfer_annotations a ON a.transferId=t.id INNER JOIN categories c ON c.id=a.categoryId WHERE a.manualOverride=1 AND t.id > :after ORDER BY t.id LIMIT :limit")
    abstract suspend fun manualPage(after: String, limit: Int): List<ManualLearningRow>
    @Query("DELETE FROM classification_learning_examples WHERE transferId NOT IN (SELECT t.id FROM transfers t INNER JOIN transfer_annotations a ON a.transferId=t.id INNER JOIN categories c ON c.id=a.categoryId WHERE a.manualOverride=1 AND a.categoryId=classification_learning_examples.categoryId)")
    abstract suspend fun pruneExamples()
    @Query("DELETE FROM classification_learning_tokens WHERE transferId NOT IN (SELECT transferId FROM classification_learning_examples)")
    abstract suspend fun pruneTokens()
    @Query("SELECT * FROM classification_learning_examples WHERE identity=:identity AND direction=:direction AND accountId=:account AND transactionType=:type AND correctionSequence>0 AND categoryId != 'transfer-account' AND categoryId NOT LIKE 'local-fallback-%' ORDER BY correctionSequence DESC LIMIT 1")
    abstract suspend fun exact(identity: String, direction: Int, account: String, type: String): List<ClassificationLearningExample>
    @Query("""SELECT e.* FROM classification_learning_examples e
        INNER JOIN classification_learning_tokens tok ON tok.transferId=e.transferId
        WHERE tok.token IN (:tokens) AND e.direction=:direction
        AND e.categoryId != 'transfer-account' AND e.categoryId NOT LIKE 'local-fallback-%'
        GROUP BY e.transferId
        HAVING COUNT(*) * 1.0 / (:tokenCount + e.tokenCount - COUNT(*)) >= 0.6
        ORDER BY COUNT(*) * 1.0 / (:tokenCount + e.tokenCount - COUNT(*)) DESC,
        e.correctionSequence DESC, e.transferId LIMIT 50""")
    abstract suspend fun similar(tokens: List<String>, tokenCount: Int, direction: Int): List<ClassificationLearningExample>
    @Query("SELECT * FROM classification_fallback_roles") abstract suspend fun roles(): List<ClassificationFallbackRole>
    @Upsert abstract suspend fun putRole(role: ClassificationFallbackRole)
    @Query("SELECT * FROM accounts WHERE kind='DEPOSIT' AND extensionId IN (SELECT extensionId FROM credential_profiles)")
    abstract suspend fun ownedAccounts(): List<Account>
    @Query("SELECT * FROM installed_extensions WHERE id=:id") abstract suspend fun extension(id: String): InstalledExtension?
    @Query("DELETE FROM classification_local_evidence") abstract suspend fun clearEvidence()
    @Query("DELETE FROM classification_pending") abstract suspend fun clearPending()
}
