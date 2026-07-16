package tw.kevinzhang.extension_runtime.bridge

import java.util.concurrent.atomic.AtomicInteger

internal class RunRequestBudget(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
) {
    private val requestCount = AtomicInteger(0)

    fun acquire() {
        if (requestCount.incrementAndGet() > maxRequests) {
            throw SafeHttpException("REQUEST_LIMIT", "extension request limit exceeded")
        }
    }

    internal fun used(): Int = requestCount.get()

    private companion object {
        const val DEFAULT_MAX_REQUESTS = 100
    }
}
