package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.CredentialProfile

@Dao
interface CredentialProfileDao {
    @Query("SELECT * FROM credential_profiles ORDER BY extensionId")
    fun observeAll(): Flow<List<CredentialProfile>>

    @Query("SELECT * FROM credential_profiles ORDER BY extensionId")
    suspend fun getAll(): List<CredentialProfile>

    @Query("SELECT * FROM credential_profiles WHERE extensionId = :extensionId")
    suspend fun getByExtensionId(extensionId: String): CredentialProfile?

    @Query(
        """
        SELECT * FROM credential_profiles
        WHERE scheduleEnabled = 1 AND username != '' AND password != ''
        ORDER BY extensionId
        """,
    )
    suspend fun getRunnableSchedules(): List<CredentialProfile>

    @Upsert
    suspend fun upsert(profile: CredentialProfile)

    @Query(
        """
        UPDATE credential_profiles
        SET lastRunAt = :lastRunAt, lastRunStatus = :lastRunStatus
        WHERE extensionId = :extensionId
        """,
    )
    suspend fun updateLastRun(
        extensionId: String,
        lastRunAt: Long,
        lastRunStatus: String,
    )

    @Query("DELETE FROM credential_profiles WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)
}
