package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import tw.kevinzhang.core.data.classification.*

@Entity(tableName = "classification_learning_examples", indices = [Index(value = ["identity", "direction", "accountId", "transactionType"])])
data class ClassificationLearningExample(
    @PrimaryKey val transferId: String,
    val categoryId: String,
    val accountId: String,
    val direction: Int,
    val identity: String,
    val transactionType: String,
    val tokenCount: Int,
    val correctionSequence: Long,
) {
    fun sample() = LearningSample(transferId, categoryId, LocalClassificationFeatures(accountId, direction, identity, transactionType), correctionSequence)
    override fun toString() = "ClassificationLearningExample([REDACTED])"
}

@Entity(tableName = "classification_learning_tokens", primaryKeys = ["token", "transferId"], indices = [Index(value = ["transferId"])])
data class ClassificationLearningToken(val token: String, val transferId: String)

@Entity(tableName = "classification_learning_clock")
data class ClassificationLearningClock(@PrimaryKey val id: Int = 1, val sequence: Long = 0)

@Entity(tableName = "classification_fallback_roles")
data class ClassificationFallbackRole(@PrimaryKey val role: String, val categoryId: String)

@Entity(tableName = "classification_local_evidence")
data class ClassificationLocalEvidence(@PrimaryKey val transferId: String, val source: String, val reason: String)

@Entity(tableName = "classification_pending", indices = [Index(value = ["runId"])])
data class ClassificationPending(@PrimaryKey val transferId: String, val runId: String)
