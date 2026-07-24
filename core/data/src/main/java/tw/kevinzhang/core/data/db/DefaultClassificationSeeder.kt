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

    private fun seedPublicRuleCollection(
        db: SupportSQLiteDatabase,
        ruleSet: AutoCategoryRuleSet,
        rules: List<PublicMccRule>,
    ) {
        insertRuleSetIfAbsent(db, ruleSet)
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
                insert.bindString(5, category.kind.name)
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
        bindString(4, rule.direction.name)
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
        bindString(4, rule.direction.name)
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
