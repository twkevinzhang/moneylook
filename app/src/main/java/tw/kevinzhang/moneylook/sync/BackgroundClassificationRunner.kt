package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import tw.kevinzhang.core.data.db.ClassificationJobStore
import tw.kevinzhang.core.data.db.StaleClassificationGenerationException
import javax.inject.Inject

class BackgroundClassificationBudget(private val nowNanos: () -> Long = System::nanoTime) {
    private val started = nowNanos()
    var requests: Int = 0; private set
    var considered: Int = 0; private set
    var stopReason: String? = null
    fun remainingMillis(): Long = (600_000L - (nowNanos() - started) / 1_000_000L).coerceAtLeast(0L)
    fun remainingTransactions(): Int = (500 - considered).coerceAtLeast(0)
    fun consider(count: Int) { require(count in 0..remainingTransactions()); considered += count }
    fun reserveRequest(): Boolean {
        if (requests >= 25) stopReason = "已達本輪 25 次 AI 請求上限，可再次接續"
        if (remainingMillis() <= 0) stopReason = "已達本輪 10 分鐘上限，可再次接續"
        if (stopReason != null) return false
        requests++; return true
    }
}

internal data class BackgroundBatchContext(val store: ClassificationJobStore, val generation: Long,
    val fullReclassification: Boolean, val budget: BackgroundClassificationBudget)

/** One bounded execution; rule generations restart within the SAME request/time budget. */
class BackgroundClassificationRunner @Inject constructor(
    private val store: ClassificationJobStore,
    private val categorizer: AutoCategorizer,
) {
    suspend fun run(attemptId: String, budget: BackgroundClassificationBudget = BackgroundClassificationBudget()) {
        try {
            withTimeout(budget.remainingMillis().coerceAtLeast(1)) {
                while (true) {
                    val job = store.prepare(attemptId)
                    val generation = job.generation
                    val visited = mutableSetOf<String>()
                    val generationChanged = coroutineScope {
                        val changed = async { store.observe().first { it.generation != generation || !it.active || it.status == "STOPPING" } }
                        val processing = async {
                            while (true) {
                                store.requireGeneration(generation)
                                val pending = store.pendingIds(generation).filterNot { it in visited }
                                if (pending.isEmpty()) break
                                if (budget.remainingTransactions() == 0) {
                                    budget.stopReason = "已達本輪 500 筆處理上限，可再次接續"; break
                                }
                                val ids = pending.take(minOf(20, budget.remainingTransactions()))
                                visited.addAll(ids); budget.consider(ids.size)
                                categorizer.applyBackgroundBatch(ids, generation, store, budget)
                                if (budget.stopReason != null) break
                            }
                        }
                        try {
                            select<Boolean> {
                                changed.onAwait { processing.cancelAndJoin(); true }
                                processing.onAwait { changed.cancelAndJoin(); false }
                            }
                        } catch (error: StaleClassificationGenerationException) {
                            if (store.current().generation != generation) true else throw error
                        } finally { processing.cancel(); changed.cancel() }
                    }
                    if (generationChanged || store.current().generation != generation) {
                        if (budget.stopReason != null || budget.remainingMillis() <= 0 || budget.remainingTransactions() == 0) {
                            store.pause(attemptId, budget.stopReason ?: "本輪上限已到，規則變更後可再次接續"); return@withTimeout
                        }
                        continue
                    }
                    store.finish(attemptId, generation, budget.stopReason)
                    return@withTimeout
                }
            }
        } catch (_: TimeoutCancellationException) {
            store.pause(attemptId, "已達本輪 10 分鐘上限，可再次接續")
        } catch (error: CancellationException) {
            withContext(NonCancellable) { store.pause(attemptId, "已停止並保存進度，可再次接續") }
            throw error
        } catch (_: Exception) {
            store.pause(attemptId, "背景分類尚未完成，已保存完成結果，可再次接續")
        }
    }
}
