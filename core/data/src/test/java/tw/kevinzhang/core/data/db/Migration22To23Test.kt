package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration22To23Test {
    @Test
    fun `adds nullable complete failure diagnostics without replacing existing runs`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                """CREATE TABLE ingestion_runs (
                    id TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL,
                    completedAt INTEGER NOT NULL, extensionId TEXT NOT NULL,
                    extensionVersion INTEGER NOT NULL, artifactRevision TEXT,
                    artifactSha256 TEXT, trigger TEXT NOT NULL, status TEXT NOT NULL,
                    classificationStatus TEXT NOT NULL, classificationCompletedAt INTEGER,
                    accountCount INTEGER NOT NULL, transferCount INTEGER NOT NULL,
                    sourceFingerprint TEXT NOT NULL, fingerprintKeyVersion INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL(
                """INSERT INTO ingestion_runs VALUES (
                    'existing', 1, 2, 'bank', 7, NULL, NULL, 'USER_SYNC', 'SUCCESS',
                    'COMPLETE', 2, 1, 3, 'fingerprint', 1
                )""".trimIndent(),
            )

            MIGRATION_22_23.migrate(db)

            val columns = db.query("PRAGMA table_info(`ingestion_runs`)").use { cursor ->
                buildMap {
                    val name = cursor.getColumnIndexOrThrow("name")
                    val notNull = cursor.getColumnIndexOrThrow("notnull")
                    while (cursor.moveToNext()) put(cursor.getString(name), cursor.getInt(notNull))
                }
            }
            assertTrue(
                columns.keys.containsAll(
                    setOf(
                        "failureOrigin",
                        "failureCode",
                        "failureMessage",
                        "failureStack",
                        "failureDiagnosticJson",
                        "failureScriptFrame",
                    ),
                ),
            )
            assertTrue(columns.filterKeys { it.startsWith("failure") }.values.all { it == 0 })
            db.query(
                """SELECT id, failureOrigin, failureMessage, failureDiagnosticJson
                    FROM ingestion_runs WHERE id = 'existing'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
            }
        }
        helper.close()
    }
}
