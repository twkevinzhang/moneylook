package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.TransferAnnotationEvent
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.TransferTagCrossRef

data class AutomaticClassificationDecision(
    val transferId: String,
    val extensionId: String,
    val categoryId: String?,
    val tagIds: Set<String>,
    val runId: String?,
    val trigger: ClassificationTrigger,
    val outcome: ClassificationOutcome,
    val ruleId: String?,
    val ruleSetId: String?,
    val ruleContentSha256: String?,
    val ruleSetContentSha256: String?,
    val matchScore: Int?,
    val classifierVersion: String?,
)

enum class AutomaticClassificationWriteResult { APPLIED, RECORDED_ONLY, PRESERVED_MANUAL }

/** A transaction together with user-owned metadata. Bank [Transfer.memo] is intentionally separate. */
data class TransferDetail(
    @Embedded val transfer: Transfer,
    @Embedded(prefix = "annotation_") val annotation: TransferAnnotation?,
    @Embedded(prefix = "category_") val category: Category?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TransferTagCrossRef::class,
            parentColumn = "transferId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
)

/** Lightweight ledger row with the same safe, user-owned fields as [TransferDetail]. */
data class TransferListItem(
    @Embedded val transfer: Transfer,
    @Embedded(prefix = "annotation_") val annotation: TransferAnnotation?,
    @Embedded(prefix = "category_") val category: Category?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TransferTagCrossRef::class,
            parentColumn = "transferId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
)

/**
 * Cross-account ledger row. It intentionally projects only an account's display metadata;
 * account numbers and extension credentials are never selected into this UI-facing model.
 */
data class GlobalTransferListItem(
    @Embedded val transfer: Transfer,
    @Embedded(prefix = "annotation_") val annotation: TransferAnnotation?,
    @Embedded(prefix = "category_") val category: Category?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TransferTagCrossRef::class,
            parentColumn = "transferId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
    val accountName: String,
    val extensionName: String,
    val currency: String,
    val accountKind: AssetKind,
    /** Safe card display metadata; a complete PAN and encrypted fields are never projected. */
    val cardDisplayName: String? = null,
    val cardMaskedPan: String? = null,
    val cardLastFour: String? = null,
)

@Dao
abstract class TransferAnnotationDao {
    @Transaction
    @Query(TRANSFER_DETAIL_SELECT + " WHERE t.id = :transferId")
    abstract fun observeDetail(transferId: String): Flow<TransferDetail?>

    @Transaction
    @Query(TRANSFER_DETAIL_SELECT + " WHERE t.accountId = :accountId ORDER BY t.txnDateTime DESC")
    abstract fun observeByAccount(accountId: String): Flow<List<TransferListItem>>

    /** Bounded, stable pages used by CSV export without loading the complete ledger. */
    @Transaction
    @Query(TRANSFER_DETAIL_SELECT + " ORDER BY t.id LIMIT :limit OFFSET :offset")
    abstract suspend fun getAllDetailsPage(limit: Int, offset: Int): List<TransferDetail>

    @Query("SELECT * FROM transfer_tag_cross_refs WHERE transferId IN (:transferIds)")
    abstract suspend fun getTagCrossRefsForTransferIds(
        transferIds: List<String>,
    ): List<TransferTagCrossRef>

    /**
     * Observes every account's transactions in [startInclusive, endExclusive). ISO dates and
     * datetimes sort lexicographically, so callers can pass date prefixes such as 2026-07-01.
     */
    @Transaction
    @Query(
        GLOBAL_TRANSFER_LIST_SELECT +
            " WHERE t.txnDateTime >= :startInclusive AND t.txnDateTime < :endExclusive" +
            " ORDER BY t.txnDateTime DESC, t.id DESC",
    )
    abstract fun observeGlobalBetween(
        startInclusive: String,
        endExclusive: String,
    ): Flow<List<GlobalTransferListItem>>

    @Upsert
    abstract suspend fun upsert(annotation: TransferAnnotation)

    /** Batch lookup prevents an N+1 query when enabled rules are applied to a sync result. */
    @Query("SELECT * FROM transfer_annotations WHERE transferId IN (:transferIds)")
    abstract suspend fun getByTransferIds(transferIds: List<String>): List<TransferAnnotation>

    @Query("SELECT * FROM transfer_tag_cross_refs WHERE transferId = :transferId")
    abstract suspend fun getTagCrossRefs(transferId: String): List<TransferTagCrossRef>

