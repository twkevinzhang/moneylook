package tw.kevinzhang.extension_runtime.captcha

sealed interface CaptchaSolveResult {
    data class Success(val text: String) : CaptchaSolveResult
    data class Error(val message: String, val cause: Throwable? = null) : CaptchaSolveResult
}

/** Solves a normal image captcha. Implementations must not log image bytes or responses. */
fun interface CaptchaSolver {
    suspend fun solve(imageBytes: ByteArray): CaptchaSolveResult
}
