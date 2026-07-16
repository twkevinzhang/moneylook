package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM installed_extensions WHERE manifestId = :manifestId LIMIT 1")
    suspend fun getByManifestId(manifestId: String): InstalledExtension?

    @Upsert
    suspend fun insert(extension: InstalledExtension)

    /**
     * Installs or updates an extension only when the manifest is not owned by another source.
     * The check and upsert share one Room transaction so concurrent install actions cannot
     * create two rows for the same manifest from different repositories.
     */
    @Transaction
    suspend fun upsertUnlessInstalledFromOtherSource(extension: InstalledExtension): Boolean {
        val existing = getByManifestId(extension.manifestId)
        if (existing != null && existing.id != extension.id) return false
        insert(extension)
        return true
    }

    @Query("DELETE FROM installed_extensions WHERE id = :id")
    suspend fun deleteById(id: String)
}
