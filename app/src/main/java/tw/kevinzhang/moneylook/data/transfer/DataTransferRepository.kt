package tw.kevinzhang.moneylook.data.transfer

import androidx.room.withTransaction
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.google.gson.Gson
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvCodec
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvDecodeResult
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvImport
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.CredentialProfileCsvCodec
import tw.kevinzhang.core.data.db.CredentialProfileCsvDecodeResult
import tw.kevinzhang.core.data.db.CredentialProfileCsvExtension
import tw.kevinzhang.core.data.db.CredentialProfileCsvFieldDefinition
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TagDao
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.moneylook.ui.home.CredentialJsonCodec

data class DataImportPreview(
    val newCount: Int,
    val overwriteCount: Int,
    val skippedCount: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val canCommit: Boolean get() = errors.isEmpty()
}

sealed interface PreparedDataImport {
    val preview: DataImportPreview

    data class AutoRules(
        val value: AutoCategoryRuleCsvImport,
        override val preview: DataImportPreview,
    ) : PreparedDataImport

    data class Credentials(
        val value: List<CredentialProfileCsvExtension>,
        override val preview: DataImportPreview,
    ) : PreparedDataImport
}

sealed interface PrepareDataImportResult {
    data class Success(val value: PreparedDataImport) : PrepareDataImportResult
    data class Failure(val reason: String) : PrepareDataImportResult
}

/**
 * Owns the database boundary for CSV transfer. Parsing and reference validation complete before
 * Room is mutated, and each confirmed import is committed in one database transaction.
 */