    @Upsert
    protected abstract suspend fun upsertTagCrossRefs(crossRefs: List<TransferTagCrossRef>)

    /**
     * Restores the exact portable annotation/tag snapshot without generating a manual-edit event.
     * The enclosing file import owns its MANUAL_FILE ingestion audit.
     */
    @Transaction
    open suspend fun replaceImportedMetadata(
        transferId: String,
        annotation: TransferAnnotation?,
        tagCrossRefs: List<TransferTagCrossRef>,
    ) {
        require(annotation == null || annotation.transferId == transferId) {
            "annotation transfer id mismatch"
        }
        require(tagCrossRefs.all { it.transferId == transferId }) {
            "tag transfer id mismatch"
        }
        deleteAllTags(transferId)
        if (annotation == null) {
            deleteAnnotation(transferId)
        } else {
            upsert(annotation)
        }
        if (tagCrossRefs.isNotEmpty()) {
            upsertTagCrossRefs(tagCrossRefs)
        }
    }

    /** Keeps an existing MANUAL assignment when an automatic rule proposes the same tag. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTagCrossRefsIgnoringConflict(
        crossRefs: List<TransferTagCrossRef>,
    )

    /**
     * The transaction-detail save action is intentionally all-manual: it can clear a category,
     * remove automatic tags, and retain a blank note as an explicit user decision.
     */
    @Transaction
    open suspend fun saveManualAnnotation(annotation: TransferAnnotation, tagIds: Set<String>) {
        require(annotation.categoryAssignment == tw.kevinzhang.core.data.model.AssignmentSource.MANUAL) {
            "manual save requires a MANUAL category assignment"
        }
        require(annotation.manualOverride) { "manual save requires manualOverride" }
        val previous = getByTransferIds(listOf(annotation.transferId)).singleOrNull()
        val previousManualTagIds = getManualTagCrossRefs(annotation.transferId).mapTo(mutableSetOf()) { it.tagId }
        upsert(annotation)
        deleteAllTags(annotation.transferId)
        if (tagIds.isNotEmpty()) {
            upsertTagCrossRefs(tagIds.map { tagId -> TransferTagCrossRef(annotation.transferId, tagId) })
        }
        insertAnnotationEvent(
            TransferAnnotationEvent(
                id = java.util.UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                runId = null,
                transferId = annotation.transferId,
                extensionId = annotation.extensionId,
                trigger = ClassificationTrigger.MANUAL_EDIT,
                outcome = if (annotation.categoryId == null) {
                    ClassificationOutcome.MANUAL_CLEARED
                } else {
                    ClassificationOutcome.MANUAL_ASSIGNED
                },
                previousCategoryId = previous?.categoryId,
                newCategoryId = annotation.categoryId,
                ruleId = null,
                ruleSetId = null,
                ruleContentSha256 = null,
                ruleSetContentSha256 = null,
                matchScore = null,
                classifierVersion = null,
                tagAddedCount = (tagIds - previousManualTagIds).size,
                tagRemovedCount = (previousManualTagIds - tagIds).size,
            ),
        )
    }

    /** Replaces only user-picked tags; automatic tags remain available for a later rule re-run. */
    @Transaction
    open suspend fun replaceManualTags(transferId: String, tagIds: Set<String>) {
        val previousTagIds = getManualTagCrossRefs(transferId).mapTo(mutableSetOf()) { it.tagId }
        val annotation = getByTransferIds(listOf(transferId)).singleOrNull()
        val extensionId = annotation?.extensionId ?: getTransferExtensionId(transferId)
            ?: return
        deleteTagsBySource(transferId, "MANUAL")
        if (tagIds.isNotEmpty()) {
            upsertTagCrossRefs(tagIds.map { tagId -> TransferTagCrossRef(transferId, tagId) })
        }
        insertAnnotationEvent(
            TransferAnnotationEvent(
                id = java.util.UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                runId = null,
                transferId = transferId,
                extensionId = extensionId,
                trigger = ClassificationTrigger.MANUAL_EDIT,
                outcome = ClassificationOutcome.MANUAL_TAG_EDIT,
                previousCategoryId = annotation?.categoryId,
                newCategoryId = annotation?.categoryId,
                ruleId = null,
                ruleSetId = null,
                ruleContentSha256 = null,
                ruleSetContentSha256 = null,
                matchScore = null,
                classifierVersion = null,
                tagAddedCount = (tagIds - previousTagIds).size,
                tagRemovedCount = (previousTagIds - tagIds).size,
            ),
        )
    }

