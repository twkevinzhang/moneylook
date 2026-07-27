package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE kind = :reportingGroup ORDER BY name COLLATE NOCASE, id")
    fun observeByReportingGroup(reportingGroup: CategoryReportingGroup): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): Category?

    @Query("SELECT * FROM categories WHERE kind = :reportingGroup ORDER BY name COLLATE NOCASE, id")
    suspend fun getByReportingGroup(reportingGroup: CategoryReportingGroup): List<Category>

    @Upsert
    suspend fun upsert(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)
}
