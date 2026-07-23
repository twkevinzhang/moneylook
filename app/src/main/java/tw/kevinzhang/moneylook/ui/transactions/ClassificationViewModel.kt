package tw.kevinzhang.moneylook.ui.transactions

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.DefaultClassificationCatalog
import tw.kevinzhang.core.data.db.TagDao
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransactionDetailDraftStore
import tw.kevinzhang.core.data.db.TransactionDetailDraftSave
import tw.kevinzhang.core.data.db.PendingTag
import tw.kevinzhang.core.data.db.AutoCategoryRuleSave
import tw.kevinzhang.core.data.db.TransferDetail
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.moneylook.sync.AutoCategorizer
import java.util.UUID
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class ClassificationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val transferAnnotationDao: TransferAnnotationDao,
    private val autoCategoryRuleDao: AutoCategoryRuleDao,
    private val transactionDetailDraftStore: TransactionDetailDraftStore,
    private val autoCategorizer: AutoCategorizer,
) : ViewModel() {
    private val isDetailSaving = MutableStateFlow(false)
    private val isApplyingAllRules = MutableStateFlow(false)
    private val _autoRuleApplicationMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch { ensureDefaultCategories() }
    }
    fun transfersForAccount(accountId: String) = transferAnnotationDao.observeByAccount(accountId)
    val categories: StateFlow<List<CategoryOption>> = categoryDao.observeAll().map(::asCategoryOptions)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags: StateFlow<List<TagOption>> = tagDao.observeAll().map(::asTagOptions)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts: StateFlow<List<AccountOption>> = accountDao.observeAll().map { accounts ->
        accounts.map { AccountOption(it.id, it.accountName) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rules: StateFlow<List<AutoRuleDraft>> = autoCategoryRuleDao.observeAll().map { rules ->
        rules.map(AutoCategoryRuleWithTags::toDraft)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val applyingAllRules: StateFlow<Boolean> = isApplyingAllRules
    val autoRuleApplicationMessages: SharedFlow<String> = _autoRuleApplicationMessages.asSharedFlow()

    private val transferId: String = URLDecoder.decode(savedStateHandle.get<String>("transferId").orEmpty(), "UTF-8")
    val detail: StateFlow<TransactionDetailUiState?> = combine(
        transferAnnotationDao.observeDetail(transferId),
        categoryDao.observeAll(),
        tagDao.observeAll(),
        accountDao.observeAll(),
        isDetailSaving,
    ) { transfer, categories, tags, accounts, saving ->
        transfer?.toDetailUi(categories, tags, accounts.associateBy(Account::id), saving)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveDetail(draft: TransactionDetailDraft, onSaved: () -> Unit) {
        if (isDetailSaving.value) return
        viewModelScope.launch {
            isDetailSaving.value = true
            try {
                val item = transferAnnotationDao.observeDetail(transferId).first() ?: return@launch
                val pendingTagNames = draft.newTagNames.filter { draftTagId(it) in draft.tagIds }.toSet()
                val existingTagIds = draft.tagIds.filterNot(::isDraftTagId).toSet()
                val pendingTagIds = draft.tagIds.filter(::isDraftTagId)
                    .map { draftTagName(it) }.toSet()
                val ruleSave = draft.matchingRule?.normalizedOrNull()?.let { (rule, ruleTagIds) ->
                    AutoCategoryRuleSave(
                        rule = rule,
                        tagIds = ruleTagIds.filterNot(::isDraftTagId).toSet(),
                        pendingTagNames = ruleTagIds.filter(::isDraftTagId).map { draftTagName(it) }.toSet(),
                    )
                }
                transactionDetailDraftStore.save(
                    TransactionDetailDraftSave(
                        annotation = TransferAnnotation(
                            transferId = item.transfer.id,
                            extensionId = item.transfer.extensionId,
                            categoryId = draft.categoryId,
                            note = draft.note,
                            categoryAssignment = AssignmentSource.MANUAL,
                        ),
                        tagIds = existingTagIds,
                        pendingTags = (pendingTagNames + pendingTagIds).map(::PendingTag),
                        autoCategoryRule = ruleSave,
                    ),
                )
                if (draft.resumeAutomatic) autoCategorizer.resumeAutomaticCategorization(transferId)
                if (draft.matchingRule?.applyExisting == true) autoCategorizer.applyToExistingTransactions()
                onSaved()
            } finally {
                isDetailSaving.value = false
            }
        }
    }

    fun saveCategory(id: String?, name: String, color: Long) = saveNamedItem(
        name = name,
        duplicate = { candidate -> categories.value.any { it.id != id && it.name.equals(candidate, ignoreCase = true) } },
    ) { trimmed ->
        val existing = categories.value.firstOrNull { it.id == id }
        categoryDao.upsert(
            Category(
                id = id ?: UUID.randomUUID().toString(),
                name = trimmed,
                color = colorString(color),
                emoji = existing?.emoji ?: Category.DEFAULT_EMOJI,
                kind = existing?.kind ?: CategoryKind.EXPENSE,
            ),
        )
    }

    fun deleteCategory(id: String) = viewModelScope.launch { categoryDao.deleteById(id) }

    fun saveTag(id: String?, name: String, color: Long) = saveNamedItem(
        name = name,
        duplicate = { candidate -> tags.value.any { it.id != id && it.name.equals(candidate, ignoreCase = true) } },
    ) { trimmed ->
        tagDao.upsert(Tag(id ?: UUID.randomUUID().toString(), trimmed, colorString(color)))
    }

    fun deleteTag(id: String) = viewModelScope.launch { tagDao.deleteById(id) }

    fun saveRule(draft: AutoRuleDraft) {
        val normalized = draft.normalizedOrNull() ?: return
        viewModelScope.launch {
            autoCategoryRuleDao.upsertWithTags(normalized.first, normalized.second)
            if (draft.applyExisting) autoCategorizer.applyToExistingTransactions()
        }
    }

    fun deleteRule(id: String) = viewModelScope.launch { autoCategoryRuleDao.deleteById(id) }

    fun applyAllRulesToExistingTransactions() {
        if (isApplyingAllRules.value) return
        viewModelScope.launch {
            isApplyingAllRules.value = true
            try {
                val result = autoCategorizer.applyToExistingTransactions()
                _autoRuleApplicationMessages.emit(
                    "套用完成：處理 ${result.processedTransferCount} 筆，符合 ${result.matchedTransferCount} 筆，保留手動調整 ${result.preservedManualOverrideCount} 筆。",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _autoRuleApplicationMessages.emit("套用規則失敗，請稍後再試。")
            } finally {
                isApplyingAllRules.value = false
            }
        }
    }

    private fun saveNamedItem(
        name: String,
        duplicate: (String) -> Boolean,
        save: suspend (String) -> Unit,
    ) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || duplicate(trimmed)) return
        viewModelScope.launch { save(trimmed) }
    }

    /** A preference marker means deliberately deleting every category never resurrects the catalog. */
    private suspend fun ensureDefaultCategories() {
        val preferences = appContext.getSharedPreferences("classification", Context.MODE_PRIVATE)
        if (preferences.getBoolean(DEFAULT_CATEGORIES_SEEDED, false)) return
        if (categoryDao.observeAll().first().isNotEmpty()) {
            preferences.edit().putBoolean(DEFAULT_CATEGORIES_SEEDED, true).apply()
            return
        }
        for (category in DefaultClassificationCatalog.categories) categoryDao.upsert(category)
        for (rule in DefaultClassificationCatalog.publicAutoCategoryRules) {
            autoCategoryRuleDao.insertIfAbsent(rule)
        }
        preferences.edit().putBoolean(DEFAULT_CATEGORIES_SEEDED, true).apply()
    }

    private companion object {
        const val DEFAULT_CATEGORIES_SEEDED = "default_categories_seeded_v1"
    }

}

private fun TransferDetail.toDetailUi(
    categories: List<Category>,
    tags: List<Tag>,
    accounts: Map<String, Account>,
    isSaving: Boolean,
): TransactionDetailUiState {
    val account = accounts[transfer.accountId]
    return TransactionDetailUiState(
        title = category?.name ?: transfer.type.orEmpty(),
        amountText = tw.kevinzhang.moneylook.ui.home.signedTransferAmount(
            transfer.amount,
            account?.currency.orEmpty(),
        ),
        amount = transfer.amount,
        accountName = account?.accountName ?: "已移除帳戶",
        transactionDate = transfer.txnDateTime.take(10),
        postingDate = null,
        description = transfer.description,
        bankMemo = transfer.memo,
        selectedCategoryId = annotation?.categoryId,
        selectedTagIds = this.tags.mapTo(mutableSetOf()) { it.id },
        userNote = annotation?.note.orEmpty(),
        categories = asCategoryOptions(categories),
        tags = asTagOptions(tags),
        isManualOverride = annotation?.manualOverride == true,
        isSaving = isSaving,
    )
}

private fun asCategoryOptions(items: List<Category>) = items.map {
    CategoryOption(it.id, it.name, it.color.parseColor(), it.emoji, it.kind)
}
private fun asTagOptions(items: List<Tag>) = items.map { TagOption(it.id, it.name, it.color.parseColor()) }
private fun AutoCategoryRuleWithTags.toDraft() = rule.toDraft(tags.mapTo(mutableSetOf()) { it.id })
private fun tw.kevinzhang.core.data.model.AutoCategoryRule.toDraft(tagIds: Set<String>) = AutoRuleDraft(
    id = id,
    name = name,
    descriptionContains = descriptionContains.orEmpty(),
    descriptionMatchMode = descriptionMatchMode,
    direction = when (direction) {
        AutoCategoryRuleDirection.ANY -> null
        AutoCategoryRuleDirection.INCOME -> TransactionDirection.INCOME
        AutoCategoryRuleDirection.EXPENSE -> TransactionDirection.EXPENSE
    },
    minAbsoluteAmount = minAbsoluteAmount?.toString().orEmpty(),
    maxAbsoluteAmount = maxAbsoluteAmount?.toString().orEmpty(),
    accountId = accountId,
    categoryId = categoryId,
    tagIds = tagIds,
    enabled = enabled,
    priority = priority,
    isDefault = isDefault,
)

private fun AutoRuleDraft.normalizedOrNull(): Pair<tw.kevinzhang.core.data.model.AutoCategoryRule, Set<String>>? {
    if (!isValidForSave()) return null
    val min = minAbsoluteAmount.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: minAbsoluteAmount.takeIf(String::isNotBlank)?.let { return null }
    val max = maxAbsoluteAmount.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: maxAbsoluteAmount.takeIf(String::isNotBlank)?.let { return null }
    if (min != null && max != null && min > max) return null
    return tw.kevinzhang.core.data.model.AutoCategoryRule(
        id = id.ifBlank { UUID.randomUUID().toString() }, name = name.trim(), descriptionContains = descriptionContains.trim().takeIf(String::isNotEmpty),
        direction = when (direction) { null -> AutoCategoryRuleDirection.ANY; TransactionDirection.INCOME -> AutoCategoryRuleDirection.INCOME; TransactionDirection.EXPENSE -> AutoCategoryRuleDirection.EXPENSE },
        minAbsoluteAmount = min, maxAbsoluteAmount = max, accountId = accountId, categoryId = categoryId, enabled = enabled, priority = priority,
        descriptionMatchMode = descriptionMatchMode,
        isDefault = isDefault,
    ) to tagIds
}

private fun colorString(value: Long) = "#%06X".format(value and 0xFFFFFF)
private fun String.parseColor(): Long = removePrefix("#").toLongOrNull(16)?.let { 0xFF000000L or it } ?: defaultCategoryColors.first()
private fun isDraftTagId(id: String): Boolean = id.startsWith("draft:")
private fun draftTagName(id: String): String = id.removePrefix("draft:")
