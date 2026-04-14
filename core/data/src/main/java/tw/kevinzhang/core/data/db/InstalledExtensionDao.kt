package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(extension: InstalledExtension)

    @Query("DELETE FROM installed_extensions WHERE id = :id")
    suspend fun deleteById(id: String)
}
