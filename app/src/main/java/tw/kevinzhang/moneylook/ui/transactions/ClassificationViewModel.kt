package tw.kevinzhang.moneylook.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.TagDao
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransferDetail
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.moneylook.sync.AutoCategorizer
import java.util.UUID
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class ClassificationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val transferAnnotationDao: TransferAnnotationDao,
    private val autoCategoryRuleDao: AutoCategoryRuleDao,
    private val autoCategorizer: AutoCategorizer,
) : ViewModel() {
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

    private val transferId: String = URLDecoder.decode(savedStateHandle.get<String>("transferId").orEmpty(), "UTF-8")
    val detail: StateFlow<TransactionDetailUiState?> = combine(
        transferAnnotationDao.observeDetail(transferId),
        categoryDao.observeAll(),
        tagDao.observeAll(),
        accountDao.observeAll(),
    ) { transfer, categories, tags, accounts -> transfer?.toDetailUi(categories, tags, accounts.associateBy(Account::id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveDetail(categoryId: String?, tagIds: Set<String>, note: String) {
        viewModelScope.launch {
            val item = transferAnnotationDao.observeDetail(transferId).first() ?: return@launch
            transferAnnotationDao.saveManualAnnotation(
                TransferAnnotation(
                    transferId = item.transfer.id,
                    extensionId = item.transfer.extensionId,
                    categoryId = categoryId,
                    note = note,
                    categoryAssignment = AssignmentSource.MANUAL,
                ),
                tagIds,
            )
        }
    }

    fun createTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || tags.value.any { it.name.equals(trimmed, ignoreCase = true) }) return
        viewModelScope.launch {
            tagDao.upsert(Tag(UUID.randomUUID().toString(), trimmed, colorString(defaultCategoryColors.first())))
        }
    }

    fun resumeAutomatic() {
        viewModelScope.launch {
            autoCategorizer.resumeAutomaticCategorization(transferId)
        }
    }

    fun saveCategory(id: String?, name: String, color: Long) = saveNamedItem(
        name = name,
        duplicate = { candidate -> categories.value.any { it.id != id && it.name.equals(candidate, ignoreCase = true) } },
    ) { trimmed ->
        categoryDao.upsert(Category(id ?: UUID.randomUUID().toString(), trimmed, colorString(color)))
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

    fun applyRuleToExisting(@Suppress("UNUSED_PARAMETER") ruleId: String) {
        viewModelScope.launch {
            autoCategorizer.applyToExistingTransactions()
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

}

private fun TransferDetail.toDetailUi(
    categories: List<Category>,
    tags: List<Tag>,
    accounts: Map<String, Account>,
): TransactionDetailUiState {
    val account = accounts[transfer.accountId]
    return TransactionDetailUiState(
        title = category?.name ?: transfer.type.orEmpty(),
        amountText = tw.kevinzhang.moneylook.ui.home.signedTransferAmount(
            transfer.amount,
            account?.currency.orEmpty(),
        ),
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
    )
}

private fun asCategoryOptions(items: List<Category>) = items.map { CategoryOption(it.id, it.name, it.color.parseColor()) }
private fun asTagOptions(items: List<Tag>) = items.map { TagOption(it.id, it.name, it.color.parseColor()) }
private fun AutoCategoryRuleWithTags.toDraft() = rule.toDraft(tags.mapTo(mutableSetOf()) { it.id })
private fun tw.kevinzhang.core.data.model.AutoCategoryRule.toDraft(tagIds: Set<String>) = AutoRuleDraft(
    id = id,
    name = name,
    descriptionContains = descriptionContains.orEmpty(),
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
    ) to tagIds
}

private fun colorString(value: Long) = "#%06X".format(value and 0xFFFFFF)
private fun String.parseColor(): Long = removePrefix("#").toLongOrNull(16)?.let { 0xFF000000L or it } ?: defaultCategoryColors.first()
