package tw.kevinzhang.moneylook.sync

/** Shared by the batch/sync/resume paths while AutoCategorizer owns its mutex. */
internal class ClassificationBudget(private val nowNanos: () -> Long = System::nanoTime) {
    private val started = nowNanos()
    var requests = 0
    var failed = 0
    var stopReason: String? = null
    fun remainingMillis(): Long = (600_000L - (nowNanos() - started) / 1_000_000L).coerceAtLeast(1L)
    fun canRequest(): Boolean {
        if (requests >= 100) stopReason = "已達本次 100 次 AI 請求上限，請再次接續"
        if (remainingMillis() <= 1L) stopReason = "已達本次 10 分鐘上限，請再次接續"
        return stopReason == null
    }
}
