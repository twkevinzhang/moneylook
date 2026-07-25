package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration20To21Test {
    @Test
    fun `creates append only privacy safe sync diagnostics table`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )
        helper.writableDatabase.use { db ->
            MIGRATION_20_21.migrate(db)
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sync_diagnostics'")
                .use { it.moveToFirst() })
            assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = 'index_sync_diagnostics_extensionId_createdAt'")
                .use { it.moveToFirst() })
        }
        helper.close()
    }
}
