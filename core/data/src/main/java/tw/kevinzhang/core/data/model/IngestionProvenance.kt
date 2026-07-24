package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The way an import was initiated; this is app-private audit metadata. */
enum class IngestionTrigger {
    USER_SYNC,
    SCHEDULED_SYNC,
    MANUAL_FILE,
    BACKFILL,
    UNKNOWN,
}

/** Terminal state recorded for an import. Failed runs may have no transfer events. */
enum class IngestionStatus { SUCCESS, PARTIAL, FAILED }

/** Snapshot persistence and classification are separate phases with independent outcomes. */
enum class IngestionClassificationStatus { PENDING, COMPLETE, FAILED }

/** Whether this observation introduced or changed the current transfer snapshot. */
enum class TransferObservation { INSERTED, UPDATED, UNCHANGED }

/** Entry point that caused a category decision to be evaluated. */
enum class ClassificationTrigger {
    INGESTION,
    BULK_REAPPLY,
    RULE_SAVE,
    RESUME,
    INTERNAL_BACKFILL,
    MANUAL_EDIT,
}

/** Result of evaluating categorization, including deliberately preserved/manual outcomes. */
enum class ClassificationOutcome {
    AUTO_APPLIED,
    ABSTAINED,
    NO_MATCH,
    PRESERVED_MANUAL,
    MANUAL_ASSIGNED,
    MANUAL_CLEARED,
    MANUAL_TAG_EDIT,
    RESUMED_AUTOMATIC,
}

/**
 * One immutable import attempt. It intentionally stores no credentials, PANs, merchant names,
 * descriptions, or account numbers. [artifactSha256] identifies the installed code artifact.
 */
@Entity(tableName = "ingestion_runs", indices = [Index(value = ["extensionId", "startedAt"]), Index(value = ["status"])])
data class IngestionRun(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val completedAt: Long,
    val extensionId: String,
    val extensionVersion: Int,
    val artifactRevision: String?,
    val artifactSha256: String?,
    val trigger: IngestionTrigger,
    val status: IngestionStatus,
    val classificationStatus: IngestionClassificationStatus = IngestionClassificationStatus.PENDING,
    val classificationCompletedAt: Long? = null,
    val accountCount: Int,
    val transferCount: Int,
    /** Per-install keyed fingerprint of the extension/source context, never raw source data. */
    val sourceFingerprint: String,
    val fingerprintKeyVersion: Int,
)

/**
 * Append-only proof that a local transfer was supplied by an import. There is deliberately no
 * foreign key: history replacement and rule deletion must never erase audit evidence.
 */
@Entity(tableName = "transfer_ingestion_events", indices = [Index(value = ["runId"]), Index(value = ["transferId"]), Index(value = ["extensionId", "occurredAt"])])
data class TransferIngestionEvent(
    @PrimaryKey val id: String,
    val runId: String,
    val occurredAt: Long,
    val transferId: String,
    val extensionId: String,
    val observation: TransferObservation,
    /** Per-install keyed fingerprint of this source record's stable identity. */
    val sourceFingerprint: String,
    /** Per-install keyed fingerprint of the normalized payload observed in this run. */
    val payloadFingerprint: String,
    val fingerprintKeyVersion: Int,
    val hasDescription: Boolean,
    val hasMemo: Boolean,
    val hasType: Boolean,
    val hasMerchantName: Boolean,
    val hasMerchantCategoryCode: Boolean,
    val hasCounterpartyName: Boolean,
    val hasPurpose: Boolean,
)

/** Immutable category decision evidence; it has no FK to a transfer or rule by design. */
@Entity(tableName = "transfer_annotation_events", indices = [Index(value = ["transferId", "occurredAt"]), Index(value = ["runId"]), Index(value = ["outcome"])])
data class TransferAnnotationEvent(
    @PrimaryKey val id: String,
    val occurredAt: Long,
    val runId: String?,
    val transferId: String,
    val extensionId: String,
    val trigger: ClassificationTrigger,
    val outcome: ClassificationOutcome,
    val previousCategoryId: String?,
    val newCategoryId: String?,
    val ruleId: String?,
    val ruleSetId: String?,
    val ruleContentSha256: String?,
    val ruleSetContentSha256: String?,
    val matchScore: Int?,
    val classifierVersion: String?,
    /** Aggregate-only manual tag delta; tag identifiers and names are never recorded. */
    val tagAddedCount: Int = 0,
    val tagRemovedCount: Int = 0,
)
