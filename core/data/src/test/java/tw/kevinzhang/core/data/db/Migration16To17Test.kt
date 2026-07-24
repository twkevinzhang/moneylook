package tw.kevinzhang.core.data.db

import androidx.room.Room
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
class Migration16To17Test {
    private val databaseName = "migration-16-17-test.db"

    @Before @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration preserves fictional v16 rows and creates legacy structured conditions`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE transfers (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, extensionId TEXT NOT NULL, txnDateTime TEXT NOT NULL, description TEXT NOT NULL, amount REAL NOT NULL, balance REAL, memo TEXT NOT NULL, type TEXT, status TEXT, postingDateTime TEXT, cardInstrumentId TEXT)")
                        db.execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, emoji TEXT NOT NULL, kind TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE auto_category_rules (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, descriptionContains TEXT, direction TEXT NOT NULL, minAbsoluteAmount REAL, maxAbsoluteAmount REAL, accountId TEXT, categoryId TEXT, enabled INTEGER NOT NULL, priority INTEGER NOT NULL, descriptionMatchMode TEXT NOT NULL, isDefault INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE transfer_annotations (transferId TEXT NOT NULL PRIMARY KEY, extensionId TEXT NOT NULL, categoryId TEXT, note TEXT NOT NULL, categoryAssignment TEXT NOT NULL, manualOverride INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO transfers (id, accountId, extensionId, txnDateTime, description, amount, memo) VALUES ('t', 'a', 'e', '2026-01-01', 'fictional', -12.0, '')")
            db.execSQL("INSERT INTO auto_category_rules VALUES ('legacy', 'Legacy', 'fictional phrase', 'EXPENSE', NULL, NULL, NULL, NULL, 1, 1, 'CONTAINS', 0)")
            db.execSQL("INSERT INTO auto_category_rules VALUES ('public', 'Public', NULL, 'EXPENSE', NULL, NULL, NULL, NULL, 1, 1, 'CONTAINS', 1)")
            MIGRATION_16_17.migrate(db)
            db.query("SELECT merchantName, merchantCategoryCode, counterpartyName, purpose FROM transfers WHERE id = 't'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue((0..3).all(cursor::isNull))
            }
            db.query("SELECT origin, action FROM auto_category_rules WHERE id = 'legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("LEGACY", cursor.getString(0)); assertEquals("AUTO_APPLY", cursor.getString(1))
            }
            db.query("SELECT field, matchMode, pattern FROM auto_category_rule_conditions WHERE ruleId = 'legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("LEGACY_ANY_TEXT", cursor.getString(0)); assertEquals("CONTAINS", cursor.getString(1)); assertEquals("fictional phrase", cursor.getString(2))
            }
            db.query("SELECT origin FROM auto_category_rules WHERE id = 'public'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("PUBLIC_DEFAULT", cursor.getString(0))
            }
        }
        helper.close()
    }

    @Test
    fun `migration result passes the complete Room v17 schema validation`() {
        val context = RuntimeEnvironment.getApplication()
        Room.databaseBuilder(context, MoneylookDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
            .also { database ->
                database.openHelper.writableDatabase
                database.close()
            }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use(::downgradeV17SchemaToV16)
        helper.close()

        Room.databaseBuilder(context, MoneylookDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()
            .also { database ->
                val migrated = database.openHelper.writableDatabase
                assertEquals(17, migrated.version)
                assertTrue(tableExists(migrated, "auto_category_rule_sets"))
                assertTrue(tableExists(migrated, "auto_category_rule_conditions"))
                database.close()
            }
    }

    /**
     * A database created by current Room is a complete schema fixture. Rebuilding only the three
     * changed tables and removing v17-only tables yields the exact v16 shape while retaining every
     * unrelated table, index, and foreign key for Room's end-to-end migration validation.
     */
    private fun downgradeV17SchemaToV16(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = OFF")
        db.execSQL("DROP TABLE `auto_category_rule_conditions`")
        db.execSQL("DROP TABLE `auto_category_rule_sets`")

        db.execSQL(
            """
            CREATE TABLE `transfers_v16` (
                `id` TEXT NOT NULL,
                `accountId` TEXT NOT NULL,
                `extensionId` TEXT NOT NULL,
                `txnDateTime` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `amount` REAL NOT NULL,
                `balance` REAL,
                `memo` TEXT NOT NULL,
                `type` TEXT,
                `status` TEXT,
                `postingDateTime` TEXT,
                `cardInstrumentId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `transfers_v16`
                (`id`, `accountId`, `extensionId`, `txnDateTime`, `description`, `amount`,
                    `balance`, `memo`, `type`, `status`, `postingDateTime`, `cardInstrumentId`)
            SELECT `id`, `accountId`, `extensionId`, `txnDateTime`, `description`, `amount`,
                `balance`, `memo`, `type`, `status`, `postingDateTime`, `cardInstrumentId`
            FROM `transfers`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `transfers`")
        db.execSQL("ALTER TABLE `transfers_v16` RENAME TO `transfers`")
        db.execSQL(
            "CREATE INDEX `index_transfers_accountId_txnDateTime` " +
                "ON `transfers` (`accountId`, `txnDateTime`)",
        )
        db.execSQL(
            "CREATE INDEX `index_transfers_txnDateTime` ON `transfers` (`txnDateTime`)",
        )

