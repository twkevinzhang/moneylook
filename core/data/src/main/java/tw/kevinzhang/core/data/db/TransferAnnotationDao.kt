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
import tw.kevinzhang.core.data.model.TransferTagCrossRef

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
        upsert(annotation)
        deleteAllTags(annotation.transferId)
        if (tagIds.isNotEmpty()) {
            upsertTagCrossRefs(tagIds.map { tagId -> TransferTagCrossRef(annotation.transferId, tagId) })
        }
    }

    /** Replaces only user-picked tags; automatic tags remain available for a later rule re-run. */
    @Transaction
    open suspend fun replaceManualTags(transferId: String, tagIds: Set<String>) {
        deleteTagsBySource(transferId, "MANUAL")
        if (tagIds.isNotEmpty()) {
            upsertTagCrossRefs(tagIds.map { tagId -> TransferTagCrossRef(transferId, tagId) })
        }
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