class DataTransferRepository @Inject constructor(
    private val database: MoneylookDatabase,
    private val autoCategoryRuleDao: AutoCategoryRuleDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val accountDao: AccountDao,
    private val credentialProfileDao: CredentialProfileDao,
    private val installedExtensionDao: InstalledExtensionDao,
    gson: Gson,
) {
    private val credentialJsonCodec = CredentialJsonCodec(gson)
    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX),
    )

    suspend fun exportAutoRules(): String {
        val rules = autoCategoryRuleDao.observeAll()
            .first()
            .filter { row ->
                !row.rule.isDefault &&
                    row.rule.origin != AutoCategoryRuleOrigin.PUBLIC_DEFAULT
            }
        return AutoCategoryRuleCsvCodec.encode(
            AutoCategoryRuleCsvImport(
                rules = rules.map { it.rule },
                conditionsByRuleId = rules.associate { row ->
                    row.rule.id to row.conditions
                },
                tagsByRuleId = rules.associate { row ->
                    row.rule.id to row.tags.map { it.id }
                },
            ),
        )
    }

    suspend fun prepareAutoRules(csv: String): PrepareDataImportResult =
        when (val decoded = AutoCategoryRuleCsvCodec.decode(csv)) {
            is AutoCategoryRuleCsvDecodeResult.Failure ->
                PrepareDataImportResult.Failure(decoded.reason)
            is AutoCategoryRuleCsvDecodeResult.Success -> {
                val errors = validateAutoRules(decoded.value)
                val existingIds = autoCategoryRuleDao.observeAll()
                    .first()
                    .mapTo(mutableSetOf()) { it.rule.id }
                PrepareDataImportResult.Success(
                    PreparedDataImport.AutoRules(
                        value = decoded.value,
                        preview = DataImportPreview(
                            newCount = decoded.value.rules.count { it.id !in existingIds },
                            overwriteCount = decoded.value.rules.count { it.id in existingIds },
                            errors = errors,
                        ),
                    ),
                )
            }
        }

    suspend fun commitAutoRules(import: PreparedDataImport.AutoRules) {
        database.withTransaction {
            val errors = validateAutoRules(import.value)
            require(errors.isEmpty()) { errors.joinToString("；") }
            import.value.rules.forEach { rule ->
                autoCategoryRuleDao.upsertWithDetails(
                    rule = rule,
                    conditions = import.value.conditionsByRuleId[rule.id].orEmpty(),
                    tagIds = import.value.tagsByRuleId[rule.id].orEmpty().toSet(),
                )
            }
        }
    }

    suspend fun exportCredentials(): String {
        val extensions = installedExtensionDao.getAll().associateBy { it.id }
        val rows = credentialProfileDao.getAll().map { profile ->
            val extension = requireNotNull(extensions[profile.extensionId]) {
                "登入資料對應的擴充不存在"
            }
            CredentialProfileCsvExtension(
                extensionId = extension.id,
                extensionName = extension.name,
                profile = profile,
                fields = credentialJsonCodec.parseFields(extension.credentialFieldsJson).map { field ->
                    CredentialProfileCsvFieldDefinition(
                        key = field.key,
                        label = field.label,
                        type = field.type,
                        required = field.required,
                    )
                },
            )
        }
        require(rows.isNotEmpty()) { "沒有可匯出的帳號密碼" }
        return CredentialProfileCsvCodec.encode(rows)
    }

    suspend fun prepareCredentials(csv: String): PrepareDataImportResult =
        when (val decoded = CredentialProfileCsvCodec.decode(csv)) {
            is CredentialProfileCsvDecodeResult.Failure ->
                PrepareDataImportResult.Failure(decoded.reason)
            is CredentialProfileCsvDecodeResult.Success -> {
                val normalized = normalizeAndValidateCredentials(decoded.value)
                val existingIds = credentialProfileDao.getAll()
                    .mapTo(mutableSetOf(), CredentialProfile::extensionId)
                PrepareDataImportResult.Success(
                    PreparedDataImport.Credentials(
                        // Keep every decoded row in the prepared payload. Invalid rows must still
                        // be present when commit re-validates inside the Room transaction; dropping
                        // them here could let a caller bypass the preview error and partially import.
                        value = decoded.value,
                        preview = DataImportPreview(
                            newCount = decoded.value.count { it.extensionId !in existingIds },
                            overwriteCount = decoded.value.count { it.extensionId in existingIds },
                            errors = normalized.errors,
                        ),
                    ),
                )
            }
        }

    /**
     * Returns the committed profiles so callers can update WorkManager after Room succeeds.
     * This method never starts a synchronization.
     */
    suspend fun commitCredentials(import: PreparedDataImport.Credentials): List<CredentialProfile> {
        val committed = mutableListOf<CredentialProfile>()
        database.withTransaction {
            val normalized = normalizeAndValidateCredentials(import.value)
            require(normalized.errors.isEmpty()) { normalized.errors.joinToString("；") }
            val existing = credentialProfileDao.getAll().associateBy { it.extensionId }
            normalized.value.forEach { row ->
                val previous = existing[row.extensionId]
                val profile = row.profile.copy(
                    lastRunAt = previous?.lastRunAt,
                    lastRunStatus = previous?.lastRunStatus,
                )
                credentialProfileDao.upsert(profile)
                committed += profile
            }
        }
        return committed
    }

    private suspend fun validateAutoRules(import: AutoCategoryRuleCsvImport): List<String> {
        val categories = categoryDao.observeAll().first().mapTo(mutableSetOf()) { it.id }
        val tags = tagDao.observeAll().first().mapTo(mutableSetOf()) { it.id }
        val accounts = accountDao.observeAll().first().mapTo(mutableSetOf()) { it.id }
        val extensions = installedExtensionDao.getAll().mapTo(mutableSetOf()) { it.id }
        return buildList {
            import.rules.forEach { rule ->
                if (rule.isDefault || rule.origin == AutoCategoryRuleOrigin.PUBLIC_DEFAULT) {
                    add("規則「${rule.name}」是系統預設規則，不能由 CSV 覆蓋")
                }
                rule.categoryId?.takeIf { it !in categories }?.let {
                    add("規則「${rule.name}」找不到分類 $it")
                }
                rule.accountId?.takeIf { it !in accounts }?.let {
                    add("規則「${rule.name}」找不到帳戶 $it")
                }
                rule.extensionId?.takeIf { it !in extensions }?.let {
                    add("規則「${rule.name}」找不到擴充 $it")
                }
                import.tagsByRuleId[rule.id].orEmpty()
                    .filterNot(tags::contains)
                    .forEach { add("規則「${rule.name}」找不到標籤 $it") }
            }
        }.distinct()
    }

    private suspend fun normalizeAndValidateCredentials(
        rows: List<CredentialProfileCsvExtension>,
    ): CredentialValidation {
        val installed = installedExtensionDao.getAll().associateBy { it.id }
        val normalized = mutableListOf<CredentialProfileCsvExtension>()
        val errors = mutableListOf<String>()
        rows.forEach { row ->
            val extension = installed[row.extensionId]
            if (extension == null) {
                errors += "找不到擴充 ${row.extensionName}（${row.extensionId}）"
                return@forEach
            }
            val expectedFields = credentialJsonCodec.parseFields(extension.credentialFieldsJson)
            val importedFields = row.fields.associateBy { it.key }
            val schemaMatches = expectedFields.size == importedFields.size &&
                expectedFields.all { expected ->
                    importedFields[expected.key]?.let { imported ->
                        imported.label == expected.label &&
                            imported.type == expected.type &&
                            imported.required == expected.required
                    } == true
                }
            if (!schemaMatches) {
                errors += "擴充「${extension.name}」的登入欄位已變更"
                return@forEach
            }
            val values = credentialJsonCodec.parseCredential(row.profile.credential)
            if (values == null || values.keys != importedFields.keys) {
                errors += "擴充「${extension.name}」的登入資料欄位不完整"
                return@forEach
            }
            val resolution = credentialJsonCodec.resolveForSave(
                fields = expectedFields,
                submittedValues = values,
                existingValues = emptyMap(),
            )
            if (resolution.missingRequiredField != null) {
                errors += "擴充「${extension.name}」缺少${resolution.missingRequiredField.label}"
                return@forEach
            }
            if (row.profile.scheduleEnabled &&
                !isValidSchedule(row.profile.scheduleCron, row.profile.timezoneId)
            ) {
                errors += "擴充「${extension.name}」的排程或時區格式不正確"
                return@forEach
            }
            normalized += row.copy(
                extensionName = extension.name,
                profile = row.profile.copy(
                    credential = credentialJsonCodec.encodeCredential(resolution.values),
                    scheduleCron = row.profile.scheduleCron.trim(),
                    timezoneId = row.profile.timezoneId.trim(),
                    lastRunAt = null,
                    lastRunStatus = null,
                ),
            )
        }
        return CredentialValidation(normalized, errors.distinct())
    }

    private fun isValidSchedule(cron: String, timezoneId: String): Boolean = runCatching {
        ZoneId.of(timezoneId)
        cronParser.parse(cron).validate()
    }.isSuccess

    private data class CredentialValidation(
        val value: List<CredentialProfileCsvExtension>,
        val errors: List<String>,
    )
}
