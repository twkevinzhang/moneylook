package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tw.kevinzhang.core.data.model.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class StaleClassificationGenerationException : CancellationException("Classification task changed")

@Singleton
class ClassificationJobStore @Inject constructor(private val db: MoneylookDatabase) {
    private val dao get() = db.classificationJobDao()
    fun observe(): Flow<ClassificationJob> = dao.observe().map { it ?: ClassificationJob() }
    suspend fun current(): ClassificationJob = dao.current() ?: ClassificationJob().also { dao.initialize(it) }
    suspend fun preview(): ClassificationJobPreview = db.withTransaction {
        val job = current()
        val ids = when {
            job.fullReclassification && job.preparedGeneration != job.generation -> dao.allNonManual()
            job.preparedGeneration == job.generation && job.status != "COMPLETED" -> dao.pending(job.generation).plus(AiClassificationStore(db).pendingIds()).distinct()
            else -> AiClassificationStore(db).pendingIds()
        }
        ClassificationJobPreview(ids.size, job.fullReclassification, AiClassificationStore(db).isFirst(), job.active)
    }
    suspend fun enqueue(): ClassificationJob? = db.withTransaction {
        val old = current()
        if (old.active || preview().pendingTransferCount == 0) return@withTransaction null
        val next = old.generation + 1
        val retain = old.preparedGeneration == old.generation && old.status != "COMPLETED"
        var total = old.total
        if (retain) {
            val existing = dao.items(old.generation).mapTo(mutableSetOf()) { it.transferId }
            val additions = AiClassificationStore(db).pendingIds().filterNot { it in existing }
            dao.moveGeneration(old.generation, next)
            dao.putItems(additions.map { ClassificationJobItem(it, next) })
            total += additions.size
        }
        old.copy(generation = next, preparedGeneration = if (retain) next else -1,
            total = total, status = "QUEUED", message = null, attemptId = UUID.randomUUID().toString(), startedAttemptId = null).also { dao.put(it) }
    }
    /** A system retry must never silently grant another paid budget to the same manual attempt. */
    suspend fun claimAttempt(attemptId: String): Boolean = db.withTransaction {
        val job = current()
        if (!job.active || job.status == "STOPPING" || job.attemptId != attemptId || job.startedAttemptId == attemptId)
            return@withTransaction false
        dao.put(job.copy(startedAttemptId = attemptId)); true
    }
    suspend fun prepare(attemptId: String): ClassificationJob = db.withTransaction {
        val job = current()
        if (!job.active || job.status == "STOPPING" || job.attemptId != attemptId) throw StaleClassificationGenerationException()
        if (job.preparedGeneration != job.generation) {
            val ids = if (job.fullReclassification) dao.allNonManual() else AiClassificationStore(db).pendingIds()
            dao.clearItems()
            dao.putItems(ids.map { ClassificationJobItem(it, job.generation) })
            job.copy(preparedGeneration = job.generation, total = ids.size, processed = 0, status = "RUNNING",
                message = if (job.fullReclassification) "規則已變更，重新分類全部非人工交易" else null).also { dao.put(it) }
        } else {
            job.copy(status = "RUNNING").also { dao.put(it) }
        }
    }
    suspend fun pendingIds(generation: Long): List<String> = dao.pending(generation)
    suspend fun requireGeneration(generation: Long) {
        val job = current()
        if (job.generation != generation || job.preparedGeneration != generation || !job.active || job.status == "STOPPING")
            throw StaleClassificationGenerationException()
    }
    suspend fun <T> guarded(generation: Long, id: String, block: suspend () -> T): T = db.withTransaction {
        requireGeneration(generation)
        block().also { dao.complete(id, generation); dao.updateProgress(generation) }
    }
    suspend fun stopping(attemptId: String): Boolean = db.withTransaction {
        val job = current()
        if (!job.active || job.attemptId != attemptId) return@withTransaction false
        dao.put(job.copy(status = "STOPPING", message = "正在停止並保存進度")); true
    }
    suspend fun pause(attemptId: String, message: String) = db.withTransaction {
        val job = current()
        if (job.attemptId == attemptId) dao.put(job.copy(status = "PAUSED", message = message))
    }
    suspend fun finish(attemptId: String, generation: Long, message: String? = null) = db.withTransaction {
        val job = current()
        if (job.attemptId != attemptId || job.generation != generation) return@withTransaction
        val done = dao.pending(generation).isEmpty()
        dao.put(job.copy(status = if (done) "COMPLETED" else "PAUSED", fullReclassification = if (done) false else job.fullReclassification,
            processed = if (done) job.total else job.processed, message = message ?: if (done) "背景分類已完成" else "部分交易仍待分類，可再次接續"))
    }
}
