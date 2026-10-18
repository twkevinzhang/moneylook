package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "classification_jobs")
data class ClassificationJob(
    @PrimaryKey val id: Int = 1,
    val generation: Long = 0,
    val preparedGeneration: Long = -1,
    val status: String = "IDLE",
    val fullReclassification: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val message: String? = null,
    val attemptId: String? = null,
    val startedAttemptId: String? = null,
) {
    val active: Boolean get() = status in setOf("QUEUED", "RUNNING", "RESTARTING", "STOPPING")
}

@Entity(tableName = "classification_job_items")
data class ClassificationJobItem(@PrimaryKey val transferId: String, val generation: Long, val completed: Boolean = false)

data class ClassificationJobPreview(val pendingTransferCount: Int, val fullReclassification: Boolean,
    val firstUpgradePending: Boolean, val active: Boolean)
