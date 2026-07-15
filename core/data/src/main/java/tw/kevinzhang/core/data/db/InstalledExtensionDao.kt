package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.InstalledExtension

@Dao
interface InstalledExtensionDao {
    @Query("SELECT * FROM installed_extensions ORDER BY name")
    fun observeAll(): Flow<List<InstalledExtension>>

    @Query("SELECT * FROM installed_extensions ORDER BY name")
    suspend fun getAll(): List<InstalledExtension>

    @Query("SELECT * FROM installed_extensions WHERE id = :id")
    suspend fun getById(id: String): InstalledExtension?

    @Upsert
    suspend fun insert(extension: InstalledExtension)

    @Query("DELETE FROM installed_extensions WHERE id = :id")
    suspend fun deleteById(id: String)
}