        db.execSQL(
            """
            CREATE TABLE `transfer_annotations_v16` (
                `transferId` TEXT NOT NULL,
                `extensionId` TEXT NOT NULL,
                `categoryId` TEXT,
                `note` TEXT NOT NULL,
                `categoryAssignment` TEXT NOT NULL,
                `manualOverride` INTEGER NOT NULL,
                PRIMARY KEY(`transferId`),
                FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `transfer_annotations_v16`
                (`transferId`, `extensionId`, `categoryId`, `note`, `categoryAssignment`,
                    `manualOverride`)
            SELECT `transferId`, `extensionId`, `categoryId`, `note`, `categoryAssignment`,
                `manualOverride`
            FROM `transfer_annotations`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `transfer_annotations`")
        db.execSQL(
            "ALTER TABLE `transfer_annotations_v16` RENAME TO `transfer_annotations`",
        )
        db.execSQL(
            "CREATE INDEX `index_transfer_annotations_extensionId` " +
                "ON `transfer_annotations` (`extensionId`)",
        )
        db.execSQL(
            "CREATE INDEX `index_transfer_annotations_categoryId` " +
                "ON `transfer_annotations` (`categoryId`)",
        )

        db.execSQL(
            """
            CREATE TABLE `auto_category_rules_v16` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `descriptionContains` TEXT,
                `direction` TEXT NOT NULL,
                `minAbsoluteAmount` REAL,
                `maxAbsoluteAmount` REAL,
                `accountId` TEXT,
                `categoryId` TEXT,
                `enabled` INTEGER NOT NULL,
                `priority` INTEGER NOT NULL,
                `descriptionMatchMode` TEXT NOT NULL,
                `isDefault` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `auto_category_rules_v16`
                (`id`, `name`, `descriptionContains`, `direction`, `minAbsoluteAmount`,
                    `maxAbsoluteAmount`, `accountId`, `categoryId`, `enabled`, `priority`,
                    `descriptionMatchMode`, `isDefault`)
            SELECT `id`, `name`, `descriptionContains`, `direction`, `minAbsoluteAmount`,
                `maxAbsoluteAmount`, `accountId`, `categoryId`, `enabled`, `priority`,
                `descriptionMatchMode`, `isDefault`
            FROM `auto_category_rules`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `auto_category_rules`")
        db.execSQL("ALTER TABLE `auto_category_rules_v16` RENAME TO `auto_category_rules`")
        db.execSQL(
            "CREATE INDEX `index_auto_category_rules_enabled_priority_id` " +
                "ON `auto_category_rules` (`enabled`, `priority`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX `index_auto_category_rules_isDefault_enabled_priority_id` " +
                "ON `auto_category_rules` (`isDefault`, `enabled`, `priority`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX `index_auto_category_rules_accountId` " +
                "ON `auto_category_rules` (`accountId`)",
        )
        db.execSQL(
            "CREATE INDEX `index_auto_category_rules_categoryId` " +
                "ON `auto_category_rules` (`categoryId`)",
        )

        db.execSQL("PRAGMA user_version = 16")
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name),
        ).use { it.moveToFirst() }
}