    /**
     * Rechecks the live manual override and commits annotation, automatic tags, and audit as one
     * decision. A manual edit that races a classifier therefore always wins.
     */
    @Transaction
    open suspend fun applyAutomaticDecision(
        decision: AutomaticClassificationDecision,
    ): AutomaticClassificationWriteResult {
        val current = getByTransferIds(listOf(decision.transferId)).singleOrNull()
        if (current?.manualOverride == true) {
            insertDecisionEvent(
                decision.copy(
                    outcome = ClassificationOutcome.PRESERVED_MANUAL,
                    categoryId = current.categoryId,
                    tagIds = emptySet(),
                ),
                previousCategoryId = current.categoryId,
            )
            return AutomaticClassificationWriteResult.PRESERVED_MANUAL
        }
        if (decision.outcome == ClassificationOutcome.AUTO_APPLIED ||
            decision.outcome == ClassificationOutcome.NO_MATCH && current != null
        ) {
            upsert(
                TransferAnnotation(
                    transferId = decision.transferId,
                    extensionId = decision.extensionId,
                    categoryId = decision.categoryId,
                    note = current?.note.orEmpty(),
                    categoryAssignment = tw.kevinzhang.core.data.model.AssignmentSource.AUTO,
                    manualOverride = false,
                    autoRuleId = decision.ruleId,
                    autoRuleSetId = decision.ruleSetId,
                    autoMatchScore = decision.matchScore,
                    classifierVersion = decision.classifierVersion,
                ),
            )
            replaceAutoTags(decision.transferId, decision.tagIds)
        }
        insertDecisionEvent(decision, current?.categoryId)
        return if (decision.outcome == ClassificationOutcome.AUTO_APPLIED) {
            AutomaticClassificationWriteResult.APPLIED
        } else {
            AutomaticClassificationWriteResult.RECORDED_ONLY
        }
    }

    /** Clears a manual override under the same serialization boundary used by classifier writes. */
    @Transaction
    open suspend fun resumeAutomaticClassification(transferId: String) {
        val existing = getByTransferIds(listOf(transferId)).singleOrNull() ?: return
        upsert(
            existing.copy(
                categoryId = null,
                categoryAssignment = tw.kevinzhang.core.data.model.AssignmentSource.AUTO,
                manualOverride = false,
            ),
        )
        insertAnnotationEvent(
            TransferAnnotationEvent(
                id = java.util.UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                runId = null,
                transferId = existing.transferId,
                extensionId = existing.extensionId,
                trigger = ClassificationTrigger.RESUME,
                outcome = ClassificationOutcome.RESUMED_AUTOMATIC,
                previousCategoryId = existing.categoryId,
                newCategoryId = null,
                ruleId = null,
                ruleSetId = null,
                ruleContentSha256 = null,
                ruleSetContentSha256 = null,
                matchScore = null,
                classifierVersion = null,
            ),
        )
    }

    private suspend fun insertDecisionEvent(
        decision: AutomaticClassificationDecision,
        previousCategoryId: String?,
    ) {
        insertAnnotationEvent(
            TransferAnnotationEvent(
                id = java.util.UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                runId = decision.runId,
                transferId = decision.transferId,
                extensionId = decision.extensionId,
                trigger = decision.trigger,
                outcome = decision.outcome,
                previousCategoryId = previousCategoryId,
                newCategoryId = decision.categoryId,
                ruleId = decision.ruleId,
                ruleSetId = decision.ruleSetId,
                ruleContentSha256 = decision.ruleContentSha256,
                ruleSetContentSha256 = decision.ruleSetContentSha256,
                matchScore = decision.matchScore,
                classifierVersion = decision.classifierVersion,
            ),
        )
    }

    /** Replaces automatic tags without disturbing explicit user tag assignments. */
    @Transaction
    open suspend fun replaceAutoTags(transferId: String, tagIds: Set<String>) {
        deleteTagsBySource(transferId, "AUTO")
        if (tagIds.isNotEmpty()) {
            insertTagCrossRefsIgnoringConflict(
                tagIds.map { tagId ->
                    TransferTagCrossRef(
                        transferId = transferId,
                        tagId = tagId,
                        source = tw.kevinzhang.core.data.model.AssignmentSource.AUTO,
                    )
                },
            )
        }
    }

