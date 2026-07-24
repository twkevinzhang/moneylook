package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration19To20Test {
    @Test
    fun `suggest rules become auto apply and provenance tables are created`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE auto_category_rules (id TEXT NOT NULL PRIMARY KEY, action TEXT NOT NULL)")
                        db.execSQL("INSERT INTO auto_category_rules VALUES ('legacy-suggest', 'SUGGEST')")
                        db.execSQL("INSERT INTO auto_category_rules VALUES ('explicit-abstain', 'ABSTAIN')")
                        db.execSQL(
                            "CREATE TABLE auto_category_rule_sets " +
                                "(id TEXT NOT NULL PRIMARY KEY, contentSha256 TEXT NOT NULL)",
                        )
                        db.execSQL("CREATE TABLE installed_extensions (id TEXT NOT NULL PRIMARY KEY)")
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )
        helper.writableDatabase.use { db ->
            MIGRATION_19_20.migrate(db)
            assertEquals(
                "AUTO_APPLY",
                db.query("SELECT action FROM auto_category_rules WHERE id = 'legacy-suggest'").use {
                    it.moveToFirst(); it.getString(0)
                },
            )
            assertEquals(
                "ABSTAIN",
                db.query("SELECT action FROM auto_category_rules WHERE id = 'explicit-abstain'").use {
                    it.moveToFirst(); it.getString(0)
                },
            )
            assertTrue(tableExists(db, "ingestion_runs"))
            assertTrue(tableExists(db, "transfer_ingestion_events"))
            assertTrue(tableExists(db, "transfer_annotation_events"))
            assertTrue(columnExists(db, "installed_extensions", "artifactSha256"))
            assertTrue(columnExists(db, "transfer_ingestion_events", "payloadFingerprint"))
            assertTrue(columnExists(db, "transfer_ingestion_events", "observation"))
            assertTrue(columnExists(db, "transfer_annotation_events", "trigger"))
            assertTrue(columnExists(db, "transfer_annotation_events", "ruleContentSha256"))
            assertTrue(columnExists(db, "transfer_annotation_events", "tagAddedCount"))
            assertTrue(columnExists(db, "ingestion_runs", "classificationStatus"))
            assertTrue(columnExists(db, "ingestion_runs", "classificationCompletedAt"))
            assertTrue(columnExists(db, "installed_extensions", "artifactRevision"))
            listOf(
                "index_ingestion_runs_extensionId_startedAt",
                "index_ingestion_runs_status",
                "index_transfer_ingestion_events_runId",
                "index_transfer_ingestion_events_transferId",
                "index_transfer_ingestion_events_extensionId_occurredAt",
                "index_transfer_annotation_events_transferId_occurredAt",
                "index_transfer_annotation_events_runId",
                "index_transfer_annotation_events_outcome",
            ).forEach { index -> assertTrue(indexExists(db, index)) }
        }
        helper.close()
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, name: String) =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun columnExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, column: String) =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.any { it == column }
        }

    private fun indexExists(db: androidx.sqlite.db.SupportSQLiteDatabase, index: String) =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index))
            .use { it.moveToFirst() }
}
