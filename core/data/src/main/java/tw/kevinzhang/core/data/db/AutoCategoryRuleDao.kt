package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleTagCrossRef
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.Tag

data class AutoCategoryRuleWithTags(
    @Embedded val rule: AutoCategoryRule,
    @Embedded(prefix = "category_") val category: Category?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = AutoCategoryRuleTagCrossRef::class,
            parentColumn = "ruleId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
    @Relation(
        parentColumn = "id",
        entityColumn = "ruleId",
    )
    val conditions: List<AutoCategoryRuleCondition> = emptyList(),
    @Relation(
        parentColumn = "ruleSetId",
        entityColumn = "id",
    )
    val ruleSet: AutoCategoryRuleSet? = null,
)

@Dao
abstract class AutoCategoryRuleDao {
    @Transaction
    @Query(RULE_SELECT + " ORDER BY r.isDefault, r.priority, r.id")
    abstract fun observeAll(): Flow<List<AutoCategoryRuleWithTags>>

    @Transaction
    @Query(RULE_SELECT + " WHERE r.enabled = 1 ORDER BY r.isDefault, r.priority, r.id")
    abstract suspend fun getEnabledInPriorityOrder(): List<AutoCategoryRuleWithTags>

    @Upsert
    abstract suspend fun upsert(rule: AutoCategoryRule)

    /** Inserts a bundled rule without replacing a user-edited rule with the same stable ID. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(rule: AutoCategoryRule): Long

    @Transaction
    open suspend fun upsertWithTags(rule: AutoCategoryRule, tagIds: Set<String>) {
        upsert(rule)
        replaceTags(rule.id, tagIds)
    }

    /** Atomically persists the rule together with its structured clauses and tags. */
    @Transaction
    open suspend fun upsertWithDetails(
        rule: AutoCategoryRule,
        conditions: List<AutoCategoryRuleCondition>,
        tagIds: Set<String>,
    ) {
        require(conditions.all { it.ruleId == rule.id }) { "conditions must belong to the rule" }
        require(conditions.map { it.position }.distinct().size == conditions.size) {
            "condition positions must be unique per rule"
        }
        upsert(rule)
        replaceConditions(rule.id, conditions)
        replaceTags(rule.id, tagIds)
    }

    @Transaction
    open suspend fun replaceConditions(ruleId: String, conditions: List<AutoCategoryRuleCondition>) {
        require(conditions.all { it.ruleId == ruleId }) { "conditions must belong to the rule" }
        require(conditions.map { it.position }.distinct().size == conditions.size) {
            "condition positions must be unique per rule"
        }
        deleteConditions(ruleId)
        if (conditions.isNotEmpty()) upsertConditions(conditions)
    }

    @Transaction
    open suspend fun replaceTags(ruleId: String, tagIds: Set<String>) {
        deleteTagCrossRefs(ruleId)
        if (tagIds.isNotEmpty()) upsertTagCrossRefs(tagIds.map { AutoCategoryRuleTagCrossRef(ruleId, it) })
    }

    @Query("DELETE FROM auto_category_rule_tag_cross_refs WHERE ruleId = :ruleId")
    protected abstract suspend fun deleteTagCrossRefs(ruleId: String)

    @Upsert
    protected abstract suspend fun upsertTagCrossRefs(crossRefs: List<AutoCategoryRuleTagCrossRef>)

    @Query("DELETE FROM auto_category_rule_conditions WHERE ruleId = :ruleId")
    protected abstract suspend fun deleteConditions(ruleId: String)

    @Upsert
    protected abstract suspend fun upsertConditions(conditions: List<AutoCategoryRuleCondition>)

    @Query("DELETE FROM auto_category_rules WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    private companion object {
        const val RULE_SELECT = """
            SELECT
                r.*,
                c.id AS category_id,
                c.name AS category_name,
                c.color AS category_color,
                c.emoji AS category_emoji,
                c.kind AS category_kind
            FROM auto_category_rules AS r
            LEFT JOIN categories AS c ON c.id = r.categoryId
        """
    }
}
