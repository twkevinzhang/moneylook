package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import tw.kevinzhang.core.data.model.*
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

fun normalizeAiCategoryName(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

class AiClassificationStore @Inject constructor(private val db: MoneylookDatabase) {
    private val dao get() = db.aiClassificationDao()
    suspend fun isFirst(): Boolean = dao.state()?.complete == false
    suspend fun pendingIds(): List<String> = (if (isFirst()) dao.pending() else emptyList())
        .plus(dao.uncategorized()).distinct().sorted()
    suspend fun begin() {
        dao.state()?.takeUnless { it.complete }?.let { dao.putState(it.copy(started = true)) }
    }
    suspend fun legacy(id: String): AiClassificationWork? = dao.work(id)
    suspend fun skip(id: String) = dao.complete(id)
    suspend fun finish() {
        if (dao.pending().isEmpty() && dao.uncategorized().isEmpty()) {
            dao.putState(AiClassificationState(started = true, complete = true))
        }
    }

    /** Call only after the network has finished. Source, category and work commit together. */
    suspend fun guarded(
        transfer: Transfer,
        expected: TransferAnnotation?,
        commit: suspend () -> AutomaticClassificationWriteResult,
    ): AutomaticClassificationWriteResult = db.withTransaction {
        val current = db.transferAnnotationDao().getByTransferIds(listOf(transfer.id)).singleOrNull()
        if (current?.manualOverride == true) {
            dao.complete(transfer.id)
            return@withTransaction AutomaticClassificationWriteResult.PRESERVED_MANUAL
        }
        val live = db.transferDao().getByIds(listOf(transfer.id)).singleOrNull()
        if (live != transfer || current?.categoryId != expected?.categoryId ||
            current?.autoRuleId != expected?.autoRuleId || current?.autoRuleSetId != expected?.autoRuleSetId ||
            current?.classifierVersion != expected?.classifierVersion) {
            if (live == null || current?.categoryId != expected?.categoryId || current?.autoRuleId != expected?.autoRuleId)
                dao.complete(transfer.id)
            return@withTransaction AutomaticClassificationWriteResult.RECORDED_ONLY
        }
        val result = commit()
        if (result == AutomaticClassificationWriteResult.APPLIED) dao.complete(transfer.id)
        result
    }

    /** Must be called within guarded, so an invalid/stale response cannot create a category. */
    suspend fun resolve(proposal: AiCategoryProposal): String = when (val selection = proposal.selection) {
        is AiCategorySelection.Existing -> {
            require(selection.categoryId != "transfer-account")
            requireNotNull(db.categoryDao().getById(selection.categoryId)).id
        }
        is AiCategorySelection.New -> {
            val name = selection.name.trim()
            require(name.isNotEmpty() && name.length <= 80)
            val found = db.categoryDao().observeAll().first().firstOrNull {
                normalizeAiCategoryName(it.name) == normalizeAiCategoryName(name)
            }
            if (found != null) {
                require(found.id != "transfer-account" && found.reportingGroup == selection.reportingGroup)
                found.id
            } else {
                Category(UUID.randomUUID().toString(), name, "#607D8B", reportingGroup = selection.reportingGroup)
                    .also { db.categoryDao().upsert(it) }.id
            }
        }
    }
}
