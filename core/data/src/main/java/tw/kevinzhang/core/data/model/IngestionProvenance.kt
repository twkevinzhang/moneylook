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

/**
 * Immutable, byte-for-byte evidence captured from an extension-declared authenticated response.
 *
 * [bodyGzip] contains the complete response bytes after deterministic gzip compression. The
 * uncompressed SHA-256 and size make the archive independently verifiable. Request bodies are
 * deliberately excluded because this ledger is for source responses, not credential replay.
 */
@Entity(
    tableName = "source_documents",
    indices = [
        Index(value = ["runId", "capturedAt"]),
        Index(value = ["extensionId", "capturedAt"]),
        Index(value = ["bodySha256"]),
    ],
)
data class SourceDocument(
    @PrimaryKey val id: String,
    val runId: String,
    val extensionId: String,
    val capturedAt: Long,
    val stage: String,
    val transport: String,
    val method: String,
    val url: String,
    /** Null for WebView main-document captures because WebView does not expose the HTTP status. */
    val statusCode: Int?,
    /** Response headers encoded as JSON; `{}` when the transport cannot expose them. */
    val responseHeadersJson: String,
    val mediaKind: String?,
    val bodyEncoding: String,
    /** exact_bytes, decoded_text, or serialized_dom. */
    val representation: String,
    val bodyByteCount: Long,
    val bodySha256: String,
    val bodyGzip: ByteArray,
)

/**
 * Append-only field-level lineage. Values are intentionally retained as JSON so an old import can
 * still be explained after the current transfer snapshot changes.
 */
@Entity(
    tableName = "transfer_field_observations",
    indices = [
        Index(value = ["transferId", "observedAt"]),
        Index(value = ["runId"]),
        Index(value = ["sourceDocumentId"]),
        Index(value = ["fieldName"]),
        Index(value = ["assetType", "assetId", "observedAt"]),
    ],
)
data class TransferFieldObservation(
    @PrimaryKey val id: String,
    val runId: String,
    val transferId: String?,
    val extensionId: String,
    val observedAt: Long,
    val fieldName: String,
    val valueJson: String,
    val sourceDocumentId: String?,
    val sourcePath: String?,
    val sourceRecordJson: String?,
    /** Complete SourceField/SourceFact descriptor, including rawKey/confidence/locator. */
    val sourceFieldJson: String?,
    val parserVersion: String?,
    /** TRANSFER, ACCOUNT, or CARD. */
    val assetType: String = "TRANSFER",
    val assetId: String = transferId.orEmpty(),
)

/** Every enabled rule considered for a transfer, including non-matches and the selected winner. */
@Entity(
    tableName = "classification_rule_evaluations",
    indices = [
        Index(value = ["transferId", "evaluatedAt"]),
        Index(value = ["runId"]),
        Index(value = ["ruleId"]),
    ],
)
data class ClassificationRuleEvaluation(
    @PrimaryKey val id: String,
    val runId: String?,
    val transferId: String,
    val extensionId: String,
    val evaluatedAt: Long,
    val trigger: ClassificationTrigger,
    val ruleId: String,
    val ruleSetId: String?,
    val ruleContentSha256: String?,
    val scopeMatched: Boolean,
    val conditionsMatched: Boolean,
    val categoryCompatible: Boolean,
    val matched: Boolean,
    val selected: Boolean,
    val score: Int?,
    val reasonCode: String,
    val classifierVersion: String,
)

/** One immutable clause evaluation belonging to [ClassificationRuleEvaluation]. */
@Entity(
    tableName = "classification_condition_evaluations",
    indices = [
        Index(value = ["ruleEvaluationId", "position"]),
        Index(value = ["transferId", "evaluatedAt"]),
    ],
)
data class ClassificationConditionEvaluation(
    @PrimaryKey val id: String,
    val ruleEvaluationId: String,
    val transferId: String,
    val evaluatedAt: Long,
    val position: Int,
    val conditionGroup: String,
    val field: String,
    val matchMode: String,
    val pattern: String,
    /** Candidate source values as JSON, retained even when the clause did not match. */
    val candidateValuesJson: String,
    val matched: Boolean,
)
