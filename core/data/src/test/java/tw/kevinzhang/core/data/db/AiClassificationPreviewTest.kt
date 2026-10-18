package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.*

@RunWith(RobolectricTestRunner::class)
class AiClassificationPreviewTest {
    private suspend fun fixture(): MoneylookDatabase {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoneylookDatabase::class.java)
            .allowMainThreadQueries().build()
        db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
        db.aiClassificationDao().putState(AiClassificationState())
        return db
    }
    private fun transaction(id: String) = Transfer(id, "a", "e", "2026-10-18", "咖啡", -60.0, null, "")
    private fun legacy(db: MoneylookDatabase, id: String) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO ai_classification_work VALUES(?,NULL,'removed',NULL,0)", arrayOf(id))
    }

    @Test fun legacyAndUncategorizedAreDeduplicated() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transaction("both"), transaction("new")))
            legacy(db, "both")
            val preview = AiClassificationStore(db).observePreview().first()
            assertEquals(2, preview.pendingTransferCount)
            assertTrue(preview.firstUpgradePending)
        } finally { db.close() }
    }

    @Test fun completedUpgradeIgnoresLegacyWork() = runBlocking {
        val db = fixture()
        try {
            legacy(db, "old")
            db.transferDao().upsertAll(listOf(transaction("new")))
            db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
            val preview = AiClassificationStore(db).observePreview().first()
            assertEquals(1, preview.pendingTransferCount)
            assertFalse(preview.firstUpgradePending)
        } finally { db.close() }
    }

    @Test fun manualClearIsExcluded() = runBlocking {
        val db = fixture()
        try {
            db.transferDao().upsertAll(listOf(transaction("manual")))
            db.transferAnnotationDao().upsert(TransferAnnotation("manual", "e", categoryAssignment = AssignmentSource.MANUAL))
            assertEquals(0, AiClassificationStore(db).observePreview().first().pendingTransferCount)
        } finally { db.close() }
    }

    @Test fun newTransferUpdatesZeroPreview() = runBlocking {
        val db = fixture()
        try {
            val store = AiClassificationStore(db)
            val received = CompletableDeferred<Unit>()
            val updated = async(Dispatchers.Default) {
                withTimeout(10_000) { store.observePreview().first {
                    if (it.pendingTransferCount == 0) received.complete(Unit)
                    it.pendingTransferCount == 1
                } }
            }
            withTimeout(10_000) { received.await() }
            db.transferDao().upsertAll(listOf(transaction("new")))
            assertEquals(1, updated.await().pendingTransferCount)
        } finally { db.close() }
    }

    @Test fun observingDoesNotStartUpgrade() = runBlocking {
        val db = fixture()
        try {
            val preview = AiClassificationStore(db).observePreview().first()
            assertFalse(preview.upgradeStarted)
            assertEquals(AiClassificationState(), db.aiClassificationDao().state())
        } finally { db.close() }
    }
}
