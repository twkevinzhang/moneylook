package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.*
import tw.kevinzhang.core.data.classification.*

@RunWith(RobolectricTestRunner::class)
class LocalClassificationStoreTest {
    private lateinit var db: MoneylookDatabase
    private lateinit var store: LocalClassificationStore
    private val transfer = Transfer("t", "a", "e", "2026-10-18", "晨光咖啡", -90.0, null, "", merchantName = "晨光咖啡")
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoneylookDatabase::class.java).allowMainThreadQueries().build()
        store = LocalClassificationStore(db)
        db.accountDao().upsertAll(listOf(Account("a", "e", "bank", "account", 0.0, "TWD", 0)))
        db.transferDao().upsertAll(listOf(transfer))
        db.categoryDao().upsert(Category("food", "餐飲", "#000000"))
        db.categoryDao().upsert(Category("work", "工作", "#000000"))
    }
    @After fun close() { db.close() }
    private suspend fun save(category: String?, note: String = "") = db.transferAnnotationDao().saveManualAnnotation(
        TransferAnnotation("t", "e", category, note, AssignmentSource.MANUAL), emptySet())
    @Test fun `manual corrections replace examples and note edits do not advance learning`() = runBlocking {
        save("food")
        store.refreshExamples()
        assertEquals("food", store.learned(transfer)?.categoryId)
        val sequence = db.localClassificationDao().example("t")!!.correctionSequence
        save("food", "note")
        assertEquals(sequence, db.localClassificationDao().example("t")!!.correctionSequence)
        save("work")
        assertEquals("work", store.learned(transfer)?.categoryId)
        save(null)
        assertNull(store.learned(transfer))
    }
    @Test fun `automatic and fallback classifications never teach and resume retracts manual example`() = runBlocking {
        db.transferAnnotationDao().upsert(TransferAnnotation("t", "e", "food"))
        store.refreshExamples()
        assertNull(store.history(transfer))
        save("food")
        db.transferAnnotationDao().resumeAutomaticClassification("t")
        store.refreshExamples()
        assertNull(store.history(transfer))
    }
    @Test fun `fallback keeps reporting direction and does not overwrite edited categories`() = runBlocking {
        val ids = store.ensureFallbackCategories()
        assertEquals(CategoryReportingGroup.INCOME, db.categoryDao().getById(ids.getValue(CategoryReportingGroup.INCOME))!!.reportingGroup)
        assertEquals(CategoryReportingGroup.EXPENSE, db.categoryDao().getById(ids.getValue(CategoryReportingGroup.EXPENSE))!!.reportingGroup)
        val old = db.categoryDao().getById(ids.getValue(CategoryReportingGroup.EXPENSE))!!
        db.categoryDao().upsert(old.copy(reportingGroup = CategoryReportingGroup.EXCLUDED))
        val replacement = store.ensureFallbackCategories().getValue(CategoryReportingGroup.EXPENSE)
        assertNotEquals(old.id, replacement)
        assertEquals(CategoryReportingGroup.EXCLUDED, db.categoryDao().getById(old.id)!!.reportingGroup)
    }
}
