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
class AiClassificationStoreTest {
    @Test fun guardedWriteProtectsManualAndRollsBackNewCategory() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),MoneylookDatabase::class.java).allowMainThreadQueries().build()
        try {
            val t=Transfer("t","a","e","2026-10-18","咖啡",-10.0,null,"")
            db.transferDao().upsertAll(listOf(t))
            val store=AiClassificationStore(db)
            val proposal=AiCategoryProposal(AiCategorySelection.New("咖啡",CategoryReportingGroup.EXPENSE),"商家")
            try {
                store.guarded(t,null) { store.resolve(proposal); error("database failure") }
                fail("must throw")
            } catch (_: IllegalStateException) { }
            assertTrue(db.categoryDao().observeAll().first().isEmpty())
            db.transferAnnotationDao().upsert(TransferAnnotation("t","e",categoryAssignment=AssignmentSource.MANUAL))
            assertEquals(AutomaticClassificationWriteResult.PRESERVED_MANUAL,store.guarded(t,null) {
                store.resolve(proposal); fail("must not enter write"); AutomaticClassificationWriteResult.APPLIED
            })
            assertTrue(db.categoryDao().observeAll().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun categoryResolutionReusesNormalizedNameAndRejectsGroupConflict() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),MoneylookDatabase::class.java).allowMainThreadQueries().build()
        try {
            val t=Transfer("t","a","e","2026-10-18","test",-1.0,null,"")
            db.transferDao().upsertAll(listOf(t))
            db.categoryDao().upsert(Category("c","Coffee","#000000"))
            val store=AiClassificationStore(db)
            store.guarded(t,null) {
                assertEquals("c",store.resolve(AiCategoryProposal(AiCategorySelection.New("ＣＯＦＦＥＥ",CategoryReportingGroup.EXPENSE),"merchant")))
                AutomaticClassificationWriteResult.APPLIED
            }
            try {
                store.guarded(t,null) { store.resolve(AiCategoryProposal(AiCategorySelection.New("coffee",CategoryReportingGroup.INCOME),"merchant")); AutomaticClassificationWriteResult.APPLIED }
                fail("group conflict accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(CategoryReportingGroup.EXPENSE,db.categoryDao().getById("c")!!.reportingGroup)
        } finally { db.close() }
    }
}
