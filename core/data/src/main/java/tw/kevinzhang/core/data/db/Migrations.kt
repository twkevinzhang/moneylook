package tw.kevinzhang.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonObject
import tw.kevinzhang.core.data.model.LEGACY_CREDENTIAL_FIELDS_JSON

/** Removes obsolete native-login policy columns without deleting installed extensions or credentials. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `credential_profiles_backup` (
                `extensionId` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `password` TEXT NOT NULL,
                `scheduleEnabled` INTEGER NOT NULL,
                `scheduleCron` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                PRIMARY KEY(`extensionId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `credential_profiles_backup` (
                `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            )
            SELECT `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            FROM `credential_profiles`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `credential_profiles`")

        db.execSQL(
            """
            CREATE TABLE `installed_extensions_new` (
                `id` TEXT NOT NULL,
                `manifestId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `repoUrl` TEXT NOT NULL,
                `syncTriggerCachePath` TEXT NOT NULL,
                `iconUrl` TEXT,
                `suggestedScheduleCron` TEXT,
                `suggestedScheduleTimezone` TEXT NOT NULL,
                `suggestedScheduleEnabled` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `installed_extensions_new` (
                `id`, `manifestId`, `name`, `version`, `repoUrl`, `syncTriggerCachePath`,
                `iconUrl`, `suggestedScheduleCron`, `suggestedScheduleTimezone`,
                `suggestedScheduleEnabled`
            )
            SELECT `id`, `manifestId`, `name`, `version`, `repoUrl`, `syncTriggerCachePath`,
                `iconUrl`, `suggestedScheduleCron`, `suggestedScheduleTimezone`,
                `suggestedScheduleEnabled`
            FROM `installed_extensions`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `installed_extensions`")
        db.execSQL("ALTER TABLE `installed_extensions_new` RENAME TO `installed_extensions`")

        db.execSQL(
            """
            CREATE TABLE `credential_profiles` (
                `extensionId` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `password` TEXT NOT NULL,
                `scheduleEnabled` INTEGER NOT NULL,
                `scheduleCron` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                PRIMARY KEY(`extensionId`),
                FOREIGN KEY(`extensionId`) REFERENCES `installed_extensions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `credential_profiles` (
                `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            )
            SELECT `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            FROM `credential_profiles_backup`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `credential_profiles_backup`")
    }
}

/** Replaces fixed username/password columns with extension-defined credential JSON. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `credential_profiles_backup` (
                `extensionId` TEXT NOT NULL,
                `credential` TEXT NOT NULL,
                `scheduleEnabled` INTEGER NOT NULL,
                `scheduleCron` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                PRIMARY KEY(`extensionId`)
            )
            """.trimIndent(),
        )

        db.compileStatement(
            """
            INSERT INTO `credential_profiles_backup` (
                `extensionId`, `credential`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            db.query(
                """
                SELECT `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                    `timezoneId`, `lastRunAt`, `lastRunStatus`
                FROM `credential_profiles`
                """.trimIndent(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val credential = JsonObject().apply {
                        addProperty("username", cursor.getString(1))
                        addProperty("password", cursor.getString(2))
                    }.toString()

                    statement.clearBindings()
                    statement.bindString(1, cursor.getString(0))
                    statement.bindString(2, credential)
                    statement.bindLong(3, cursor.getLong(3))
                    statement.bindString(4, cursor.getString(4))
                    statement.bindString(5, cursor.getString(5))
                    if (cursor.isNull(6)) statement.bindNull(6) else statement.bindLong(6, cursor.getLong(6))
                    if (cursor.isNull(7)) statement.bindNull(7) else statement.bindString(7, cursor.getString(7))
                    statement.executeInsert()
                }
            }
        }
        db.execSQL("DROP TABLE `credential_profiles`")

        db.execSQL(
            """
            CREATE TABLE `installed_extensions_new` (
                `id` TEXT NOT NULL,
                `manifestId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `repoUrl` TEXT NOT NULL,
                `syncTriggerCachePath` TEXT NOT NULL,
                `iconUrl` TEXT,
                `suggestedScheduleCron` TEXT,
                `suggestedScheduleTimezone` TEXT NOT NULL,
                `suggestedScheduleEnabled` INTEGER NOT NULL,
                `credentialFieldsJson` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.compileStatement(
            """
            INSERT INTO `installed_extensions_new` (
                `id`, `manifestId`, `name`, `version`, `repoUrl`, `syncTriggerCachePath`,
                `iconUrl`, `suggestedScheduleCron`, `suggestedScheduleTimezone`,
                `suggestedScheduleEnabled`, `credentialFieldsJson`
            )
            SELECT `id`, `manifestId`, `name`, `version`, `repoUrl`, `syncTriggerCachePath`,
                `iconUrl`, `suggestedScheduleCron`, `suggestedScheduleTimezone`,
                `suggestedScheduleEnabled`, ?
            FROM `installed_extensions`
            """.trimIndent(),
        ).use { statement ->
            statement.bindString(1, LEGACY_CREDENTIAL_FIELDS_JSON)
            statement.executeInsert()
        }
        db.execSQL("DROP TABLE `installed_extensions`")
        db.execSQL("ALTER TABLE `installed_extensions_new` RENAME TO `installed_extensions`")

        db.execSQL(
            """
            CREATE TABLE `credential_profiles` (
                `extensionId` TEXT NOT NULL,
                `credential` TEXT NOT NULL,
                `scheduleEnabled` INTEGER NOT NULL,
                `scheduleCron` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                PRIMARY KEY(`extensionId`),
                FOREIGN KEY(`extensionId`) REFERENCES `installed_extensions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `credential_profiles` (
                `extensionId`, `credential`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            )
            SELECT `extensionId`, `credential`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            FROM `credential_profiles_backup`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `credential_profiles_backup`")
    }
}

/** Adds typed asset semantics without rebuilding tables that hold credential profiles. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'DEPOSIT'")
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `branchName` TEXT")
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `availableCredit` REAL")
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `creditLimit` REAL")
    }
}

/** Makes a transaction's running balance optional and records history-sync completeness per account. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `transferSyncComplete` INTEGER")
        db.execSQL(
            """
            CREATE TABLE `transfers_new` (
                `id` TEXT NOT NULL,
                `accountId` TEXT NOT NULL,
                `extensionId` TEXT NOT NULL,
                `txnDateTime` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `amount` REAL NOT NULL,
                `balance` REAL,
                `memo` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `transfers_new` (`id`, `accountId`, `extensionId`, `txnDateTime`, `description`, `amount`, `balance`, `memo`)
            SELECT `id`, `accountId`, `extensionId`, `txnDateTime`, `description`, `amount`, `balance`, `memo`
            FROM `transfers`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `transfers`")
        db.execSQL("ALTER TABLE `transfers_new` RENAME TO `transfers`")
    }
}

/** Adds opaque cursor identities and optional bank transaction metadata without exposing account numbers. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing rows intentionally remain null: their old account-number cursor cannot safely
        // be converted to an extension-visible opaque key. The next extension sync repopulates it.
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `sourceAccountKey` TEXT")
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `type` TEXT")
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `status` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_accounts_extensionId_sourceAccountKey_kind_currency` " +
                "ON `accounts` (`extensionId`, `sourceAccountKey`, `kind`, `currency`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transfers_accountId_txnDateTime` " +
                "ON `transfers` (`accountId`, `txnDateTime`)",
        )
    }
}

/** Adds user-owned categories, tags, transaction annotations, and automatic classification rules. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `categories` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL COLLATE NOCASE,
                `color` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tags` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL COLLATE NOCASE,
                `color` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")

        // No foreign key to transfers: sync range replacement must preserve this user metadata.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `transfer_annotations` (
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
            "CREATE INDEX IF NOT EXISTS `index_transfer_annotations_extensionId` " +
                "ON `transfer_annotations` (`extensionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transfer_annotations_categoryId` " +
                "ON `transfer_annotations` (`categoryId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `transfer_tag_cross_refs` (
                `transferId` TEXT NOT NULL,
                `tagId` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`transferId`, `tagId`),
                FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transfer_tag_cross_refs_tagId` " +
                "ON `transfer_tag_cross_refs` (`tagId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_category_rules` (
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
                PRIMARY KEY(`id`),
                FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_enabled_priority_id` " +
                "ON `auto_category_rules` (`enabled`, `priority`, `id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_accountId` " +
                "ON `auto_category_rules` (`accountId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_categoryId` " +
                "ON `auto_category_rules` (`categoryId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_category_rule_tag_cross_refs` (
                `ruleId` TEXT NOT NULL,
                `tagId` TEXT NOT NULL,
                PRIMARY KEY(`ruleId`, `tagId`),
                FOREIGN KEY(`ruleId`) REFERENCES `auto_category_rules`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rule_tag_cross_refs_tagId` " +
                "ON `auto_category_rule_tag_cross_refs` (`tagId`)",
        )
    }
}

/** Adds display metadata and exact-description matching without changing existing rule behaviour. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `emoji` TEXT NOT NULL DEFAULT '🏷️'")
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'EXPENSE'")
        db.execSQL(
            "ALTER TABLE `auto_category_rules` ADD COLUMN `descriptionMatchMode` TEXT NOT NULL DEFAULT 'CONTAINS'",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_kind` ON `categories` (`kind`)")
    }
}

/** Adds the date-only index used by global transaction lists and reporting windows. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_txnDateTime` ON `transfers` (`txnDateTime`)")
    }
}

/** Marks bundled rules while preserving user rules and seeds only rules whose category still exists. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `auto_category_rules` ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_isDefault_enabled_priority_id` " +
                "ON `auto_category_rules` (`isDefault`, `enabled`, `priority`, `id`)",
        )
        DefaultClassificationSeeder.seedRulesForExistingCategories(db)
    }
}

/** Retains a bank's settlement date separately from the consumer's transaction date. */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `postingDateTime` TEXT")
    }
}