    @Query("DELETE FROM transfer_tag_cross_refs WHERE transferId = :transferId AND source = :source")
    protected abstract suspend fun deleteTagsBySource(transferId: String, source: String)

    @Query("DELETE FROM transfer_tag_cross_refs WHERE transferId = :transferId")
    protected abstract suspend fun deleteAllTags(transferId: String)

    @Query("SELECT * FROM transfer_tag_cross_refs WHERE transferId = :transferId AND source = 'MANUAL'")
    protected abstract suspend fun getManualTagCrossRefs(transferId: String): List<TransferTagCrossRef>

    @Query("SELECT extensionId FROM transfers WHERE id = :transferId")
    protected abstract suspend fun getTransferExtensionId(transferId: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAnnotationEvent(event: TransferAnnotationEvent)

    /**
     * Removes annotations and their tags once an extension is genuinely removed, not during a
     * sync range replacement. The annotation table is the extension ownership index for tags.
     */
    @Transaction
    open suspend fun clearForExtension(extensionId: String) {
        deleteTagsForExtension(extensionId)
        deleteAnnotationsForExtension(extensionId)
    }

    @Query(
        """
        DELETE FROM transfer_tag_cross_refs
        WHERE transferId IN (
            SELECT transferId FROM transfer_annotations WHERE extensionId = :extensionId
        )
        """,
    )
    protected abstract suspend fun deleteTagsForExtension(extensionId: String)

    @Query("DELETE FROM transfer_annotations WHERE extensionId = :extensionId")
    protected abstract suspend fun deleteAnnotationsForExtension(extensionId: String)

    @Query("DELETE FROM transfer_annotations WHERE transferId = :transferId")
    protected abstract suspend fun deleteAnnotation(transferId: String)

    private companion object {
        const val TRANSFER_DETAIL_SELECT = """
            SELECT
                t.*,
                a.transferId AS annotation_transferId,
                a.extensionId AS annotation_extensionId,
                a.categoryId AS annotation_categoryId,
                a.note AS annotation_note,
                a.categoryAssignment AS annotation_categoryAssignment,
                a.manualOverride AS annotation_manualOverride,
                a.autoRuleId AS annotation_autoRuleId,
                a.autoRuleSetId AS annotation_autoRuleSetId,
                a.autoMatchScore AS annotation_autoMatchScore,
                a.classifierVersion AS annotation_classifierVersion,
                c.id AS category_id,
                c.name AS category_name,
                c.color AS category_color,
                c.emoji AS category_emoji,
                c.kind AS category_kind
            FROM transfers AS t
            LEFT JOIN transfer_annotations AS a ON a.transferId = t.id
            LEFT JOIN categories AS c ON c.id = a.categoryId
        """

        const val GLOBAL_TRANSFER_LIST_SELECT = """
            SELECT
                t.*,
                a.transferId AS annotation_transferId,
                a.extensionId AS annotation_extensionId,
                a.categoryId AS annotation_categoryId,
                a.note AS annotation_note,
                a.categoryAssignment AS annotation_categoryAssignment,
                a.manualOverride AS annotation_manualOverride,
                a.autoRuleId AS annotation_autoRuleId,
                a.autoRuleSetId AS annotation_autoRuleSetId,
                a.autoMatchScore AS annotation_autoMatchScore,
                a.classifierVersion AS annotation_classifierVersion,
                c.id AS category_id,
                c.name AS category_name,
                c.color AS category_color,
                c.emoji AS category_emoji,
                c.kind AS category_kind,
                account.accountName AS accountName,
                account.extensionName AS extensionName,
                account.currency AS currency,
                account.kind AS accountKind,
                card.displayName AS cardDisplayName,
                card.maskedPan AS cardMaskedPan,
                card.lastFour AS cardLastFour
            FROM transfers AS t
            INNER JOIN accounts AS account ON account.id = t.accountId
            LEFT JOIN credit_card_instruments AS card ON card.id = t.cardInstrumentId
            LEFT JOIN transfer_annotations AS a ON a.transferId = t.id
            LEFT JOIN categories AS c ON c.id = a.categoryId
        """
    }
}
