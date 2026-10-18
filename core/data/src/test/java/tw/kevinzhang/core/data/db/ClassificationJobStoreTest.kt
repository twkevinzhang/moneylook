package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.*

@RunWith(RobolectricTestRunner::class)
class ClassificationJobStoreTest {
    @Test fun interruptedAttemptCannotAcquireAnotherAutomaticBudget() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transfer("new")))
            val store = ClassificationJobStore(db)
            val first = store.enqueue()!!.attemptId!!
            assertTrue(store.claimAttempt(first))
            assertFalse(ClassificationJobStore(db).claimAttempt(first))
            store.pause(first, "程序中止")
            val next = store.enqueue()!!.attemptId!!
            assertNotEquals(first, next)
            assertTrue(store.claimAttempt(next))
            assertFalse(store.claimAttempt(first))
        } finally { db.close() }
    }
    private suspend fun fixture(): MoneylookDatabase {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoneylookDatabase::class.java)
            .addCallback(MoneylookDatabase.defaultClassificationSeedCallback()).allowMainThreadQueries().build()
        db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
        db.categoryDao().upsert(Category("old", "舊類別", "#607D8B"))
        db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
        return db
    }
    private fun transfer(id: String) = Transfer(id, "a", "e", "2026-10-18", "交易$id", -60.0, null, "")

    @Test fun ordinaryStartIncludesOnlyUncategorizedAndCannotQueueTwice() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transfer("old"), transfer("new"), transfer("manual")))
            db.transferAnnotationDao().upsert(TransferAnnotation("old", "e", "old"))
            db.transferAnnotationDao().upsert(TransferAnnotation("manual", "e", categoryAssignment = AssignmentSource.MANUAL))
            val store = ClassificationJobStore(db)
            assertEquals(1, store.preview().pendingTransferCount)
            val job = store.enqueue()!!
            assertNull(store.enqueue())
            store.prepare(job.attemptId!!)
            assertEquals(listOf("new"), store.pendingIds(job.generation))
        } finally { db.close() }
    }

    @Test fun resumeIncludesNewPendingWithoutReopeningCommittedItems() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transfer("done"), transfer("pending")))
            val store = ClassificationJobStore(db)
            val first = store.enqueue()!!
            store.prepare(first.attemptId!!)
            store.guarded(first.generation, "done") {
                db.transferAnnotationDao().upsert(TransferAnnotation("done", "e", "old"))
            }
            store.pause(first.attemptId, "停止")
            db.transferDao().upsertAll(listOf(transfer("imported")))
            assertEquals(2, store.preview().pendingTransferCount)
            val resumed = store.enqueue()!!
            val prepared = store.prepare(resumed.attemptId!!)
            assertEquals(listOf("imported", "pending"), store.pendingIds(prepared.generation))
            assertEquals(3, prepared.total)
            assertEquals(1, prepared.processed)
        } finally { db.close() }
    }

    @Test fun ruleMutationRestartsWholeLedgerAndRejectsOldGeneration() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transfer("old"), transfer("new"), transfer("manual")))
            db.transferAnnotationDao().upsert(TransferAnnotation("old", "e", "old"))
            db.transferAnnotationDao().upsert(TransferAnnotation("manual", "e", "old", categoryAssignment = AssignmentSource.MANUAL))
            val store = ClassificationJobStore(db)
            val job = store.enqueue()!!
            store.prepare(job.attemptId!!)
            store.guarded(job.generation, "new") { db.transferAnnotationDao().upsert(TransferAnnotation("new", "e", "old")) }
            db.autoCategoryRuleDao().upsert(AutoCategoryRule(id = "user-rule", name = "規則", descriptionContains = "交易", categoryId = "old"))
            val changed = store.current()
            assertTrue(changed.active)
            assertTrue(changed.fullReclassification)
            assertTrue(changed.generation > job.generation)
            assertEquals(0, changed.processed)
            var entered = false
            try { store.guarded(job.generation, "new") { entered = true }; fail("Old generation must fail") }
            catch (_: StaleClassificationGenerationException) { }
            assertFalse(entered)
            store.prepare(job.attemptId)
            assertEquals(listOf("new", "old"), store.pendingIds(changed.generation))
            assertTrue(db.aiClassificationDao().state()!!.complete)
        } finally { db.close() }
    }

    @Test fun pausedProgressSurvivesRecreationAndDeleteRuleAlsoRestarts() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transfer("a"), transfer("b")))
            val store = ClassificationJobStore(db)
            val job = store.enqueue()!!
            store.prepare(job.attemptId!!)
            store.guarded(job.generation, "a") { }
            store.pause(job.attemptId, "停止")
            assertEquals(listOf("b"), ClassificationJobStore(db).pendingIds(job.generation))
            db.autoCategoryRuleDao().upsert(AutoCategoryRule(id = "rule", name = "規則"))
            val before = store.current().generation
            db.autoCategoryRuleDao().deleteById("rule")
            assertTrue(store.current().generation > before)
            assertFalse(store.current().active)
            assertEquals(2, store.preview().pendingTransferCount)
        } finally { db.close() }
    }
}
