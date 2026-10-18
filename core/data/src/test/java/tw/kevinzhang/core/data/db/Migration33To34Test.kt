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
class Migration33To34Test {
    @Test fun upgradeKeepsAnnotationsAndInstallsDurableInvalidation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "classification-jobs-migration.db"
        context.deleteDatabase(name)
        try {
            val old = Room.databaseBuilder(context, MoneylookDatabase::class.java, name).allowMainThreadQueries().build()
            old.categoryDao().upsert(Category("keep", "保留分類", "#607D8B"))
            old.transferAnnotationDao().upsert(TransferAnnotation("manual", "e", "keep", "保留備註", AssignmentSource.MANUAL))
            old.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
            val sql = old.openHelper.writableDatabase
            sql.execSQL("DROP TABLE classification_jobs")
            sql.execSQL("DROP TABLE classification_job_items")
            sql.version = 33
            old.close()
            val migrated = Room.databaseBuilder(context, MoneylookDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(MIGRATION_33_34).build()
            try {
                assertEquals("保留備註", migrated.transferAnnotationDao().getByTransferIds(listOf("manual")).single().note)
                assertTrue(migrated.aiClassificationDao().state()!!.complete)
                val jobs = ClassificationJobStore(migrated)
                assertEquals(0L, jobs.current().generation)
                migrated.autoCategoryRuleDao().upsert(AutoCategoryRule(id = "r", name = "新增規則"))
                assertTrue(jobs.current().fullReclassification)
                assertTrue(jobs.current().generation > 0)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
