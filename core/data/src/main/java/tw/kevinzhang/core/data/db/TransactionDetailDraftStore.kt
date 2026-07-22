package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.TransferAnnotation
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A not-yet-persisted tag entered while editing a transaction detail. */
data class PendingTag(
    val name: String,
    val color: String = DEFAULT_COLOR,
) {
    init {
        require(name == name.trim() && name.isNotEmpty()) { "pending tag name must be non-blank and trimmed" }
        require(color.isNotBlank()) { "pending tag color must not be blank" }
    }

    companion object {
        const val DEFAULT_COLOR = "#607D8B"
    }
}

/** An optional automatic rule saved together with the transaction detail that created it. */
data class AutoCategoryRuleSave(
    val rule: AutoCategoryRule,
    val tagIds: Set<String> = emptySet(),
    /** Names resolve against [TransactionDetailDraftSave.pendingTags], or create a default-colour tag. */
    val pendingTagNames: Set<String> = emptySet(),
)

/**
 * One explicit Save action from the transaction-detail editor.
 *
 * All tag names are deliberately data, rather than immediately-written UI side effects. This
 * lets cancellation discard every edit and makes the final save atomic.
 */
data class TransactionDetailDraftSave(
    val annotation: TransferAnnotation,
    val tagIds: Set<String> = emptySet(),
    val pendingTags: List<PendingTag> = emptyList(),
    val autoCategoryRule: AutoCategoryRuleSave? = null,
)

data class TransactionDetailDraftSaveResult(
    val annotationTagIds: Set<String>,
    val ruleTagIds: Set<String>?,
    val createdTags: List<Tag>,
)

/**
 * Transaction boundary for a detail editor's final Save action.
 *
 * UI state may freely add/remove pending tags and a pending rule. Nothing reaches Room until
 * [save] executes, and a failed rule/tag write rolls back the annotation as well.
 */
@Singleton
class TransactionDetailDraftStore @Inject constructor(
    private val database: MoneylookDatabase,
    private val tagDao: TagDao,
    private val transferAnnotationDao: TransferAnnotationDao,
    private val autoCategoryRuleDao: AutoCategoryRuleDao,
) {
    suspend fun save(draft: TransactionDetailDraftSave): TransactionDetailDraftSaveResult =
        database.withTransaction {
            require(draft.annotation.categoryAssignment == AssignmentSource.MANUAL) {
                "detail save requires a manual annotation"
            }
            require(draft.annotation.manualOverride) { "detail save requires manualOverride" }

            val pendingTagsByName = linkedMapOf<String, PendingTag>()
            draft.pendingTags.forEach { pendingTag ->
                pendingTagsByName.putIfAbsent(pendingTag.name.caseInsensitiveKey(), pendingTag)
            }
            draft.autoCategoryRule?.pendingTagNames?.forEach { name ->
                val trimmed = name.trim()
                require(trimmed.isNotEmpty()) { "rule pending tag name must be non-blank" }
                pendingTagsByName.putIfAbsent(
                    trimmed.caseInsensitiveKey(),
                    PendingTag(name = trimmed),
                )
            }

            val createdTags = mutableListOf<Tag>()
            val tagsByName = pendingTagsByName.values.associate { pendingTag ->
                pendingTag.name.caseInsensitiveKey() to resolveOrCreateTag(pendingTag, createdTags)
            }
            val annotationTagIds = draft.tagIds + tagsByName.values.mapTo(linkedSetOf()) { it.id }
            transferAnnotationDao.saveManualAnnotation(draft.annotation, annotationTagIds)

            val ruleTagIds = draft.autoCategoryRule?.let { ruleSave ->
                val pendingRuleTagIds = ruleSave.pendingTagNames.mapTo(linkedSetOf()) { name ->
                    tagsByName.getValue(name.trim().caseInsensitiveKey()).id
                }
                (ruleSave.tagIds + pendingRuleTagIds).also { tagIds ->
                    autoCategoryRuleDao.upsertWithTags(ruleSave.rule, tagIds)
                }
            }

            TransactionDetailDraftSaveResult(
                annotationTagIds = annotationTagIds,
                ruleTagIds = ruleTagIds,
                createdTags = createdTags,
            )
        }

    private suspend fun resolveOrCreateTag(pendingTag: PendingTag, createdTags: MutableList<Tag>): Tag {
        tagDao.getByName(pendingTag.name)?.let { return it }
        return Tag(
            id = UUID.randomUUID().toString(),
            name = pendingTag.name,
            color = pendingTag.color,
        ).also { tag ->
            tagDao.upsert(tag)
            createdTags += tag
        }
    }
}

private fun String.caseInsensitiveKey(): String = lowercase(Locale.ROOT)