/** Stores physical-card metadata separately from aggregated credit-card accounts. */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `credit_card_instruments` (
                `id` TEXT NOT NULL,
                `accountId` TEXT NOT NULL,
                `extensionId` TEXT NOT NULL,
                `sourceCardKey` TEXT,
                `panCiphertext` BLOB,
                `panIv` BLOB,
                `panFingerprint` TEXT,
                `maskedPan` TEXT,
                `lastFour` TEXT,
                `displayName` TEXT,
                `network` TEXT,
                `productType` TEXT,
                `holderRole` TEXT,
                `holderName` TEXT,
                `status` TEXT,
                `expiryMonth` INTEGER,
                `expiryYear` INTEGER,
                `creditLimit` REAL,
                `availableCredit` REAL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_instruments_accountId` ON `credit_card_instruments` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_instruments_extensionId_sourceCardKey` ON `credit_card_instruments` (`extensionId`, `sourceCardKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_card_instruments_extensionId_panFingerprint` ON `credit_card_instruments` (`extensionId`, `panFingerprint`)")
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `cardInstrumentId` TEXT")
    }
}

/**
 * Adds Rules v2 without rebuilding historical tables. Existing v1 rules remain executable through
 * their legacy columns and receive a semantically equivalent LEGACY_ANY_TEXT condition.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `merchantName` TEXT")
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `merchantCategoryCode` TEXT")
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `counterpartyName` TEXT")
        db.execSQL("ALTER TABLE `transfers` ADD COLUMN `purpose` TEXT")

        db.execSQL("ALTER TABLE `auto_category_rules` ADD COLUMN `ruleSetId` TEXT")
        db.execSQL("ALTER TABLE `auto_category_rules` ADD COLUMN `extensionId` TEXT")
        db.execSQL("ALTER TABLE `auto_category_rules` ADD COLUMN `accountKind` TEXT")
        db.execSQL(
            "ALTER TABLE `auto_category_rules` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'LEGACY'",
        )
        db.execSQL(
            "ALTER TABLE `auto_category_rules` ADD COLUMN `action` TEXT NOT NULL DEFAULT 'AUTO_APPLY'",
        )
        db.execSQL(
            "UPDATE `auto_category_rules` SET `origin` = " +
                "CASE WHEN `isDefault` = 1 THEN 'PUBLIC_DEFAULT' ELSE 'LEGACY' END",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_ruleSetId` " +
                "ON `auto_category_rules` (`ruleSetId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_extensionId` " +
                "ON `auto_category_rules` (`extensionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_accountKind_extensionId` " +
                "ON `auto_category_rules` (`accountKind`, `extensionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rules_origin_action_enabled_priority_id` " +
                "ON `auto_category_rules` (`origin`, `action`, `enabled`, `priority`, `id`)",
        )

        // RuleSet deliberately has no foreign-key relationship from auto_category_rules.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_category_rule_sets` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `version` TEXT NOT NULL,
                `canonicalizerVersion` TEXT NOT NULL,
                `contentSha256` TEXT NOT NULL,
                `isActive` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rule_sets_origin` " +
                "ON `auto_category_rule_sets` (`origin`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rule_sets_isActive` " +
                "ON `auto_category_rule_sets` (`isActive`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `auto_category_rule_conditions` (
                `ruleId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `conditionGroup` TEXT NOT NULL,
                `field` TEXT NOT NULL,
                `matchMode` TEXT NOT NULL,
                `pattern` TEXT NOT NULL,
                PRIMARY KEY(`ruleId`, `position`),
                FOREIGN KEY(`ruleId`) REFERENCES `auto_category_rules`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rule_conditions_field_matchMode` " +
                "ON `auto_category_rule_conditions` (`field`, `matchMode`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_auto_category_rule_conditions_ruleId_conditionGroup_position` " +
                "ON `auto_category_rule_conditions` (`ruleId`, `conditionGroup`, `position`)",
        )
        db.execSQL(
            """
            INSERT INTO `auto_category_rule_conditions`
                (`ruleId`, `position`, `conditionGroup`, `field`, `matchMode`, `pattern`)
            SELECT `id`, 0, 'INCLUDE_ANY', 'LEGACY_ANY_TEXT', `descriptionMatchMode`, `descriptionContains`
            FROM `auto_category_rules`
            WHERE `descriptionContains` IS NOT NULL AND length(trim(`descriptionContains`)) > 0
            """.trimIndent(),
        )

        db.execSQL("ALTER TABLE `transfer_annotations` ADD COLUMN `autoRuleId` TEXT")
        db.execSQL("ALTER TABLE `transfer_annotations` ADD COLUMN `autoRuleSetId` TEXT")
        db.execSQL("ALTER TABLE `transfer_annotations` ADD COLUMN `autoMatchScore` INTEGER")
        db.execSQL("ALTER TABLE `transfer_annotations` ADD COLUMN `classifierVersion` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transfer_annotations_autoRuleId` " +
                "ON `transfer_annotations` (`autoRuleId`)",
        )

        DefaultClassificationSeeder.seedRulesV2ForExistingCategories(db)
    }
}

/**
 * Restores the full public catalog for the legacy device state where both catalog tables were
 * empty. Any non-empty state is treated as user-owned and deliberately left unchanged.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DefaultClassificationSeeder.seedFullPublicCatalogIfEmpty(db)
    }
}

/**
 * Adds the v3 public generic collection only to an existing public catalog. This migration has
 * no DDL: the collection marker makes the additive insert one-time and protects later deletions.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DefaultClassificationSeeder.seedRulesV3ForExistingPublicCatalog(db)
    }
}

/** Makes every non-abstaining rule immediately actionable and adds private append-only provenance. */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE auto_category_rules SET action = 'AUTO_APPLY' WHERE action = 'SUGGEST'")
        db.execSQL(
            "UPDATE auto_category_rule_sets SET contentSha256 = ? WHERE id = ?",
            arrayOf(
                DefaultClassificationCatalog.publicMccRuleSet.contentSha256,
                DefaultClassificationCatalog.publicMccRuleSet.id,
            ),
        )
        db.execSQL(
            "UPDATE auto_category_rule_sets SET contentSha256 = ? WHERE id = ?",
            arrayOf(
                DefaultClassificationCatalog.publicStructuralRuleSet.contentSha256,
                DefaultClassificationCatalog.publicStructuralRuleSet.id,
            ),
        )
        db.execSQL("ALTER TABLE installed_extensions ADD COLUMN artifactRevision TEXT")
        db.execSQL("ALTER TABLE installed_extensions ADD COLUMN artifactSha256 TEXT")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ingestion_runs (
                id TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, completedAt INTEGER NOT NULL,
                extensionId TEXT NOT NULL, extensionVersion INTEGER NOT NULL, artifactRevision TEXT,
                artifactSha256 TEXT, trigger TEXT NOT NULL, status TEXT NOT NULL,
                classificationStatus TEXT NOT NULL, classificationCompletedAt INTEGER,
                accountCount INTEGER NOT NULL,
                transferCount INTEGER NOT NULL, sourceFingerprint TEXT NOT NULL, fingerprintKeyVersion INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ingestion_runs_extensionId_startedAt ON ingestion_runs(extensionId, startedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ingestion_runs_status ON ingestion_runs(status)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS transfer_ingestion_events (
                id TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, occurredAt INTEGER NOT NULL, transferId TEXT NOT NULL,
                extensionId TEXT NOT NULL, observation TEXT NOT NULL, sourceFingerprint TEXT NOT NULL,
                payloadFingerprint TEXT NOT NULL, fingerprintKeyVersion INTEGER NOT NULL,
                hasDescription INTEGER NOT NULL, hasMemo INTEGER NOT NULL, hasType INTEGER NOT NULL,
                hasMerchantName INTEGER NOT NULL, hasMerchantCategoryCode INTEGER NOT NULL,
                hasCounterpartyName INTEGER NOT NULL, hasPurpose INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_ingestion_events_runId ON transfer_ingestion_events(runId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_ingestion_events_transferId ON transfer_ingestion_events(transferId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_ingestion_events_extensionId_occurredAt ON transfer_ingestion_events(extensionId, occurredAt)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS transfer_annotation_events (
                id TEXT NOT NULL PRIMARY KEY, occurredAt INTEGER NOT NULL, runId TEXT, transferId TEXT NOT NULL,
                extensionId TEXT NOT NULL, trigger TEXT NOT NULL, outcome TEXT NOT NULL,
                previousCategoryId TEXT, newCategoryId TEXT, ruleId TEXT, ruleSetId TEXT,
                ruleContentSha256 TEXT, ruleSetContentSha256 TEXT, matchScore INTEGER, classifierVersion TEXT,
                tagAddedCount INTEGER NOT NULL, tagRemovedCount INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_annotation_events_transferId_occurredAt ON transfer_annotation_events(transferId, occurredAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_annotation_events_runId ON transfer_annotation_events(runId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_annotation_events_outcome ON transfer_annotation_events(outcome)")
    }
}

/** Adds privacy-preserving, append-only sync diagnostics. */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_diagnostics (
                id TEXT NOT NULL PRIMARY KEY, extensionId TEXT NOT NULL, createdAt INTEGER NOT NULL,
                category TEXT NOT NULL, code TEXT, scriptFrame TEXT
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_diagnostics_extensionId_createdAt ON sync_diagnostics (extensionId, createdAt)")
    }
}
