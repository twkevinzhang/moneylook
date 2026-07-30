package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.TransferAnnotationEvent

@RunWith(RobolectricTestRunner::class)
class ClassificationCatalogResetStoreTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var store: ClassificationCatalogResetStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomClassificationCatalogResetStore(
            database,
            database.transferAnnotationDao(),
            database.ingestionProvenanceDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `reset atomically restores exact defaults and clears only mutable classification state`() = runBlocking {
        database.accountDao().upsertAll(
            listOf(Account("account", "extension", "Extension", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)),
        )
        database.transferDao().upsertAll(
            listOf(
                Transfer(
                    id = "transfer",
                    accountId = "account",
                    extensionId = "extension",
                    txnDateTime = "2026-07-30T12:00:00",
                    description = "Lunch",
                    amount = -120.0,
                    balance = 1000.0,
                    memo = "raw memo",
                ),
            ),
        )
        database.categoryDao().upsert(
            Category("custom-category", "自訂分類", "#000000", "🧪", CategoryReportingGroup.EXPENSE),
        )
        database.tagDao().upsert(Tag("custom-tag", "自訂標籤", "#000000"))
        database.autoCategoryRuleSetDao().upsert(
            AutoCategoryRuleSet(
                id = "custom-set",
                name = "Custom set",
                origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
                version = "1",
                canonicalizerVersion = "test",
                contentSha256 = "custom",
            ),
        )
        database.autoCategoryRuleDao().upsertWithDetails(
            AutoCategoryRule(
                id = "custom-rule",
                name = "Custom rule",
                categoryId = "custom-category",
                ruleSetId = "custom-set",
            ),
            listOf(
                AutoCategoryRuleCondition(
                    ruleId = "custom-rule",
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.DESCRIPTION,
                    matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                    pattern = "Lunch",
                ),
            ),
            tagIds = setOf("custom-tag"),
        )
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = "transfer",
                extensionId = "extension",
                categoryId = "custom-category",
                note = "保留這段備註",
                categoryAssignment = AssignmentSource.MANUAL,
            ),
            tagIds = setOf("custom-tag"),
        )
        database.transferAnnotationDao().upsert(
            TransferAnnotation(
                transferId = "transfer",
                extensionId = "extension",
                categoryId = "custom-category",
                note = "保留這段備註",
                categoryAssignment = AssignmentSource.MANUAL,
                autoRuleId = "custom-rule",
                autoRuleSetId = "custom-set",
                autoMatchScore = 99,
                classifierVersion = "classifier-v1",
            ),
        )
        database.ingestionProvenanceDao().insertAnnotationEvent(
            TransferAnnotationEvent(
                id = "existing-audit",
                occurredAt = 1L,
                runId = null,
                transferId = "transfer",
                extensionId = "extension",
                trigger = ClassificationTrigger.MANUAL_EDIT,
                outcome = ClassificationOutcome.MANUAL_ASSIGNED,
                previousCategoryId = null,
                newCategoryId = "custom-category",
                ruleId = null,
                ruleSetId = null,
                ruleContentSha256 = null,
                ruleSetContentSha256 = null,
                matchScore = null,
                classifierVersion = null,
            ),
        )

        store.resetToDefaults()

        val expectedRuleDetails = buildMap {
            DefaultClassificationCatalog.publicAutoCategoryRules.forEach { put(it.id, emptyList()) }
            listOf(
                DefaultClassificationCatalog.publicMccRules,
                DefaultClassificationCatalog.publicStructuralRules,
                DefaultClassificationCatalog.publicGenericRules,
            ).flatten().forEach { publicRule -> put(publicRule.rule.id, publicRule.conditions) }
        }
        val expectedRules = buildList {
            addAll(DefaultClassificationCatalog.publicAutoCategoryRules)
            addAll(DefaultClassificationCatalog.publicMccRules.map { it.rule })
            addAll(DefaultClassificationCatalog.publicStructuralRules.map { it.rule })
            addAll(DefaultClassificationCatalog.publicGenericRules.map { it.rule })
        }.associateBy { it.id }
        val actualRules = database.autoCategoryRuleDao().observeAll().first()
        val actualRuleIds = actualRules.map { it.rule.id }.toSet()
        val expectedRuleSetIds = setOf(
            DefaultClassificationCatalog.publicMccRuleSet.id,
            DefaultClassificationCatalog.publicStructuralRuleSet.id,
            DefaultClassificationCatalog.publicGenericRuleSet.id,
        )

        assertEquals(DefaultClassificationCatalog.categories.toSet(), database.categoryDao().observeAll().first().toSet())
        assertEquals(expectedRuleDetails.keys, actualRuleIds)
        assertEquals(expectedRules, actualRules.associate { it.rule.id to it.rule })
        assertEquals(expectedRuleDetails.size, actualRules.size)
        actualRules.forEach { actual ->
            assertEquals(
                expectedRuleDetails.getValue(actual.rule.id).sortedBy { it.position },
                actual.conditions.sortedBy { it.position },
            )
        }
        assertEquals(
            expectedRuleSetIds,
            database.autoCategoryRuleSetDao().getAll().mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            setOf(
                DefaultClassificationCatalog.publicMccRuleSet,
                DefaultClassificationCatalog.publicStructuralRuleSet,
                DefaultClassificationCatalog.publicGenericRuleSet,
            ),
            database.autoCategoryRuleSetDao().getAll().toSet(),
        )
        assertTrue(database.tagDao().observeAll().first().isEmpty())
        assertTrue(database.transferAnnotationDao().getTagCrossRefs("transfer").isEmpty())

        val annotation = database.transferAnnotationDao().getByTransferIds(listOf("transfer")).single()
        assertEquals("transfer", annotation.transferId)
        assertEquals("extension", annotation.extensionId)
        assertEquals("保留這段備註", annotation.note)
        assertNull(annotation.categoryId)
        assertEquals(AssignmentSource.AUTO, annotation.categoryAssignment)
        assertEquals(false, annotation.manualOverride)
        assertNull(annotation.autoRuleId)
        assertNull(annotation.autoRuleSetId)
        assertNull(annotation.autoMatchScore)
        assertNull(annotation.classifierVersion)

        assertEquals(listOf("transfer"), database.transferDao().getAll().map { it.id })
        val auditEvents = database.ingestionProvenanceDao().getTransferAnnotationEvents("transfer")
        assertTrue(auditEvents.any { it.id == "existing-audit" })
        assertTrue(
            auditEvents.any {
                it.trigger == ClassificationTrigger.CATALOG_RESET &&
                    it.outcome == ClassificationOutcome.CATALOG_RESET &&
                    it.previousCategoryId == "custom-category" &&
                    it.newCategoryId == null &&
                    it.tagRemovedCount == 1
            },
        )
    }

    @Test
    fun `reset rolls back every destructive change when default catalog seeding fails`() {
        runBlocking {
            database.categoryDao().upsert(
                Category("custom-category", "自訂分類", "#000000", "🧪", CategoryReportingGroup.EXPENSE),
            )
            database.tagDao().upsert(Tag("custom-tag", "自訂標籤", "#000000"))
            database.autoCategoryRuleDao().upsert(
                AutoCategoryRule(
                    id = "custom-rule",
                    name = "Custom rule",
                    categoryId = "custom-category",
                ),
            )
            database.transferAnnotationDao().upsert(
                TransferAnnotation(
                    transferId = "transfer",
                    extensionId = "extension",
                    categoryId = "custom-category",
                    note = "保留",
                    categoryAssignment = AssignmentSource.MANUAL,
                ),
            )
        }
        val firstDefaultCategoryId = DefaultClassificationCatalog.categories.first().id
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_default_catalog_seed
            BEFORE INSERT ON categories
            WHEN NEW.id = '$firstDefaultCategoryId'
            BEGIN
                SELECT RAISE(ABORT, 'fictional seed failure');
            END
            """.trimIndent(),
        )

        assertThrows(Exception::class.java) {
            runBlocking { store.resetToDefaults() }
        }

        runBlocking {
            assertEquals(
                setOf("custom-category"),
                database.categoryDao().observeAll().first().mapTo(mutableSetOf()) { it.id },
            )
            assertEquals(listOf("custom-tag"), database.tagDao().observeAll().first().map { it.id })
            assertEquals(
                listOf("custom-rule"),
                database.autoCategoryRuleDao().observeAll().first().map { it.rule.id },
            )
            val annotation = database.transferAnnotationDao().getByTransferIds(listOf("transfer")).single()
            assertEquals("custom-category", annotation.categoryId)
            assertEquals(AssignmentSource.MANUAL, annotation.categoryAssignment)
            assertTrue(annotation.manualOverride)
            assertEquals("保留", annotation.note)
            assertTrue(
                database.ingestionProvenanceDao().getTransferAnnotationEvents("transfer")
                    .none { it.trigger == ClassificationTrigger.CATALOG_RESET },
            )
        }
    }
}
