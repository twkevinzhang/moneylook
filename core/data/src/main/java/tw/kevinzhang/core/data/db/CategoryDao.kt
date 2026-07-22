package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryKind

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY name COLLATE NOCASE, id")
    fun observeByKind(kind: CategoryKind): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): Category?

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY name COLLATE NOCASE, id")
    suspend fun getByKind(kind: CategoryKind): List<Category>

    @Upsert
    suspend fun upsert(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)
}
