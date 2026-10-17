package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import tw.kevinzhang.core.data.model.*
import tw.kevinzhang.core.data.classification.*
import javax.inject.Inject

class LocalClassificationStore @Inject constructor(private val database: MoneylookDatabase) {
    private val dao get() = database.localClassificationDao()
    /** A DB snapshot provides consistent learning/rules across pages; cancellation rolls it back. */
    suspend fun <T> snapshot(block: suspend () -> T): T = database.withTransaction { block() }

    suspend fun sourceManifestId(extensionId: String): String? = dao.extension(extensionId)?.manifestId

    suspend fun evidence(id: String): ClassificationLocalEvidence? = dao.evidence(id)

    suspend fun refreshExamples() {
        dao.pruneExamples()
        dao.pruneTokens()
        var after = ""
        while (true) {
            val page = dao.manualPage(after, 250)
            if (page.isEmpty()) break
            for (row in page) {
                val old = dao.example(row.transfer.id)
                val features = featuresOf(row.transfer)
                val seq = old?.takeIf { it.categoryId == row.learningCategoryId }?.correctionSequence ?: 0
                val next = ClassificationLearningExample(row.transfer.id, row.learningCategoryId, features.accountId,
                    features.direction, features.identity, features.transactionType, bigrams(features.identity).size, seq)
                if (next != old) {
                    dao.putExample(next)
                    dao.deleteTokens(next.transferId)
                    dao.putTokens(bigrams(features.identity).map { ClassificationLearningToken(it, next.transferId) })
                }
            }
            after = page.last().transfer.id
        }
    }

    suspend fun learned(t: Transfer): LocalCategoryChoice? {
        val f = featuresOf(t)
        if (f.identity.isEmpty()) return null
        return chooseLearned(f, dao.exact(f.identity, f.direction, f.accountId, f.transactionType).map { it.sample() })
    }
    suspend fun history(t: Transfer): LocalCategoryChoice? {
        val f = featuresOf(t)
        val tokens = bigrams(f.identity).toList()
        if (tokens.isEmpty()) return null
        return chooseHistory(f, dao.similar(tokens, tokens.size, f.direction).map { it.sample() })
    }
    suspend fun ownedAccounts(): List<OwnedBankAccount> = dao.ownedAccounts().mapNotNull { account ->
        val bank = normalizeBankIdentity(account.bankId)
            ?: normalizeBankIdentity(dao.extension(account.extensionId)?.manifestId) ?: return@mapNotNull null
        OwnedBankAccount(account.id, bank, account.accountNo ?: return@mapNotNull null)
    }
    suspend fun ensureFallbackCategories(): Map<CategoryReportingGroup, String> = database.withTransaction {
        val roles = dao.roles().associateBy { it.role }
        CategoryReportingGroup.entries.associateWith { group ->
            val current = roles[group.name]?.let { database.categoryDao().getById(it.categoryId) }
            val label = when (group) {
                CategoryReportingGroup.INCOME -> "收入"
                CategoryReportingGroup.EXPENSE -> "支出"
                CategoryReportingGroup.EXCLUDED -> "零額"
            }
            val existingNames = database.categoryDao().allNames().toSet()
            val base = "其他／用途不明（$label）"
            if (current != null && current.reportingGroup == group &&
                (current.name == base || current.name.matches(Regex("${Regex.escape(base)} (?:[2-9]|[1-9][0-9]+)")))) {
                return@associateWith current.id
            }
            var name = base
            var suffix = 2
            while (name in existingNames) { name = "$base ${suffix++}" }
            val id = "local-fallback-${group.name.lowercase()}-${java.util.UUID.randomUUID()}"
            database.categoryDao().upsert(Category(id, name, "#78909C", reportingGroup = group))
            dao.putRole(ClassificationFallbackRole(group.name, id))
            id
        }
    }
}
