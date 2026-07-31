package tw.kevinzhang.moneylook.ui.transactions

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleSave
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.CreditCardInstrumentDao
import tw.kevinzhang.core.data.db.CreditCardInstrumentMetadata
import tw.kevinzhang.core.data.db.DefaultClassificationCatalog
import tw.kevinzhang.core.data.db.PendingTag
import tw.kevinzhang.core.data.db.TagDao
import tw.kevinzhang.core.data.db.TransactionDetailDraftSave
import tw.kevinzhang.core.data.db.TransactionDetailDraftStore
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransferDetail
import tw.kevinzhang.core.data.db.IngestionProvenanceDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.moneylook.sync.AutoCategorizer
import tw.kevinzhang.moneylook.sync.AutoCategoryApplicationProgress
import tw.kevinzhang.moneylook.sync.ClassificationResetProgress
import tw.kevinzhang.moneylook.sync.ClassificationResetStage
import java.net.URLDecoder
import java.util.UUID
import java.security.MessageDigest
import android.util.Base64
import javax.inject.Inject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

sealed interface ClassificationResetUiState {
    data object Idle : ClassificationResetUiState
    data object Confirming : ClassificationResetUiState
    data object ResettingCatalog : ClassificationResetUiState
    data class Reclassifying(
        val processedTransferCount: Int,
        val totalTransferCount: Int,
    ) : ClassificationResetUiState

    data class Success(
        val processedTransferCount: Int,
        val matchedTransferCount: Int,
    ) : ClassificationResetUiState

    data class Error(
        val message: String,
        val lastStage: ClassificationResetStage = ClassificationResetStage.RESETTING_CATALOG,
        val processedTransferCount: Int = 0,
        val totalTransferCount: Int = 0,
    ) : ClassificationResetUiState
}

enum class ApplyAllRulesStage {
    PREPARING,
    APPLYING,
}

sealed interface ApplyAllRulesUiState {
    data object Idle : ApplyAllRulesUiState
    data object Confirming : ApplyAllRulesUiState
    data object Preparing : ApplyAllRulesUiState
    data class Applying(
        val processedTransferCount: Int,
        val totalTransferCount: Int,
    ) : ApplyAllRulesUiState

    data class Success(
        val processedTransferCount: Int,
        val matchedTransferCount: Int,
        val preservedManualOverrideCount: Int,
    ) : ApplyAllRulesUiState

