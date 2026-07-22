package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Category

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): Category?

    @Upsert
    suspend fun upsert(category: Category)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)
}
