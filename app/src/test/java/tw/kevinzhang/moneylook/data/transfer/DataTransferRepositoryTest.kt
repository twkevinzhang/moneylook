package tw.kevinzhang.moneylook.data.transfer

import androidx.room.Room
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvCodec
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvImport
import tw.kevinzhang.core.data.db.CredentialProfileCsvCodec
import tw.kevinzhang.core.data.db.CredentialProfileCsvExtension
import tw.kevinzhang.core.data.db.CredentialProfileCsvFieldDefinition
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Tag

@RunWith(RobolectricTestRunner::class)
class DataTransferRepositoryTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var repository: DataTransferRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = DataTransferRepository(
            database = database,
            autoCategoryRuleDao = database.autoCategoryRuleDao(),
            categoryDao = database.categoryDao(),
            tagDao = database.tagDao(),
            accountDao = database.accountDao(),
            credentialProfileDao = database.credentialProfileDao(),
            installedExtensionDao = database.installedExtensionDao(),
            gson = Gson(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rule export and import preserve details while excluding system defaults`() = runBlocking {
        val category = Category("food", "餐飲", "#123456")
        val tag = Tag("daily", "日常", "#654321")
        database.categoryDao().upsert(category)
        database.tagDao().upsert(tag)
        val detailedRule = AutoCategoryRule(
            id = "coffee",
            name = "咖啡",
            categoryId = category.id,
            priority = 7,
            origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
        )
        val condition = AutoCategoryRuleCondition(
            ruleId = detailedRule.id,
            position = 0,
            field = AutoCategoryRuleConditionField.MERCHANT_NAME,
            matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
            pattern = "咖啡",
        )
        database.autoCategoryRuleDao().upsertWithDetails(
            detailedRule,
            listOf(condition),
            setOf(tag.id),
        )
        val zeroConditionRule = AutoCategoryRule(
            id = "manual-only",
            name = "無條件規則",
            origin = AutoCategoryRuleOrigin.PRIVATE_LEARNED,
        )
        database.autoCategoryRuleDao().upsertWithDetails(
            zeroConditionRule,
            emptyList(),
            emptySet(),
        )
        database.autoCategoryRuleDao().upsert(
            AutoCategoryRule(
                id = "system-default",
                name = "系統預設",
                isDefault = true,
                origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            ),
        )

        val csv = repository.exportAutoRules()
        assertTrue(csv.contains("coffee"))
        assertTrue(csv.contains("manual-only"))
        assertFalse(csv.contains("system-default"))

        database.autoCategoryRuleDao().upsertWithDetails(
            detailedRule.copy(name = "待覆寫"),
            emptyList(),
            emptySet(),
        )
        val prepared = repository.prepareAutoRules(csv)
            as PrepareDataImportResult.Success
        val rules = prepared.value as PreparedDataImport.AutoRules
        assertEquals(2, rules.preview.overwriteCount)
        assertTrue(rules.preview.canCommit)

        repository.commitAutoRules(rules)

        val imported = database.autoCategoryRuleDao().observeAll().first()
            .associateBy { it.rule.id }
        assertEquals(detailedRule, imported.getValue(detailedRule.id).rule)
        assertEquals(listOf(condition), imported.getValue(detailedRule.id).conditions)
        assertEquals(listOf(tag), imported.getValue(detailedRule.id).tags)
        assertTrue(imported.getValue(zeroConditionRule.id).conditions.isEmpty())
        assertNotNull(imported["system-default"])
    }

    @Test
    fun `invalid rule reference prevents every write`() = runBlocking {
        val valid = AutoCategoryRule(
            id = "valid",
            name = "有效規則",
            origin = AutoCategoryRuleOrigin.IMPORTED,
        )
        val invalid = AutoCategoryRule(
            id = "invalid",
            name = "無效規則",
            categoryId = "missing-category",
            origin = AutoCategoryRuleOrigin.IMPORTED,
        )
        val csv = AutoCategoryRuleCsvCodec.encode(
            AutoCategoryRuleCsvImport(
                rules = listOf(valid, invalid),
                conditionsByRuleId = emptyMap(),
            ),
        )
        val prepared = repository.prepareAutoRules(csv)
            as PrepareDataImportResult.Success
        val rules = prepared.value as PreparedDataImport.AutoRules
        assertFalse(rules.preview.canCommit)

        assertTrue(runCatching { repository.commitAutoRules(rules) }.isFailure)
        val ids = database.autoCategoryRuleDao().observeAll().first().map { it.rule.id }
        assertFalse("valid rule must roll back with invalid row", valid.id in ids)
        assertFalse(invalid.id in ids)
    }

    @Test
    fun `credential round trip keeps plaintext and preserves last run status`() = runBlocking {
        val extension = extension("bank")
        database.installedExtensionDao().insert(extension)
        val original = profile(
            extensionId = extension.id,
            username = "alice",
            password = "plain-secret",
            enabled = true,
            cron = "15 6 * * *",
        ).copy(lastRunAt = 100L, lastRunStatus = "success")
        database.credentialProfileDao().upsert(original)

        val csv = repository.exportCredentials()
        assertTrue("password must be exported as plaintext", csv.contains("plain-secret"))

        database.credentialProfileDao().upsert(
            profile(
                extensionId = extension.id,
                username = "changed",
                password = "changed-secret",
                enabled = false,
                cron = "0 8 * * *",
            ).copy(lastRunAt = 999L, lastRunStatus = "newer-status"),
        )
        val prepared = repository.prepareCredentials(csv)
            as PrepareDataImportResult.Success
        val credentials = prepared.value as PreparedDataImport.Credentials
        assertEquals(1, credentials.preview.overwriteCount)
        assertTrue(credentials.preview.canCommit)

        repository.commitCredentials(credentials)

        val imported = database.credentialProfileDao().getByExtensionId(extension.id)!!
        assertTrue(imported.credential.contains("plain-secret"))
        assertEquals(original.scheduleEnabled, imported.scheduleEnabled)
        assertEquals(original.scheduleCron, imported.scheduleCron)
        assertEquals(999L, imported.lastRunAt)
        assertEquals("newer-status", imported.lastRunStatus)
    }

    @Test
    fun `invalid credential row cannot be dropped for a partial commit`() = runBlocking {
        val installed = extension("installed")
        database.installedExtensionDao().insert(installed)
        val existing = profile(
            extensionId = installed.id,
            username = "before",
            password = "before-secret",
            enabled = false,
            cron = "0 7 * * *",
        )
        database.credentialProfileDao().upsert(existing)
        val fields = fields()
        val csv = CredentialProfileCsvCodec.encode(
            listOf(
                CredentialProfileCsvExtension(
                    extensionId = installed.id,
                    extensionName = installed.name,
                    profile = profile(
                        extensionId = installed.id,
                        username = "after",
                        password = "after-secret",
                        enabled = true,
                        cron = "0 9 * * *",
                    ),
                    fields = fields,
                ),
                CredentialProfileCsvExtension(
                    extensionId = "missing::repo",
                    extensionName = "未安裝銀行",
                    profile = profile(
                        extensionId = "missing::repo",
                        username = "missing",
                        password = "missing-secret",
                        enabled = true,
                        cron = "0 10 * * *",
                    ),
                    fields = fields,
                ),
            ),
        )
        val prepared = repository.prepareCredentials(csv)
            as PrepareDataImportResult.Success
        val credentials = prepared.value as PreparedDataImport.Credentials
        assertFalse(credentials.preview.canCommit)
        assertEquals(2, credentials.value.size)

        assertTrue(runCatching { repository.commitCredentials(credentials) }.isFailure)
        assertEquals(existing, database.credentialProfileDao().getByExtensionId(installed.id))
    }

    private fun extension(id: String): InstalledExtension = InstalledExtension(
        id = "$id::repo",
        manifestId = id,
        name = "測試銀行 $id",
        version = 1,
        repoUrl = "https://github.com/example/$id",
        syncTriggerCachePath = "/tmp/$id.js",
        iconUrl = null,
        credentialFieldsJson =
            """[{"key":"username","label":"帳號","type":"text","required":true,"summary":true},""" +
                """{"key":"password","label":"密碼","type":"password","required":true,"summary":false}]""",
    )

    private fun fields(): List<CredentialProfileCsvFieldDefinition> = listOf(
        CredentialProfileCsvFieldDefinition(
            key = "username",
            label = "帳號",
            type = "text",
            required = true,
        ),
        CredentialProfileCsvFieldDefinition(
            key = "password",
            label = "密碼",
            type = "password",
            required = true,
        ),
    )

    private fun profile(
        extensionId: String,
        username: String,
        password: String,
        enabled: Boolean,
        cron: String,
    ): CredentialProfile = CredentialProfile(
        extensionId = extensionId,
        credential = Gson().toJson(
            linkedMapOf(
                "username" to username,
                "password" to password,
            ),
        ),
        scheduleEnabled = enabled,
        scheduleCron = cron,
        timezoneId = "Asia/Taipei",
    )
}
