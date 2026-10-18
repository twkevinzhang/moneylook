package tw.kevinzhang.core.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.*

@Dao
interface ClassificationJobDao {
    @Query("SELECT * FROM classification_jobs WHERE id=1") fun observe(): Flow<ClassificationJob?>
    @Query("SELECT * FROM classification_jobs WHERE id=1") suspend fun current(): ClassificationJob?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun initialize(job: ClassificationJob): Long
    @Upsert suspend fun put(job: ClassificationJob)
    @Query("SELECT * FROM classification_job_items WHERE generation=:generation ORDER BY transferId") suspend fun items(generation: Long): List<ClassificationJobItem>
    @Upsert suspend fun putItems(items: List<ClassificationJobItem>)
    @Query("DELETE FROM classification_job_items") suspend fun clearItems()
    @Query("SELECT transferId FROM classification_job_items WHERE generation=:generation AND completed=0 ORDER BY transferId") suspend fun pending(generation: Long): List<String>
    @Query("UPDATE classification_job_items SET completed=1 WHERE generation=:generation AND transferId=:id") suspend fun complete(id: String, generation: Long)
    @Query("UPDATE classification_job_items SET generation=:next WHERE generation=:previous") suspend fun moveGeneration(previous: Long, next: Long)
    @Query("SELECT t.id FROM transfers t INNER JOIN accounts ac ON ac.id=t.accountId LEFT JOIN transfer_annotations a ON a.transferId=t.id WHERE COALESCE(a.manualOverride,0)=0 ORDER BY t.id")
    suspend fun allNonManual(): List<String>
    @Query("UPDATE classification_jobs SET processed=(SELECT COUNT(*) FROM classification_job_items WHERE completed=1 AND generation=:generation) WHERE id=1 AND generation=:generation")
    suspend fun updateProgress(generation: Long)
}
