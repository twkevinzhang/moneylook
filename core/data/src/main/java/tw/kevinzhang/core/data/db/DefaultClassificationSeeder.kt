package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.Category

/** Writes only absent public catalog rows; it never updates or recreates user-deleted data. */
object DefaultClassificationSeeder {
    fun seedFreshDatabase(db: SupportSQLiteDatabase) {
        seedCategories(db, DefaultClassificationCatalog.categories)
        seedRulesForExistingCategories(db)
    }

    /** Used by the v13-to-v14 upgrade: categories remain user-owned and are never inserted here. */
    fun seedRulesForExistingCategories(
        db: SupportSQLiteDatabase,
        rules: List<AutoCategoryRule> = DefaultClassificationCatalog.publicAutoCategoryRules,
    ) {
        db.compileStatement(INSERT_RULE_SQL).use { insert ->
            for (rule in rules) {
                if (!categoryExists(db, rule.categoryId)) continue
                insert.clearBindings()
                insert.bindRule(rule)
                insert.executeInsert()
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
        bindLong(12, 1)
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
            `descriptionMatchMode`, `isDefault`
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
}
