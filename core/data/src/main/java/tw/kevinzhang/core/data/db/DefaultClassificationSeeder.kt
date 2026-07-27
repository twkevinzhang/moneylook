package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet
import tw.kevinzhang.core.data.model.Category

/** Writes only absent public rows; the one-time completely empty catalog repair is documented below. */
object DefaultClassificationSeeder {
    fun seedFreshDatabase(db: SupportSQLiteDatabase) {
        seedFullPublicCatalogIfEmpty(db)
        seedRulesV3ForExistingCategories(db)
    }

    /**
     * V22 broadens merchant/brand rules to all structured searchable fields without touching
     * transfer-structure rules or user-created rules.
     */
    fun upgradeGenericMerchantRulesToV4(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE `auto_category_rule_conditions`
            SET `field` = 'SEARCHABLE_TEXT'
            WHERE `field` = 'DESCRIPTION'
              AND `ruleId` IN (
                SELECT `id` FROM `auto_category_rules`
                WHERE `ruleSetId` = '${DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID}'
                  AND `priority` >= 29
              )
            """.trimIndent(),
        )
        db.compileStatement(
            """
            UPDATE `auto_category_rule_sets`
            SET `name` = ?, `version` = ?, `contentSha256` = ?
            WHERE `id` = ?
            """.trimIndent(),
        ).use { update ->
            val ruleSet = DefaultClassificationCatalog.publicGenericRuleSet
            update.bindString(1, ruleSet.name)
            update.bindString(2, ruleSet.version)
            update.bindString(3, ruleSet.contentSha256)
            update.bindString(4, ruleSet.id)
            update.executeUpdateDelete()
        }
    }

    /**
     * Restores the bundled catalog only for a database with no categories and no rules at all.
     *
     * A non-empty category or rule table is user-owned state: it may represent a deliberately
     * deleted default, a partial custom catalog, or imported rules whose categories have not been
     * created yet. Those states must remain untouched during upgrades. Legacy schemas have no
     * tombstone for the indistinguishable edge case where a user deliberately deleted both
     * catalogs completely, so the one-time migration treats that fully empty state as unseeded.
     */
    fun seedFullPublicCatalogIfEmpty(db: SupportSQLiteDatabase) {
        if (tableHasRows(db, "categories") || tableHasRows(db, "auto_category_rules")) return

        seedCategories(db, DefaultClassificationCatalog.categories)
        seedRulesForExistingCategoriesV17(db)
        seedRulesV2ForExistingCategories(db)
    }

    /** Used by the v13-to-v14 upgrade: categories remain user-owned and are never inserted here. */
    fun seedRulesForExistingCategories(
        db: SupportSQLiteDatabase,
        rules: List<AutoCategoryRule> = DefaultClassificationCatalog.publicAutoCategoryRules,
    ) {
        db.compileStatement(INSERT_RULE_V1_SQL).use { insert ->
            for (rule in rules) {
                if (!categoryExists(db, rule.categoryId)) continue
                insert.clearBindings()
                insert.bindRuleV1(rule)
                insert.executeInsert()
            }
        }
    }

    private fun seedRulesForExistingCategoriesV17(db: SupportSQLiteDatabase) {
        db.compileStatement(INSERT_RULE_SQL).use { insert ->
            for (rule in DefaultClassificationCatalog.publicAutoCategoryRules) {
                if (!categoryExists(db, rule.categoryId)) continue
                insert.clearBindings()
                insert.bindRule(rule)
                insert.executeInsert()
            }
        }
    }

    /** Inserts only stable public MCC rules and never replaces a user edit or deleted rule. */
    fun seedRulesV2ForExistingCategories(db: SupportSQLiteDatabase) {
        seedPublicRuleCollection(
            db,
            DefaultClassificationCatalog.publicMccRuleSet,
            DefaultClassificationCatalog.publicMccRules,
        )
        seedPublicRuleCollection(
            db,
            DefaultClassificationCatalog.publicStructuralRuleSet,
            DefaultClassificationCatalog.publicStructuralRules,
        )
    }

    /** Inserts the reviewed v3 public rules for a fresh catalog without replacing user rows. */
    fun seedRulesV3ForExistingCategories(db: SupportSQLiteDatabase) {
        seedPublicRuleCollection(
            db,
            DefaultClassificationCatalog.publicGenericRuleSet,
            DefaultClassificationCatalog.publicGenericRules,
        )
    }

    /**
     * One-time v18-to-v19 upgrade seed. A pre-existing generic marker is deliberately terminal:
     * it may mean a user removed a rule or intentionally replaced the collection.
     */
    fun seedRulesV3ForExistingPublicCatalog(db: SupportSQLiteDatabase) {
        if (!ruleSetExists(
                db,
                DefaultClassificationCatalog.publicMccRuleSet.id,
                AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            ) || ruleSetExists(db, DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID)
        ) return

        seedRulesV3ForExistingCategories(db)
    }

    /**
     * v24 is a catalog revision, not a catalog reset.  It adds new public rows only when the
     * corresponding public collection still exists, and retires an old rule only when every
     * mutable rule field is still its shipped value.  A user edit or deletion is therefore never
     * overwritten or resurrected.
     */
    fun upgradePublicCatalogToV5(db: SupportSQLiteDatabase) {
        val hasPublicCatalog = listOf(
            DefaultClassificationCatalog.publicMccRuleSet.id,
            DefaultClassificationCatalog.publicStructuralRuleSet.id,
            DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID,
        ).any { ruleSetExists(db, it, AutoCategoryRuleOrigin.PUBLIC_DEFAULT) }
        if (!hasPublicCatalog) return

        seedCategories(
            db,
            DefaultClassificationCatalog.categories.filter {
                it.id == "income-interest" || it.id == "income-cashback"
            },
        )

        listOf(
            LegacyPublicRule("public-rule-003", "保險｜保險公司", "保險股份有限公司", "expense-insurance", 30),
            LegacyPublicRule("public-rule-006", "休閒娛樂｜Steam", "steam", "expense-entertainment", 60),
            LegacyPublicRule("public-rule-010", "儲值｜自動加值", "自動加值", "expense-topup", 100),
            LegacyPublicRule("public-rule-011", "現金消費｜跨行提款", "跨行提款", "expense-cash", 110),
            LegacyPublicRule("public-rule-015", "費用／手續費｜國外交易", "國外交易手續費", "expense-fees", 150),
            LegacyPublicRule("public-rule-017", "現金消費｜自行提款", "現金自行提款", "expense-cash", 170),
            LegacyPublicRule("public-rule-019", "交通｜停車服務", "停車大聲公", "expense-transport", 190),
        ).forEach { deleteLegacyRuleIfPristine(db, it) }
        deleteStructuralCashWithdrawalIfPristine(db)

        if (ruleSetExists(db, DefaultClassificationCatalog.publicStructuralRuleSet.id, AutoCategoryRuleOrigin.PUBLIC_DEFAULT)) {
            seedPublicRuleCollection(
                db,
                DefaultClassificationCatalog.publicStructuralRuleSet,
                DefaultClassificationCatalog.publicStructuralRules.filter { it.rule.id.endsWith("-v3") },
                insertRuleSet = false,
            )
            updatePublicRuleSetMetadata(db, DefaultClassificationCatalog.publicStructuralRuleSet)
        }
        if (ruleSetExists(db, DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID, AutoCategoryRuleOrigin.PUBLIC_DEFAULT)) {
            seedPublicRuleCollection(
                db,
                DefaultClassificationCatalog.publicGenericRuleSet,
                DefaultClassificationCatalog.publicGenericRules.filter { it.rule.id.startsWith("public-v3-") },
                insertRuleSet = false,
            )
            updatePublicRuleSetMetadata(db, DefaultClassificationCatalog.publicGenericRuleSet)
        }
        if (ruleSetExists(db, DefaultClassificationCatalog.publicMccRuleSet.id, AutoCategoryRuleOrigin.PUBLIC_DEFAULT)) {
            updatePublicRuleSetMetadata(db, DefaultClassificationCatalog.publicMccRuleSet)
        }
    }

    private data class LegacyPublicRule(
        val id: String,
        val name: String,
        val descriptionContains: String,
        val categoryId: String,
        val priority: Int,
    )

    private fun deleteLegacyRuleIfPristine(db: SupportSQLiteDatabase, expected: LegacyPublicRule) {
        if (!hasPristineLegacyCondition(db, expected.id, expected.descriptionContains)) return
        db.execSQL(
            """
            DELETE FROM `auto_category_rules`
            WHERE `id` = ? AND `name` = ? AND `descriptionContains` = ?
              AND `direction` = 'NEGATIVE' AND `minAbsoluteAmount` IS NULL
              AND `maxAbsoluteAmount` IS NULL AND `accountId` IS NULL
              AND `categoryId` = ? AND `enabled` = 1 AND `priority` = ?
              AND `descriptionMatchMode` = 'CONTAINS' AND `isDefault` = 1
              AND `ruleSetId` IS NULL AND `extensionId` IS NULL AND `accountKind` IS NULL
              AND `origin` = 'PUBLIC_DEFAULT' AND `action` = 'AUTO_APPLY'
            """.trimIndent(),
            arrayOf(
                expected.id,
                expected.name,
                expected.descriptionContains,
                expected.categoryId,
                expected.priority,
            ),
        )
    }

    private fun hasPristineLegacyCondition(
        db: SupportSQLiteDatabase,
        ruleId: String,
        descriptionContains: String,
    ): Boolean = db.query(
        """
        SELECT `position`, `conditionGroup`, `field`, `matchMode`, `pattern`
        FROM `auto_category_rule_conditions` WHERE `ruleId` = ? ORDER BY `position`
        """.trimIndent(),
        arrayOf(ruleId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use true
        cursor.count == 1 &&
            cursor.getInt(0) == 0 &&
            cursor.getString(1) == "INCLUDE_ANY" &&
            cursor.getString(2) == "LEGACY_ANY_TEXT" &&
            cursor.getString(3) == "CONTAINS" &&
            cursor.getString(4) == descriptionContains
    }

    private fun deleteStructuralCashWithdrawalIfPristine(db: SupportSQLiteDatabase) {
        val id = "public-structural-auto-deposit-cash-withdrawal-v2"
        val rowMatches = db.query(
            """
            SELECT EXISTS(
                SELECT 1 FROM `auto_category_rules`
                WHERE `id` = ? AND `name` = '結構化｜expense-cash'
                  AND `descriptionContains` IS NULL AND `direction` = 'NEGATIVE'
                  AND `minAbsoluteAmount` IS NULL AND `maxAbsoluteAmount` IS NULL
                  AND `accountId` IS NULL AND `categoryId` = 'expense-cash'
                  AND `enabled` = 1 AND `priority` = 100
                  AND `descriptionMatchMode` = 'CONTAINS' AND `isDefault` = 1
                  AND `ruleSetId` = ? AND `extensionId` IS NULL AND `accountKind` = 'DEPOSIT'
                  AND `origin` = 'PUBLIC_DEFAULT' AND `action` = 'AUTO_APPLY'
            )
            """.trimIndent(),
            arrayOf(id, DefaultClassificationCatalog.publicStructuralRuleSet.id),
        ).use { it.moveToFirst() && it.getInt(0) == 1 }
        if (!rowMatches || !hasExactlyConditions(
                db,
                id,
                listOf("跨行提款", "自行提款", "cash withdrawal", "atm withdrawal"),
            )
        ) return
        db.execSQL("DELETE FROM `auto_category_rules` WHERE `id` = ?", arrayOf(id))
    }

    private fun hasExactlyConditions(
        db: SupportSQLiteDatabase,
        ruleId: String,
        phrases: List<String>,
    ): Boolean = db.query(
        """
        SELECT `position`, `conditionGroup`, `field`, `matchMode`, `pattern`
        FROM `auto_category_rule_conditions` WHERE `ruleId` = ? ORDER BY `position`
        """.trimIndent(),
        arrayOf(ruleId),
    ).use { cursor ->
        val expected = phrases.flatMap { phrase ->
            listOf("DESCRIPTION", "MEMO", "TYPE").map { field ->
                listOf("INCLUDE_ANY", field, "CONTAINS", phrase)
            }
        }
        val actual = buildList {
            while (cursor.moveToNext()) {
                add(listOf(cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4)))
            }
        }
        actual == expected
    }

    private fun seedPublicRuleCollection(
        db: SupportSQLiteDatabase,
        ruleSet: AutoCategoryRuleSet,
        rules: List<PublicMccRule>,
        insertRuleSet: Boolean = true,
    ) {
        if (insertRuleSet) insertRuleSetIfAbsent(db, ruleSet)
        db.compileStatement(INSERT_RULE_SQL).use { insertRule ->
            db.compileStatement(INSERT_CONDITION_SQL).use { insertCondition ->
                rules.forEach { publicRule ->
                    if (!categoryExists(db, publicRule.rule.categoryId)) return@forEach
                    insertRule.clearBindings()
                    insertRule.bindRule(publicRule.rule)
                    val inserted = insertRule.executeInsert() != -1L
                    if (!inserted) return@forEach
                    publicRule.conditions.forEach { condition ->
                        insertCondition.clearBindings()
                        insertCondition.bindCondition(condition)
                        insertCondition.executeInsert()
                    }
                }
            }
        }
    }

    internal fun seedCategories(db: SupportSQLiteDatabase, categories: List<Category>) {
        db.compileStatement(INSERT_CATEGORY_SQL).use { insert ->
            for (category in categories) {
                insert.clearBindings()
                insert.bindString(1, category.id)
                insert.bindString(2, category.name)
                insert.bindString(3, category.color)
                insert.bindString(4, category.emoji)
                insert.bindString(5, category.reportingGroup.name)
                insert.executeInsert()
            }
        }
    }

    private fun categoryExists(db: SupportSQLiteDatabase, categoryId: String?): Boolean =
        categoryId == null || db.query(
            "SELECT EXISTS(SELECT 1 FROM `categories` WHERE `id` = ?)",
            arrayOf(categoryId),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    private fun tableHasRows(db: SupportSQLiteDatabase, tableName: String): Boolean =
        db.query("SELECT EXISTS(SELECT 1 FROM `$tableName`)").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }

    private fun ruleSetExists(
        db: SupportSQLiteDatabase,
        ruleSetId: String,
        origin: AutoCategoryRuleOrigin? = null,
    ): Boolean {
        val sql = if (origin == null) {
            "SELECT EXISTS(SELECT 1 FROM `auto_category_rule_sets` WHERE `id` = ?)"
        } else {
            "SELECT EXISTS(SELECT 1 FROM `auto_category_rule_sets` WHERE `id` = ? AND `origin` = ?)"
        }
        val args = if (origin == null) arrayOf(ruleSetId) else arrayOf(ruleSetId, origin.name)
        return db.query(sql, args).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
    }

    private fun SupportSQLiteStatement.bindRule(rule: AutoCategoryRule) {
        bindString(1, rule.id)
        bindString(2, rule.name)
        bindNullableString(3, rule.descriptionContains)
        bindString(4, rule.amountSign.name)
        bindNullableDouble(5, rule.minAbsoluteAmount)
        bindNullableDouble(6, rule.maxAbsoluteAmount)
        bindNullableString(7, rule.accountId)
        bindNullableString(8, rule.categoryId)
        bindLong(9, if (rule.enabled) 1 else 0)
        bindLong(10, rule.priority.toLong())
        bindString(11, rule.descriptionMatchMode.name)
        bindLong(12, if (rule.isDefault) 1 else 0)
        bindNullableString(13, rule.ruleSetId)
        bindNullableString(14, rule.extensionId)
        bindNullableString(15, rule.accountKind?.name)
        bindString(16, rule.origin.name)
        bindString(17, rule.action.name)
    }

    private fun SupportSQLiteStatement.bindRuleV1(rule: AutoCategoryRule) {
        bindString(1, rule.id)
        bindString(2, rule.name)
        bindNullableString(3, rule.descriptionContains)
        bindString(4, rule.amountSign.name)
        bindNullableDouble(5, rule.minAbsoluteAmount)
        bindNullableDouble(6, rule.maxAbsoluteAmount)
        bindNullableString(7, rule.accountId)
        bindNullableString(8, rule.categoryId)
        bindLong(9, if (rule.enabled) 1 else 0)
        bindLong(10, rule.priority.toLong())
        bindString(11, rule.descriptionMatchMode.name)
        bindLong(12, if (rule.isDefault) 1 else 0)
    }

    private fun SupportSQLiteStatement.bindCondition(condition: AutoCategoryRuleCondition) {
        bindString(1, condition.ruleId)
        bindLong(2, condition.position.toLong())
        bindString(3, condition.conditionGroup.name)
        bindString(4, condition.field.name)
        bindString(5, condition.matchMode.name)
        bindString(6, condition.pattern)
    }

    private fun insertRuleSetIfAbsent(db: SupportSQLiteDatabase, ruleSet: AutoCategoryRuleSet) {
        db.compileStatement(INSERT_RULE_SET_SQL).use { insert ->
            insert.bindString(1, ruleSet.id)
            insert.bindString(2, ruleSet.name)
            insert.bindString(3, ruleSet.origin.name)
            insert.bindString(4, ruleSet.version)
            insert.bindString(5, ruleSet.canonicalizerVersion)
            insert.bindString(6, ruleSet.contentSha256)
            insert.bindLong(7, if (ruleSet.isActive) 1 else 0)
            insert.executeInsert()
        }
    }

    private fun updatePublicRuleSetMetadata(db: SupportSQLiteDatabase, ruleSet: AutoCategoryRuleSet) {
        db.execSQL(
            """
            UPDATE `auto_category_rule_sets`
            SET `name` = ?, `version` = ?, `canonicalizerVersion` = ?, `contentSha256` = ?, `isActive` = ?
            WHERE `id` = ? AND `origin` = 'PUBLIC_DEFAULT'
            """.trimIndent(),
            arrayOf(
                ruleSet.name,
                ruleSet.version,
                ruleSet.canonicalizerVersion,
                ruleSet.contentSha256,
                if (ruleSet.isActive) 1 else 0,
                ruleSet.id,
            ),
        )
    }

    private fun SupportSQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun SupportSQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
        if (value == null) bindNull(index) else bindDouble(index, value)
    }

    private const val INSERT_CATEGORY_SQL = """
        INSERT OR IGNORE INTO `categories` (`id`, `name`, `color`, `emoji`, `kind`)
        VALUES (?, ?, ?, ?, ?)
    """

    private const val INSERT_RULE_SQL = """
        INSERT OR IGNORE INTO `auto_category_rules` (
            `id`, `name`, `descriptionContains`, `direction`, `minAbsoluteAmount`,
            `maxAbsoluteAmount`, `accountId`, `categoryId`, `enabled`, `priority`,
            `descriptionMatchMode`, `isDefault`, `ruleSetId`, `extensionId`, `accountKind`,
            `origin`, `action`
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    private const val INSERT_RULE_V1_SQL = """
        INSERT OR IGNORE INTO `auto_category_rules` (
            `id`, `name`, `descriptionContains`, `direction`, `minAbsoluteAmount`,
            `maxAbsoluteAmount`, `accountId`, `categoryId`, `enabled`, `priority`,
            `descriptionMatchMode`, `isDefault`
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    private const val INSERT_RULE_SET_SQL = """
        INSERT OR IGNORE INTO `auto_category_rule_sets` (
            `id`, `name`, `origin`, `version`, `canonicalizerVersion`, `contentSha256`, `isActive`
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """

    private const val INSERT_CONDITION_SQL = """
        INSERT OR IGNORE INTO `auto_category_rule_conditions` (
            `ruleId`, `position`, `conditionGroup`, `field`, `matchMode`, `pattern`
        ) VALUES (?, ?, ?, ?, ?, ?)
    """
}
