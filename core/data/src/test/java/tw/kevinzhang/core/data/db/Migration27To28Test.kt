package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration27To28Test {
    private val databaseName = "migration-27-28-test.db"

    @Before @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration adds bounded trace schema without deleting legacy detail`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE classification_rule_evaluations (
                                id TEXT NOT NULL PRIMARY KEY, runId TEXT, transferId TEXT NOT NULL,
                                extensionId TEXT NOT NULL, evaluatedAt INTEGER NOT NULL,
                                trigger TEXT NOT NULL, ruleId TEXT NOT NULL, ruleSetId TEXT,
                                ruleContentSha256 TEXT, scopeMatched INTEGER NOT NULL,
                                conditionsMatched INTEGER NOT NULL, categoryCompatible INTEGER NOT NULL,
                                matched INTEGER NOT NULL, selected INTEGER NOT NULL, score INTEGER,
                                reasonCode TEXT NOT NULL, classifierVersion TEXT NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE transfer_annotation_events (
                                id TEXT NOT NULL PRIMARY KEY, occurredAt INTEGER NOT NULL, runId TEXT,
                                transferId TEXT NOT NULL, extensionId TEXT NOT NULL, trigger TEXT NOT NULL,
                                outcome TEXT NOT NULL, previousCategoryId TEXT, newCategoryId TEXT,
                                ruleId TEXT, ruleSetId TEXT, ruleContentSha256 TEXT,
                                ruleSetContentSha256 TEXT, matchScore INTEGER, classifierVersion TEXT,
                                tagAddedCount INTEGER NOT NULL, tagRemovedCount INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO classification_rule_evaluations VALUES " +
                    "('rule-eval','run','transfer','ext',1,'INGESTION','rule',NULL,NULL,1,1,1,1,1,1,'SELECTED','v')",
            )
            db.execSQL(
                "INSERT INTO transfer_annotation_events VALUES " +
                    "('event',1,'run','transfer','ext','INGESTION','AUTO_APPLIED',NULL,'category','rule',NULL,NULL,NULL,1,'v',0,0)",
            )

            MIGRATION_27_28.migrate(db)

            assertEquals(1, count(db, "classification_rule_evaluations"))
            assertEquals(1, count(db, "transfer_annotation_events"))
            assertEquals(0, count(db, "classification_executions"))
            db.query(
                "SELECT classificationExecutionId, evaluatedRuleCount, detailState " +
                    "FROM transfer_annotation_events WHERE id = 'event'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("LEGACY_FULL", cursor.getString(2))
            }
            db.query("SELECT classificationExecutionId FROM classification_rule_evaluations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        }
        helper.close()
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
