package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet

@Dao
abstract class AutoCategoryRuleSetDao {
    @Query("SELECT * FROM auto_category_rule_sets WHERE id = :id")
    abstract suspend fun getById(id: String): AutoCategoryRuleSet?

    @Query("SELECT * FROM auto_category_rule_sets ORDER BY id")
    abstract suspend fun getAll(): List<AutoCategoryRuleSet>

    @Query("SELECT * FROM auto_category_rule_sets ORDER BY isActive DESC, id")
    abstract fun observeAll(): Flow<List<AutoCategoryRuleSet>>

    @Upsert
    abstract suspend fun upsert(ruleSet: AutoCategoryRuleSet)

    /** Keeps rules intact when a collection is removed, as ruleSetId intentionally has no FK. */
    @Transaction
    open suspend fun deleteAndDetachRules(ruleSetId: String) {
        detachRules(ruleSetId)
        deleteById(ruleSetId)
    }

    @Query("UPDATE auto_category_rules SET ruleSetId = NULL WHERE ruleSetId = :ruleSetId")
    protected abstract suspend fun detachRules(ruleSetId: String)

    @Query("DELETE FROM auto_category_rule_sets WHERE id = :ruleSetId")
    protected abstract suspend fun deleteById(ruleSetId: String)
}
