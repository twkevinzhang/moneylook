package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.*

@RunWith(RobolectricTestRunner::class)
class Migration32To33Test {
    @Test fun savesDefaultSourcesWithoutChangingManualOrUserRules() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "ai-upgrade-fixture.db"
        context.deleteDatabase(name)
        try {
            val initial = Room.databaseBuilder(context, MoneylookDatabase::class.java, name).allowMainThreadQueries().build()
            DefaultClassificationSeeder.seedFreshDatabase(initial.openHelper.writableDatabase)
            seedLegacyRules(initial)
            initial.categoryDao().upsert(Category("food", "本機餐飲", "#000000"))
            initial.categoryDao().upsert(Category("expense-insurance", "保險", "#000000"))
            initial.autoCategoryRuleDao().upsert(AutoCategoryRule("default", "預設", categoryId="food", isDefault=true))
            initial.autoCategoryRuleDao().upsert(AutoCategoryRule("user", "使用者", categoryId="food"))
            initial.autoCategoryRuleDao().upsert(DefaultClassificationCatalog.publicAutoCategoryRules.first { it.id=="public-rule-002" }.copy(name="我的外送",categoryId="food"))
            initial.autoCategoryRuleSetDao().upsert(AutoCategoryRuleSet("public-set","舊預設集",AutoCategoryRuleOrigin.PUBLIC_DEFAULT,"1","1","hash"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("old", "e", "food", autoRuleId="default"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("user-result", "e", "food", autoRuleId="user"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("manual", "e", "food", categoryAssignment=AssignmentSource.MANUAL))
            initial.transferAnnotationDao().upsert(TransferAnnotation("orphan", "e", "food", autoRuleId="removed-default",autoRuleSetId="public-set"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("user-in-public-set", "e", "food", autoRuleId="user",autoRuleSetId="public-set"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("edited", "e", "food", autoRuleId="public-rule-002"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("retired", "e", "expense-insurance", autoRuleId="public-rule-003"))
            initial.autoCategoryRuleDao().deleteById("public-rule-005")
            initial.transferAnnotationDao().upsert(TransferAnnotation("deleted-current", "e", "expense-internet", autoRuleId="public-rule-005"))
            initial.transferAnnotationDao().upsert(TransferAnnotation("old-own", "e", "transfer-account"))
            initial.openHelper.writableDatabase.execSQL("INSERT INTO classification_local_evidence VALUES('old-own','OWN_ACCOUNT','OWNED_ACCOUNT_MATCH')")
            val sql = initial.openHelper.writableDatabase
            sql.execSQL("DROP TABLE ai_classification_state")
            sql.execSQL("DROP TABLE ai_classification_work")
            sql.version = 32
            initial.close()
            val db = Room.databaseBuilder(context, MoneylookDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(MIGRATION_32_33, MIGRATION_33_34).build()
            try {
                assertEquals(listOf("deleted-current","old","old-own","orphan","retired"), db.aiClassificationDao().pending())
                val rules=db.autoCategoryRuleDao().observeAll().first()
                assertEquals(setOf("user","public-rule-002"), rules.map { it.rule.id }.toSet())
                assertFalse(rules.first { it.rule.id=="public-rule-002" }.rule.isDefault)
                assertEquals("food", db.transferAnnotationDao().getByTransferIds(listOf("old")).single().categoryId)
                assertFalse(db.aiClassificationDao().state()!!.complete)
                assertFalse(db.aiClassificationDao().state()!!.started)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
