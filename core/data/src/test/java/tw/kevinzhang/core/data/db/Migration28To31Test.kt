package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration28To31Test {
    @Test fun `migration preserves classifications and account numbers without reclassifying`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication()).name(null).callback(object : SupportSQLiteOpenHelper.Callback(28) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY NOT NULL, accountNo TEXT)")
                    db.execSQL("CREATE TABLE transfer_annotations(transferId TEXT PRIMARY KEY NOT NULL, categoryId TEXT)")
                    db.execSQL("INSERT INTO accounts VALUES('a','001234567890')")
                    db.execSQL("INSERT INTO transfer_annotations VALUES('t','manual-category')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            MIGRATION_28_29.migrate(db)
            MIGRATION_29_30.migrate(db)
            MIGRATION_30_31.migrate(db)
            db.query("SELECT accountNo, bankId FROM accounts").use {
                assertTrue(it.moveToFirst()); assertEquals("001234567890", it.getString(0)); assertTrue(it.isNull(1))
            }
            db.query("SELECT categoryId FROM transfer_annotations").use {
                assertTrue(it.moveToFirst()); assertEquals("manual-category", it.getString(0))
            }
        } finally { helper.close() }
    }
}
