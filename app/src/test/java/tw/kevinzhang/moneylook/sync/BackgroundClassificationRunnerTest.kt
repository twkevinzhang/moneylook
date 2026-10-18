package tw.kevinzhang.moneylook.sync

import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class BackgroundClassificationRunnerTest {
    private suspend fun fixture(count: Int): MoneylookDatabase {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoneylookDatabase::class.java)
            .addCallback(MoneylookDatabase.defaultClassificationSeedCallback()).allowMainThreadQueries().build()
        db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
        db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
        db.transferDao().upsertAll((1..count).map { Transfer("t%03d".format(it), "a", "e", "2026-10-18", "合成消費$it", -60.0, null, "") })
        return db
    }
    private fun categorizer(db: MoneylookDatabase, batch: AiBatchClassifier) = AutoCategorizer(
        LocalClassificationStore(db), db.transferDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(), db.categoryDao(),
        RoomClassificationTraceStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
        RoomClassificationCatalogResetStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
        classificationTraceMaintenanceScheduler = object : ClassificationTraceMaintenanceScheduler {
            override fun wake() {}; override fun continueAfter(continuation: Int) {}
        }, aiClassifier = AiClassifier { error("Background must use batch classifier") }, aiStore = AiClassificationStore(db), aiBatchClassifier = batch,
    )
    private fun result(items: List<AiBatchItem>) = AiBatchResult(items.associate {
        it.id to AiCategoryProposal(AiCategorySelection.New("合成分類", CategoryReportingGroup.EXPENSE), "測試")
    }, emptySet())

    @Test fun twentyOneTransactionsUseTwoRequestsAndPersistCompletedJob() = runBlocking {
        val db = fixture(21)
        try {
            val sizes = mutableListOf<Int>()
            val store = ClassificationJobStore(db)
            val job = store.enqueue()!!
            BackgroundClassificationRunner(store, categorizer(db, AiBatchClassifier { sizes.add(it.size); result(it) })).run(job.attemptId!!)
            assertEquals(listOf(20, 1), sizes)
            assertEquals("COMPLETED", store.current().status)
            assertEquals(21, store.current().processed)
            assertTrue(store.pendingIds(store.current().generation).isEmpty())
        } finally { db.close() }
    }

    @Test fun ruleChangeCancelsBatchDiscardsOldResultAndRestartsAllNonManual() = runBlocking {
        val db = fixture(2)
        try {
            val store = ClassificationJobStore(db)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var calls = 0
            val batch = AiBatchClassifier { items ->
                calls++
                if (calls == 1) { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
                result(items)
            }
            val job = store.enqueue()!!
            val running = async(Dispatchers.Default) { BackgroundClassificationRunner(store, categorizer(db, batch)).run(job.attemptId!!) }
            withTimeout(10_000) { entered.await() }
            db.categoryDao().upsert(Category("rule-category", "規則分類", "#607D8B"))
            db.autoCategoryRuleDao().upsert(AutoCategoryRule(id = "r", name = "規則", descriptionContains = "合成消費", categoryId = "rule-category"))
            withTimeout(10_000) { cancelled.await(); running.await() }
            assertEquals(1, calls) // new rule now classifies both locally
            assertEquals("COMPLETED", store.current().status)
            assertEquals(2, store.current().processed)
            assertTrue(db.transferAnnotationDao().getByTransferIds(listOf("t001", "t002")).all { it.categoryId == "rule-category" })
            assertFalse(db.categoryDao().observeAll().first().any { it.name == "合成分類" })
        } finally { db.close() }
    }

    @Test fun partialBatchSavesValidItemsAndDoesNotRetrySameInvalidItem() = runBlocking {
        val db = fixture(2)
        try {
            val store = ClassificationJobStore(db)
            var calls = 0
            val batch = AiBatchClassifier { items -> calls++; AiBatchResult(result(items.take(1)).proposals, setOf(items.last().id)) }
            val job = store.enqueue()!!
            BackgroundClassificationRunner(store, categorizer(db, batch)).run(job.attemptId!!)
            assertEquals(1, calls)
            assertEquals("PAUSED", store.current().status)
            assertEquals(1, store.pendingIds(store.current().generation).size)
            assertEquals(1, store.current().processed)
        } finally { db.close() }
    }

    @Test fun successfulSplitHalfIsSavedEvenIfOtherHalfIsRateLimited() = runBlocking {
        val db = fixture(20)
        try {
            val store = ClassificationJobStore(db)
            var calls = 0
            val batch = AiBatchClassifier { items ->
                calls++
                when (calls) {
                    1 -> throw BatchTooLargeException()
                    2 -> result(items)
                    else -> throw AiClassificationException(AiFailureCode.RATE_LIMIT)
                }
            }
            val job = store.enqueue()!!
            BackgroundClassificationRunner(store, categorizer(db, batch)).run(job.attemptId!!)
            assertEquals(10, store.current().processed)
            assertEquals(10, store.pendingIds(store.current().generation).size)
            assertEquals("PAUSED", store.current().status)
            assertEquals(3, calls)
        } finally { db.close() }
    }

    @Test fun ruleChangeBeforeHttpReturnsCannotTurnRestartIntoPausedCancellation() = runBlocking {
        val db = fixture(2)
        try {
            val store = ClassificationJobStore(db)
            var calls = 0
            val batch = AiBatchClassifier { items ->
                calls++
                if (calls == 1) {
                    db.categoryDao().upsert(Category("r-category", "規則分類", "#607D8B"))
                    db.autoCategoryRuleDao().upsert(AutoCategoryRule(id = "r", name = "規則", descriptionContains = "合成消費", categoryId = "r-category"))
                }
                result(items)
            }
            val job = store.enqueue()!!
            BackgroundClassificationRunner(store, categorizer(db, batch)).run(job.attemptId!!)
            assertEquals("COMPLETED", store.current().status)
            assertFalse(db.categoryDao().observeAll().first().any { it.name == "合成分類" })
        } finally { db.close() }
    }

    @Test fun stoppingCancelsInFlightAndNextAttemptOnlyRequestsRemaining() = runBlocking {
        val db = fixture(21)
        try {
            val store = ClassificationJobStore(db)
            val blocked = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val sizes = mutableListOf<Int>()
            val classifier = AiBatchClassifier { items ->
                sizes.add(items.size)
                if (sizes.size == 2) { blocked.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
                result(items)
            }
            val categorizer = categorizer(db, classifier)
            val job = store.enqueue()!!
            val first = async(Dispatchers.Default) { BackgroundClassificationRunner(store, categorizer).run(job.attemptId!!) }
            withTimeout(10_000) { blocked.await() }
            store.stopping(job.attemptId!!)
            withTimeout(10_000) { cancelled.await() }
            try { first.await() } catch (_: CancellationException) { }
            assertEquals(20, store.current().processed)
            val next = store.enqueue()!!
            BackgroundClassificationRunner(store, categorizer).run(next.attemptId!!)
            assertEquals(listOf(20, 1, 1), sizes)
            assertEquals("COMPLETED", store.current().status)
        } finally { db.close() }
    }
}