    data class Error(
        val message: String,
        val lastStage: ApplyAllRulesStage = ApplyAllRulesStage.PREPARING,
        val processedTransferCount: Int = 0,
        val totalTransferCount: Int = 0,
    ) : ApplyAllRulesUiState
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ClassificationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val accountDao: AccountDao,
    private val creditCardInstrumentDao: CreditCardInstrumentDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val transferAnnotationDao: TransferAnnotationDao,
    private val autoCategoryRuleDao: AutoCategoryRuleDao,
    private val transactionDetailDraftStore: TransactionDetailDraftStore,
    private val autoCategorizer: AutoCategorizer,
    private val ingestionProvenanceDao: IngestionProvenanceDao,
) : ViewModel() {
    private val isDetailSaving = MutableStateFlow(false)
    private val _applyAllRulesUiState = MutableStateFlow<ApplyAllRulesUiState>(ApplyAllRulesUiState.Idle)
    private val _classificationResetUiState = MutableStateFlow<ClassificationResetUiState>(ClassificationResetUiState.Idle)
    private val auditState = MutableStateFlow(TransactionAuditUi())

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
    val applyAllRulesUiState: StateFlow<ApplyAllRulesUiState> = _applyAllRulesUiState
    val classificationResetUiState: StateFlow<ClassificationResetUiState> = _classificationResetUiState

    private val transferId: String = URLDecoder.decode(savedStateHandle.get<String>("transferId").orEmpty(), "UTF-8")
    init {
        viewModelScope.launch { loadAudit() }
    }
    private val detailWithCard = transferAnnotationDao.observeDetail(transferId)
        .flatMapLatest { detail ->
            val cardId = detail?.transfer?.cardInstrumentId
            if (cardId == null) flowOf(detail to null)
            else creditCardInstrumentDao.observeByIds(listOf(cardId)).map { cards -> detail to cards.firstOrNull() }
        }

    private val baseDetail: StateFlow<TransactionDetailUiState?> = combine(
        detailWithCard,
        categoryDao.observeAll(),
        tagDao.observeAll(),
        accountDao.observeAll(),
        isDetailSaving,
    ) { detailAndCard, categories, tags, accounts, saving ->
        detailAndCard.first?.toDetailUi(
            categories,
            tags,
            accounts.associateBy(Account::id),
            detailAndCard.second,
            saving,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val detail: StateFlow<TransactionDetailUiState?> = combine(baseDetail, auditState) { state, audit ->
        state?.copy(
            sourceFields = audit.fields,
            auditTimeline = audit.timeline,
            sourceDocuments = audit.documents,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun loadSourceDocumentBody(documentId: String) {
        if (auditState.value.documents.none { it.id == documentId && it.bodyText == null }) return
        viewModelScope.launch {
            val document = ingestionProvenanceDao.getSourceDocument(documentId) ?: return@launch
            val body = tw.kevinzhang.moneylook.sync.readArchivedSourceBodyPreview(document)
            auditState.value = auditState.value.copy(
                documents = auditState.value.documents.map {
                    if (it.id == documentId) it.copy(bodyText = body) else it
                },
            )
        }
    }

    private suspend fun loadAudit() {
        val fields = ingestionProvenanceDao.getTransferFieldObservations(transferId)
            .map {
                SourceFieldAuditUi(
                    fieldName = it.fieldName,
                    valueJson = it.valueJson,
                    sourcePath = it.sourcePath,
                    parserVersion = it.parserVersion,
                    sourceDocumentId = it.sourceDocumentId,
                    runId = it.runId,
                    extensionId = it.extensionId,
                    observedAt = it.observedAt,
                    sourceFieldJson = it.sourceFieldJson,
                )
            }
        val documents = ingestionProvenanceDao.getSourceDocumentsForTransfer(transferId)
            .map {
                SourceDocumentAuditUi(
                    id = it.id,
                    stage = it.stage,
                    method = it.method,
                    url = it.url,
                    statusCode = it.statusCode,
                    capturedAt = it.capturedAt,
                    byteCount = it.bodyByteCount,
                    sha256 = it.bodySha256,
                    bodyEncoding = it.bodyEncoding,
                    runId = it.runId,
                    extensionId = it.extensionId,
                    transport = it.transport,
                    mediaKind = it.mediaKind,
                    representation = it.representation,
                    responseHeadersJson = it.responseHeadersJson,
                )
            }
        val ingestion = ingestionProvenanceDao.getTransferIngestionEvents(transferId)
            .map { "${it.occurredAt} · 匯入 ${it.observation} · run ${it.runId} · ${it.extensionId}" }
        val runs = ingestionProvenanceDao.getIngestionRunsForTransfer(transferId)
            .map {
                "${it.startedAt} · run ${it.id} · ${it.trigger} · ${it.status} · " +
                    "${it.extensionId} v${it.extensionVersion}"
            }
        val annotations = ingestionProvenanceDao.getTransferAnnotationEvents(transferId)
            .map {
                "${it.occurredAt} · 分類 ${it.outcome} · run ${it.runId ?: "無"} · " +
                    "規則 ${it.ruleId ?: "無"} · set ${it.ruleSetId ?: "無"} · ${it.trigger} · " +
                    "score=${it.matchScore ?: "無"} · classifier=${it.classifierVersion ?: "無"}"
            }
        val rules = ingestionProvenanceDao.getRuleEvaluations(transferId)
            .map {
                "${it.evaluatedAt} · 規則 ${it.ruleId} · ${it.reasonCode} · " +
                    "run=${it.runId ?: "無"} · set=${it.ruleSetId ?: "無"} · " +
                    "hash=${it.ruleContentSha256 ?: "無"} · scope=${it.scopeMatched}, " +
                    "conditions=${it.conditionsMatched}, compatible=${it.categoryCompatible}, " +
                    "matched=${it.matched}, selected=${it.selected}, score=${it.score ?: "無"}, " +
                    "classifier=${it.classifierVersion}"
            }
        val conditions = ingestionProvenanceDao.getConditionEvaluations(transferId)
            .map {
                "${it.evaluatedAt} · eval ${it.ruleEvaluationId} · " +
                    "group ${it.conditionGroup}#${it.position} · ${it.field} ${it.matchMode} ${it.pattern} · " +
                    "actual=${it.candidateValuesJson} · matched=${it.matched}"
            }
        auditState.value = TransactionAuditUi(
            fields = fields,
            documents = documents,
            timeline = (runs + ingestion + annotations + rules + conditions).sortedDescending(),
        )
    }

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
                val ruleSave = draft.matchingRule?.normalizedOrNull()?.let { normalized ->
                    AutoCategoryRuleSave(
                        rule = normalized.rule,
                        conditions = normalized.conditions,
                        tagIds = normalized.tagIds.filterNot(::isDraftTagId).toSet(),
                        pendingTagNames = normalized.tagIds.filter(::isDraftTagId).map { draftTagName(it) }.toSet(),
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
                if (draft.matchingRule?.applyExisting == true) {
                    autoCategorizer.applyToExistingTransactions(ClassificationTrigger.RULE_SAVE)
                }
                onSaved()
            } finally {
                isDetailSaving.value = false
            }
        }
    }

    fun saveCategory(
        id: String?,
        name: String,
        color: Long,
        reportingGroup: CategoryReportingGroup,
    ) = saveNamedItem(
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
                reportingGroup = reportingGroup,
            ),
        )
    }

    fun createCategory(
        name: String,
        color: Long,
        reportingGroup: CategoryReportingGroup,
        onResult: (CategoryCreationResult) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                onResult(persistNewCategory(categoryDao, name, color, reportingGroup))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(CategoryCreationResult.Failed)
            }
        }
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
            autoCategoryRuleDao.upsertWithDetails(
                rule = normalized.rule,
                conditions = normalized.conditions,
                tagIds = normalized.tagIds,
            )
            if (draft.applyExisting) {
                autoCategorizer.applyToExistingTransactions(ClassificationTrigger.RULE_SAVE)
            }
        }
    }

    fun deleteRule(id: String) = viewModelScope.launch { autoCategoryRuleDao.deleteById(id) }

    fun showApplyAllRulesConfirmation() {
        if (
            _applyAllRulesUiState.value != ApplyAllRulesUiState.Idle ||
            _classificationResetUiState.value != ClassificationResetUiState.Idle
        ) return
        _applyAllRulesUiState.value = ApplyAllRulesUiState.Confirming
    }

    fun cancelApplyAllRules() {
        if (_applyAllRulesUiState.value == ApplyAllRulesUiState.Confirming) {
            _applyAllRulesUiState.value = ApplyAllRulesUiState.Idle
        }
    }

    fun startApplyAllRules() {
        if (
            _applyAllRulesUiState.value != ApplyAllRulesUiState.Confirming ||
            _classificationResetUiState.value != ClassificationResetUiState.Idle
        ) return
        runApplyAllRules()
    }

    fun retryApplyAllRules() {
        if (
            _applyAllRulesUiState.value !is ApplyAllRulesUiState.Error ||
            _classificationResetUiState.value != ClassificationResetUiState.Idle
        ) return
        runApplyAllRules()
    }

    fun dismissApplyAllRules() {
        when (_applyAllRulesUiState.value) {
            is ApplyAllRulesUiState.Success,
            is ApplyAllRulesUiState.Error,
            -> _applyAllRulesUiState.value = ApplyAllRulesUiState.Idle
            else -> Unit
        }
    }

    private fun runApplyAllRules() {
        _applyAllRulesUiState.value = ApplyAllRulesUiState.Preparing
        viewModelScope.launch {
            var latestProgress: AutoCategoryApplicationProgress? = null
            try {
                val result = withContext(Dispatchers.Default) {
                    autoCategorizer.applyToExistingTransactions { progress ->
                        latestProgress = progress
                        _applyAllRulesUiState.value = ApplyAllRulesUiState.Applying(
                            processedTransferCount = progress.processedTransferCount,
                            totalTransferCount = progress.totalTransferCount,
                        )
                    }
                }
                _applyAllRulesUiState.value = ApplyAllRulesUiState.Success(
                    processedTransferCount = result.processedTransferCount,
                    matchedTransferCount = result.matchedTransferCount,
                    preservedManualOverrideCount = result.preservedManualOverrideCount,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _applyAllRulesUiState.value = ApplyAllRulesUiState.Error(
                    message = "套用規則失敗，請稍後再試。",
                    lastStage = if (latestProgress == null) {
                        ApplyAllRulesStage.PREPARING
                    } else {
                        ApplyAllRulesStage.APPLYING
                    },
                    processedTransferCount = latestProgress?.processedTransferCount ?: 0,
                    totalTransferCount = latestProgress?.totalTransferCount ?: 0,
                )
            }
        }
    }

    fun showResetClassificationConfirmation() {
        if (
            _applyAllRulesUiState.value != ApplyAllRulesUiState.Idle ||
            _classificationResetUiState.value != ClassificationResetUiState.Idle
        ) return
        _classificationResetUiState.value = ClassificationResetUiState.Confirming
    }

    fun cancelClassificationReset() {
        if (_classificationResetUiState.value == ClassificationResetUiState.Confirming) {
            _classificationResetUiState.value = ClassificationResetUiState.Idle
        }
    }

    fun startClassificationReset() {
        if (
            _applyAllRulesUiState.value != ApplyAllRulesUiState.Idle ||
            _classificationResetUiState.value != ClassificationResetUiState.Confirming
        ) return
        runClassificationReset()
    }

    fun retryClassificationReset() {
        if (
            _applyAllRulesUiState.value != ApplyAllRulesUiState.Idle ||
            _classificationResetUiState.value !is ClassificationResetUiState.Error
        ) return
        runClassificationReset()
    }

    fun dismissClassificationReset() {
        when (_classificationResetUiState.value) {
            is ClassificationResetUiState.Success,
            is ClassificationResetUiState.Error,
            -> _classificationResetUiState.value = ClassificationResetUiState.Idle
            else -> Unit
        }
    }

    private fun runClassificationReset() {
        _classificationResetUiState.value = ClassificationResetUiState.ResettingCatalog
        viewModelScope.launch {
            var latestProgress = ClassificationResetProgress(ClassificationResetStage.RESETTING_CATALOG)
            try {
                val result = withContext(Dispatchers.Default) {
                    autoCategorizer.resetClassificationSystem { progress ->
                        latestProgress = progress
                        _classificationResetUiState.value = when (progress.stage) {
                            ClassificationResetStage.RESETTING_CATALOG ->
                                ClassificationResetUiState.ResettingCatalog
                            ClassificationResetStage.RECLASSIFYING_TRANSACTIONS ->
                                ClassificationResetUiState.Reclassifying(
                                    processedTransferCount = progress.processedTransferCount,
                                    totalTransferCount = progress.totalTransferCount,
                                )
                        }
                    }
                }
                _classificationResetUiState.value = ClassificationResetUiState.Success(
                    processedTransferCount = result.processedTransferCount,
                    matchedTransferCount = result.matchedTransferCount,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _classificationResetUiState.value = ClassificationResetUiState.Error(
                    message = "重設分類系統失敗，請稍後再試。",
                    lastStage = latestProgress.stage,
                    processedTransferCount = latestProgress.processedTransferCount,
                    totalTransferCount = latestProgress.totalTransferCount,
                )
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
    card: CreditCardInstrumentMetadata?,
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
        extensionId = transfer.extensionId,
        cardDisplayLabel = card?.let { instrument ->
            listOfNotNull(
                instrument.displayName?.trim()?.takeIf(String::isNotBlank) ?: "信用卡",
                instrument.maskedPan?.trim()?.takeIf(String::isNotBlank)
                    ?: instrument.lastFour?.takeIf { it.matches(Regex("\\d{4}")) }?.let { "•••• $it" },
            ).joinToString(" · ")
        },
        transactionDate = transfer.txnDateTime.take(10),
        postingDate = transfer.postingDateTime?.take(10),
        description = transfer.description,
        bankMemo = transfer.memo,
        selectedCategoryId = annotation?.categoryId,
        selectedTagIds = this.tags.mapTo(mutableSetOf()) { it.id },
        userNote = annotation?.note.orEmpty(),
        categories = asCategoryOptions(categories),
        tags = asTagOptions(tags),
        accountKind = account?.kind ?: AssetKind.DEPOSIT,
        status = transfer.status,
        isManualOverride = annotation?.manualOverride == true,
        isSaving = isSaving,
    )
}

private fun asCategoryOptions(items: List<Category>) = items.map {
    it.toCategoryOption()
}

private fun Category.toCategoryOption() =
    CategoryOption(id, name, color.parseColor(), emoji, reportingGroup)

internal suspend fun persistNewCategory(
    categoryDao: CategoryDao,
    name: String,
    color: Long,
    reportingGroup: CategoryReportingGroup,
    idFactory: () -> String = { UUID.randomUUID().toString() },
): CategoryCreationResult {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return CategoryCreationResult.Failed
    if (categoryDao.observeAll().first().any { it.name.equals(trimmed, ignoreCase = true) }) {
        return CategoryCreationResult.DuplicateName
    }
    val category = Category(
        id = idFactory(),
        name = trimmed,
        color = colorString(color),
        emoji = Category.DEFAULT_EMOJI,
        reportingGroup = reportingGroup,
    )
    categoryDao.upsert(category)
    return CategoryCreationResult.Created(category.toCategoryOption())
}

private data class TransactionAuditUi(
    val fields: List<SourceFieldAuditUi> = emptyList(),
    val timeline: List<String> = emptyList(),
    val documents: List<SourceDocumentAuditUi> = emptyList(),
)
private fun asTagOptions(items: List<Tag>) = items.map { TagOption(it.id, it.name, it.color.parseColor()) }
private fun AutoCategoryRuleWithTags.toDraft() = rule.toDraft(
    tagIds = tags.mapTo(mutableSetOf()) { it.id },
    conditions = conditions,
)
private fun tw.kevinzhang.core.data.model.AutoCategoryRule.toDraft(
    tagIds: Set<String>,
    conditions: List<AutoCategoryRuleCondition>,
) = AutoRuleDraft(
    id = id,
    name = name,
    descriptionContains = descriptionContains.orEmpty(),
    descriptionMatchMode = descriptionMatchMode,
    amountSign = amountSign,
    minAbsoluteAmount = minAbsoluteAmount?.toString().orEmpty(),
    maxAbsoluteAmount = maxAbsoluteAmount?.toString().orEmpty(),
    accountId = accountId,
    categoryId = categoryId,
    tagIds = tagIds,
    enabled = enabled,
    priority = priority,
    isDefault = isDefault,
    createExactDescriptionCondition = conditions.isSingleDescriptionCondition(
        descriptionContains,
        descriptionMatchMode,
    ),
    updateLegacyAnyTextCondition = conditions.isSingleLegacyAnyTextCondition(
        descriptionContains,
        descriptionMatchMode,
    ),
    conditions = conditions,
    ruleSetId = ruleSetId,
    accountKind = accountKind,
    extensionId = extensionId,
    origin = origin,
    action = action,
)

internal data class NormalizedAutoRule(
    val rule: tw.kevinzhang.core.data.model.AutoCategoryRule,
    val tagIds: Set<String>,
    val conditions: List<AutoCategoryRuleCondition>,
)

internal fun AutoRuleDraft.normalizedOrNull(): NormalizedAutoRule? {
    if (!isValidForSave()) return null
    val min = minAbsoluteAmount.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: minAbsoluteAmount.takeIf(String::isNotBlank)?.let { return null }
    val max = maxAbsoluteAmount.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: maxAbsoluteAmount.takeIf(String::isNotBlank)?.let { return null }
    if (min != null && max != null && min > max) return null
    val normalizedRule = tw.kevinzhang.core.data.model.AutoCategoryRule(
        id = id.ifBlank { UUID.randomUUID().toString() }, name = name.trim(), descriptionContains = descriptionContains.trim().takeIf(String::isNotEmpty),
        amountSign = amountSign,
        minAbsoluteAmount = min, maxAbsoluteAmount = max, accountId = accountId, categoryId = categoryId, enabled = enabled, priority = priority,
        descriptionMatchMode = descriptionMatchMode,
        isDefault = isDefault,
        ruleSetId = ruleSetId,
        accountKind = accountKind,
        extensionId = extensionId,
        origin = origin,
        action = action,
    )
    val exactDescriptionPattern = normalizedRule.descriptionContains
    val normalizedConditions = when {
        createExactDescriptionCondition && exactDescriptionPattern != null -> listOf(
            AutoCategoryRuleCondition(
                ruleId = normalizedRule.id,
                position = 0,
                conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                field = AutoCategoryRuleConditionField.DESCRIPTION,
                matchMode = when (normalizedRule.descriptionMatchMode) {
                    AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                        AutoCategoryRuleConditionMatchMode.CONTAINS
                    AutoCategoryRuleDescriptionMatchMode.EXACT ->
                        AutoCategoryRuleConditionMatchMode.EXACT
                },
                pattern = exactDescriptionPattern,
            ),
        )
        updateLegacyAnyTextCondition -> exactDescriptionPattern?.let { pattern ->
            listOf(
                AutoCategoryRuleCondition(
                    ruleId = normalizedRule.id,
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
                    matchMode = when (normalizedRule.descriptionMatchMode) {
                        AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                            AutoCategoryRuleConditionMatchMode.CONTAINS
                        AutoCategoryRuleDescriptionMatchMode.EXACT ->
                            AutoCategoryRuleConditionMatchMode.EXACT
                    },
                    pattern = pattern,
                ),
            )
        }.orEmpty()
        conditions.isNotEmpty() -> conditions.map { it.copy(ruleId = normalizedRule.id) }
        else -> emptyList()
    }
    return NormalizedAutoRule(normalizedRule, tagIds, normalizedConditions)
}

private fun List<AutoCategoryRuleCondition>.isSingleDescriptionCondition(
    descriptionContains: String?,
    descriptionMatchMode: AutoCategoryRuleDescriptionMatchMode,
): Boolean = isSingleEditableTextCondition(
    descriptionContains = descriptionContains,
    descriptionMatchMode = descriptionMatchMode,
    field = AutoCategoryRuleConditionField.DESCRIPTION,
)

private fun List<AutoCategoryRuleCondition>.isSingleLegacyAnyTextCondition(
    descriptionContains: String?,
    descriptionMatchMode: AutoCategoryRuleDescriptionMatchMode,
): Boolean = isSingleEditableTextCondition(
    descriptionContains = descriptionContains,
    descriptionMatchMode = descriptionMatchMode,
    field = AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
)

private fun List<AutoCategoryRuleCondition>.isSingleEditableTextCondition(
    descriptionContains: String?,
    descriptionMatchMode: AutoCategoryRuleDescriptionMatchMode,
    field: AutoCategoryRuleConditionField,
): Boolean {
    val condition = singleOrNull() ?: return false
    val expectedMatchMode = when (descriptionMatchMode) {
        AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
            AutoCategoryRuleConditionMatchMode.CONTAINS
        AutoCategoryRuleDescriptionMatchMode.EXACT ->
            AutoCategoryRuleConditionMatchMode.EXACT
    }
    return descriptionContains != null &&
        condition.conditionGroup == AutoCategoryRuleConditionGroup.INCLUDE_ANY &&
        condition.field == field &&
        condition.matchMode == expectedMatchMode &&
        condition.pattern == descriptionContains
}

private fun colorString(value: Long) = "#%06X".format(value and 0xFFFFFF)
private fun String.parseColor(): Long = removePrefix("#").toLongOrNull(16)?.let { 0xFF000000L or it } ?: defaultCategoryColors.first()
private fun isDraftTagId(id: String): Boolean = id.startsWith("draft:")
private fun draftTagName(id: String): String = id.removePrefix("draft:")
