package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.TransferAnnotationEvent
import java.util.UUID

/** Restores the bundled classification catalog without touching financial or provenance records. */
interface ClassificationCatalogResetStore {
    suspend fun resetToDefaults()
}

/** Room-backed, all-or-nothing implementation of [ClassificationCatalogResetStore]. */
class RoomClassificationCatalogResetStore(
    private val database: MoneylookDatabase,
    private val annotationDao: TransferAnnotationDao,
    private val provenanceDao: IngestionProvenanceDao,
) : ClassificationCatalogResetStore {
    override suspend fun resetToDefaults() {
        database.withTransaction {
            val annotations = annotationDao.getAll()
            val tagCountsByTransferId = annotationDao.getAllTagCrossRefs()
                .groupingBy { it.transferId }
                .eachCount()

            annotationDao.resetClassificationState()
            DefaultClassificationSeeder.resetToDefaults(database.openHelper.writableDatabase)

            if (annotations.isNotEmpty()) {
                val occurredAt = System.currentTimeMillis()
                annotations.forEach { annotation ->
                    provenanceDao.insertAnnotationEvent(
                        TransferAnnotationEvent(
                            id = UUID.randomUUID().toString(),
                            occurredAt = occurredAt,
                            runId = null,
                            transferId = annotation.transferId,
                            extensionId = annotation.extensionId,
                            trigger = ClassificationTrigger.CATALOG_RESET,
                            outcome = ClassificationOutcome.CATALOG_RESET,
                            previousCategoryId = annotation.categoryId,
                            newCategoryId = null,
                            ruleId = null,
                            ruleSetId = null,
                            ruleContentSha256 = null,
                            ruleSetContentSha256 = null,
                            matchScore = null,
                            classifierVersion = null,
                            tagRemovedCount = tagCountsByTransferId[annotation.transferId] ?: 0,
                        ),
                    )
                }
            }
        }
    }
}
