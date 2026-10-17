package tw.kevinzhang.moneylook.sync

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.*
import tw.kevinzhang.core.data.model.*
import tw.kevinzhang.moneylook.ai.*
import tw.kevinzhang.moneylook.schedule.ClassificationTraceMaintenanceScheduler

@RunWith(RobolectricTestRunner::class)
class AiClassificationBatchTest {
    private fun transaction(id: String) = Transfer(id,"a","e","2026-10-18","咖啡$id",-60.0,null,"")
    private fun categorizer(db: MoneylookDatabase, classifier: AiClassifier) = AutoCategorizer(
        LocalClassificationStore(db), db.transferDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(), db.categoryDao(),
        RoomClassificationTraceStore(db,db.transferAnnotationDao(),db.ingestionProvenanceDao()),
        RoomClassificationCatalogResetStore(db,db.transferAnnotationDao(),db.ingestionProvenanceDao()),
        classificationTraceMaintenanceScheduler = object : ClassificationTraceMaintenanceScheduler { override fun wake() {}; override fun continueAfter(continuation: Int) {} },
        aiClassifier=classifier, aiStore=AiClassificationStore(db),
    )
    private suspend fun fixture(): MoneylookDatabase {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),MoneylookDatabase::class.java).allowMainThreadQueries().build()
        db.accountDao().upsertAll(listOf(Account("a","e","Bank","Account",0.0,"TWD",0)))
        db.categoryDao().upsert(Category("old","舊分類","#000000"))
        db.aiClassificationDao().putState(AiClassificationState())
        return db
    }
    @Test fun firstReplacesDefaultThenOnlyUnclassifiedAndProtectsManual() = runBlocking {
        val db=fixture()
        try {
            db.transferDao().upsertAll(listOf(transaction("old"),transaction("new"),transaction("manual")))
            db.transferAnnotationDao().upsert(TransferAnnotation("old","e","old",autoRuleId="removed-default"))
            db.transferAnnotationDao().upsert(TransferAnnotation("manual","e","old",categoryAssignment=AssignmentSource.MANUAL))
            db.openHelper.writableDatabase.execSQL("INSERT INTO ai_classification_work VALUES('old','old','removed-default',NULL,0)")
            var calls=0
            val classifier=AiClassifier { calls++; AiCategoryProposal(AiCategorySelection.New("咖啡餐飲",CategoryReportingGroup.EXPENSE),"商家") }
            val service=categorizer(db,classifier)
            val first=service.applyToExistingTransactions()
            assertEquals(2,calls)
            assertEquals(0,first.pendingTransferCount)
            assertTrue(db.aiClassificationDao().state()!!.complete)
            assertEquals("old",db.transferAnnotationDao().getByTransferIds(listOf("manual")).single().categoryId)
            service.applyToExistingTransactions()
            assertEquals(2,calls)
            db.transferDao().upsertAll(listOf(transaction("later")))
            service.applyToExistingTransactions()
            // Same learned/user classification mechanisms remain enabled, but these are AI results, not learning samples.
            assertEquals(3,calls)
        } finally { db.close() }
    }
    @Test fun failedConfigurationPreservesOldCategoryAndFirstOpportunity() = runBlocking {
        val db=fixture()
        try {
            db.transferDao().upsertAll(listOf(transaction("old")))
            db.transferAnnotationDao().upsert(TransferAnnotation("old","e","old",autoRuleId="removed"))
            db.openHelper.writableDatabase.execSQL("INSERT INTO ai_classification_work VALUES('old','old','removed',NULL,0)")
            val result=categorizer(db,AiClassifier { throw AiClassificationException(AiFailureCode.SETTINGS) }).applyToExistingTransactions()
            assertTrue(result.pendingTransferCount>0)
            assertFalse(db.aiClassificationDao().state()!!.complete)
            assertEquals("old",db.transferAnnotationDao().getByTransferIds(listOf("old")).single().categoryId)
        } finally { db.close() }
    }
    @Test fun requestCapStopsAt100AndNextClickOnlyFinishesRemaining() = runBlocking {
        val db=fixture()
        try {
            db.transferDao().upsertAll((1..101).map { transaction("item-$it") })
            var calls=0
            val service=categorizer(db,AiClassifier { calls++; AiCategoryProposal(AiCategorySelection.Existing("old"),"商家") })
            val first=service.applyToExistingTransactions()
            assertEquals(100,calls)
            assertEquals(1,first.pendingTransferCount)
            assertNotNull(first.stopReason)
            val second=service.applyToExistingTransactions()
            assertEquals(101,calls)
            assertEquals(0,second.pendingTransferCount)
        } finally { db.close() }
    }
    @Test fun manualEditDuringRequestWinsAndCreatesNoCategory() = runBlocking {
        val db=fixture()
        try {
            db.transferDao().upsertAll(listOf(transaction("race")))
            val service=categorizer(db,AiClassifier {
                db.transferAnnotationDao().upsert(TransferAnnotation("race","e","old",categoryAssignment=AssignmentSource.MANUAL))
                AiCategoryProposal(AiCategorySelection.New("不該建立",CategoryReportingGroup.EXPENSE),"商家")
            })
            service.applyToExistingTransactions()
            assertEquals(listOf("舊分類"),db.categoryDao().allNames())
            assertTrue(db.transferAnnotationDao().getByTransferIds(listOf("race")).single().manualOverride)
        } finally { db.close() }
    }
    @Test fun invalidCategoryGroupRemainsPendingWhileLaterRowsContinue() = runBlocking {
        val db=fixture()
        try {
            db.transferDao().upsertAll(listOf(transaction("a"),transaction("b")))
            var calls=0
            val service=categorizer(db,AiClassifier {
                calls++
                if(calls==1) AiCategoryProposal(AiCategorySelection.New("舊分類",CategoryReportingGroup.INCOME),"分類矛盾")
                else AiCategoryProposal(AiCategorySelection.Existing("old"),"商家")
            })
            val result=service.applyToExistingTransactions()
            assertEquals(2,calls)
            assertEquals(1,result.pendingTransferCount)
            assertEquals("old",db.transferAnnotationDao().getByTransferIds(listOf("b")).single().categoryId)
        } finally { db.close() }
    }
}
