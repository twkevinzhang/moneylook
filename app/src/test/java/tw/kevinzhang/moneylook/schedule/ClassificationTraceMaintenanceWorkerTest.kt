package tw.kevinzhang.moneylook.schedule

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.db.ClassificationTraceDatabaseStats
import tw.kevinzhang.core.data.db.ClassificationTraceMaintenanceBatchResult
import tw.kevinzhang.core.data.db.ClassificationTraceMaintenancePhase
import tw.kevinzhang.core.data.db.ClassificationTraceMaintenanceStore

class ClassificationTraceMaintenanceWorkerTest {

    @Test
    fun `completed cleanup checkpoints once and reports SQLite allocation stats`() = runTest {
        val expectedStats = ClassificationTraceDatabaseStats(
            pageCount = 3_073_671,
            freelistCount = 2_000_000,
            pageSize = 4_096,
            databaseBytes = 12_589_756_416,
            autoVacuum = 0,
            walBusy = 0,
            walLogFrames = 4,
            walCheckpointedFrames = 4,
        )
        val store = FakeMaintenanceStore(
            batches = ArrayDeque(
                listOf(
                    batch(rowsDeleted = 5_000, hasMore = true),
                    batch(rowsDeleted = 37, hasMore = false),
                ),
            ),
            stats = expectedStats,
        )

        val result = ClassificationTraceMaintenanceRunner(store).runWindow(nowMs = { 0L })

        assertEquals(5_037L, result.rowsDeleted)
        assertEquals(2, result.batches)
        assertFalse(result.hasMore)
        assertEquals(expectedStats, result.stats)
        assertEquals(1, store.checkpointCalls)
        assertEquals(
            listOf(5_000 to 5, 5_000 to 5),
            store.pruneArguments,
        )
    }

    @Test
    fun `one work window stops at bounded batch count without checkpointing`() = runTest {
        val store = FakeMaintenanceStore(
            batches = ArrayDeque(
                List(ClassificationTraceMaintenanceRunner.MAX_BATCHES_PER_WINDOW + 1) {
                    batch(rowsDeleted = 5_000, hasMore = true)
                },
            ),
        )

        val result = ClassificationTraceMaintenanceRunner(store).runWindow(nowMs = { 0L })

        assertEquals(ClassificationTraceMaintenanceRunner.MAX_BATCHES_PER_WINDOW, result.batches)
        assertEquals(100_000L, result.rowsDeleted)
        assertTrue(result.hasMore)
        assertNull(result.stats)
        assertEquals(0, store.checkpointCalls)
    }

    @Test
    fun `one work window also stops at elapsed time bound`() = runTest {
        val store = FakeMaintenanceStore(
            batches = ArrayDeque(
                listOf(
                    batch(rowsDeleted = 5_000, hasMore = true),
                    batch(rowsDeleted = 5_000, hasMore = true),
                ),
            ),
        )
        val times = ArrayDeque(
            listOf(
                1_000L,
                1_000L + ClassificationTraceMaintenanceRunner.MAX_WINDOW_MILLIS,
            ),
        )

        val result = ClassificationTraceMaintenanceRunner(store).runWindow(nowMs = { times.removeFirst() })

        assertEquals(1, result.batches)
        assertEquals(5_000L, result.rowsDeleted)
        assertTrue(result.hasMore)
        assertEquals(0, store.checkpointCalls)
    }

    @Test
    fun `continuation ceiling covers measured legacy rows but remains finite`() {
        val minimumRowsPerChain =
            (ClassificationTraceMaintenanceWorker.maxContinuationsPerChain() + 1L) *
                ClassificationTraceMaintenanceRunner.ROWS_PER_BATCH
        val maximumRowsPerChain =
            (ClassificationTraceMaintenanceWorker.maxContinuationsPerChain() + 1L) *
                ClassificationTraceMaintenanceRunner.MAX_BATCHES_PER_WINDOW *
                ClassificationTraceMaintenanceRunner.ROWS_PER_BATCH

        assertTrue(minimumRowsPerChain >= 15_400_000L)
        assertEquals(20_485_000L, minimumRowsPerChain)
        assertEquals(409_700_000L, maximumRowsPerChain)
        assertEquals(5, ClassificationTraceMaintenanceWorker.maxRetryAttempts())
        assertEquals(
            "classification-trace:maintenance",
            ClassificationTraceMaintenanceWorker.uniqueWorkName(),
        )
        assertFalse(
            ClassificationTraceMaintenanceWorker.uniqueWorkName() == BankSyncWorker.uniqueQueueName(),
        )
    }

    @Test
    fun `best effort maintenance enqueue does not propagate scheduler failure`() {
        var attempted = false

        val enqueued = enqueueBestEffort {
            attempted = true
            error("WorkManager unavailable")
        }

        assertTrue(attempted)
        assertFalse(enqueued)
    }

    @Test
    fun `terminal sync remains isolated from maintenance scheduler failure`() {
        val failingScheduler = object : ClassificationTraceMaintenanceScheduler {
            override fun wake() = error("WorkManager unavailable")

            override fun continueAfter(continuation: Int) = Unit
        }

        assertFalse(wakeClassificationTraceMaintenanceAfterTerminal(failingScheduler))
    }

    private class FakeMaintenanceStore(
        private val batches: ArrayDeque<ClassificationTraceMaintenanceBatchResult>,
        private val stats: ClassificationTraceDatabaseStats = ClassificationTraceDatabaseStats(
            pageCount = 0,
            freelistCount = 0,
            pageSize = 4_096,
            databaseBytes = 0,
            autoVacuum = 0,
            walBusy = 0,
            walLogFrames = 0,
            walCheckpointedFrames = 0,
        ),
    ) : ClassificationTraceMaintenanceStore {
        val pruneArguments = mutableListOf<Pair<Int, Int>>()
        var checkpointCalls = 0

        override suspend fun pruneBatch(
            limit: Int,
            retainTerminalExecutions: Int,
            now: Long,
        ): ClassificationTraceMaintenanceBatchResult {
            pruneArguments += limit to retainTerminalExecutions
            return batches.removeFirst()
        }

        override suspend fun checkpointAndStats(): ClassificationTraceDatabaseStats {
            checkpointCalls += 1
            return stats
        }
    }

    private companion object {
        fun batch(rowsDeleted: Int, hasMore: Boolean) = ClassificationTraceMaintenanceBatchResult(
            rowsDeleted = rowsDeleted,
            hasMore = hasMore,
            phase = if (hasMore) {
                ClassificationTraceMaintenancePhase.EXECUTION_CONDITIONS
            } else {
                ClassificationTraceMaintenancePhase.COMPLETE
            },
            executionId = "execution-1".takeIf { hasMore },
        )
    }
}
