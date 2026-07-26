package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration21To22Test {
    @Test
    fun `adds reconciliation columns and complete traceability tables`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL("CREATE TABLE transfers (id TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE credit_card_instruments (id TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE auto_category_rule_sets (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, origin TEXT NOT NULL, version TEXT NOT NULL, canonicalizerVersion TEXT NOT NULL, contentSha256 TEXT NOT NULL, isActive INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE auto_category_rules (id TEXT NOT NULL PRIMARY KEY, ruleSetId TEXT, priority INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE auto_category_rule_conditions (ruleId TEXT NOT NULL, position INTEGER NOT NULL, field TEXT NOT NULL, PRIMARY KEY(ruleId, position))")

            MIGRATION_21_22.migrate(db)

            val columns = db.query("PRAGMA table_info(`transfers`)").use { cursor ->
                buildSet {
                    val name = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(name))
                }
            }
            assertTrue(columns.containsAll(setOf(
                "authorizationDateTime", "transactionCode", "feeCurrency",
                "originalTransactionSourceId", "sourceFieldsJson",
            )))
            listOf(
                "source_documents",
                "transfer_field_observations",
                "classification_rule_evaluations",
                "classification_condition_evaluations",
            ).forEach { table ->
                assertTrue(
                    db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'")
                        .use { it.moveToFirst() },
                )
            }
            val sourceColumns = db.query("PRAGMA table_info(`source_documents`)").use { cursor ->
                buildMap {
                    val name = cursor.getColumnIndexOrThrow("name")
                    val notNull = cursor.getColumnIndexOrThrow("notnull")
                    while (cursor.moveToNext()) put(cursor.getString(name), cursor.getInt(notNull))
                }
            }
            assertTrue("representation" in sourceColumns)
            assertTrue(sourceColumns["statusCode"] == 0)
            val observationColumns =
                db.query("PRAGMA table_info(`transfer_field_observations`)").use { cursor ->
                    buildSet {
                        val name = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(name))
                    }
                }
            assertTrue("sourceFieldJson" in observationColumns)
        }
        helper.close()
    }
}
