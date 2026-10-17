package tw.kevinzhang.core.data.db

import androidx.room.Room
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
    @Test fun `legacy version 29 without bankId upgrades with full Room validation`() = validateVersion29(false)
    @Test fun `version 29 with bankId retains existing bank evidence`() = validateVersion29(true)

    private fun validateVersion29(hasBankId: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-v29-$hasBankId.db"
        context.deleteDatabase(name)
        try {
            val current = Room.databaseBuilder(context, MoneylookDatabase::class.java, name)
                .allowMainThreadQueries().build()
            val db = current.openHelper.writableDatabase
            db.execSQL("INSERT INTO accounts(id,extensionId,extensionName,accountName,balance,currency,lastSyncAt,kind,accountNo,bankId) VALUES('a','e','bank','account',100,'TWD',0,'DEPOSIT','001234567890','013')")
            db.execSQL("INSERT INTO categories(id,name,color,emoji,kind) VALUES('manual-category','manual','#000000','','EXPENSE')")
            db.execSQL("INSERT INTO transfer_annotations(transferId,extensionId,categoryId,note,categoryAssignment,manualOverride) VALUES('t','e','manual-category','keep note','MANUAL',1)")
            if (!hasBankId) {
                val sql = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='accounts'").use {
                    check(it.moveToFirst()); it.getString(0)
                }
                val columns = db.query("PRAGMA table_info(accounts)").use { cursor ->
                    buildList { while (cursor.moveToNext()) if (cursor.getString(1) != "bankId") add("`${cursor.getString(1)}`") }.joinToString(",")
                }
                db.execSQL(sql.replace("`accounts`", "`accounts_legacy`").replace("`bankId` TEXT, ", ""))
                db.execSQL("INSERT INTO accounts_legacy($columns) SELECT $columns FROM accounts")
                db.execSQL("DROP TABLE accounts")
                db.execSQL("ALTER TABLE accounts_legacy RENAME TO accounts")
                db.execSQL("CREATE INDEX index_accounts_extensionId_sourceAccountKey_kind_currency ON accounts(extensionId,sourceAccountKey,kind,currency)")
            }
            listOf("classification_learning_examples", "classification_learning_tokens", "classification_learning_clock",
                "classification_fallback_roles", "classification_local_evidence", "classification_pending").forEach {
                db.execSQL("DROP TABLE $it")
            }
            db.version = 29
            current.close()
            val migrated = Room.databaseBuilder(context, MoneylookDatabase::class.java, name)
                .allowMainThreadQueries().addMigrations(MIGRATION_29_30, MIGRATION_30_31).build()
            try {
                val opened = migrated.openHelper.writableDatabase
                opened.query("SELECT accountNo,bankId FROM accounts WHERE id='a'").use {
                    assertTrue(it.moveToFirst()); assertEquals("001234567890", it.getString(0))
                    if (hasBankId) assertEquals("013", it.getString(1)) else assertTrue(it.isNull(1))
                }
                opened.query("SELECT categoryId,note,manualOverride FROM transfer_annotations WHERE transferId='t'").use {
                    assertTrue(it.moveToFirst()); assertEquals("manual-category", it.getString(0))
                    assertEquals("keep note", it.getString(1)); assertEquals(1, it.getInt(2))
                }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

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
