package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleTagCrossRef
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
)

@Dao
abstract class AutoCategoryRuleDao {
    @Transaction
    @Query(RULE_SELECT + " ORDER BY r.priority, r.id")
    abstract fun observeAll(): Flow<List<AutoCategoryRuleWithTags>>

    @Transaction
    @Query(RULE_SELECT + " WHERE r.enabled = 1 ORDER BY r.priority, r.id")
    abstract suspend fun getEnabledInPriorityOrder(): List<AutoCategoryRuleWithTags>

    @Upsert
    abstract suspend fun upsert(rule: AutoCategoryRule)

    @Transaction
    open suspend fun upsertWithTags(rule: AutoCategoryRule, tagIds: Set<String>) {
        upsert(rule)
        replaceTags(rule.id, tagIds)
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
