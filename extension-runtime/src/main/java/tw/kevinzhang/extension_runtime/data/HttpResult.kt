package tw.kevinzhang.extension_runtime.data

data class HttpResult private constructor(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    companion object {
        operator fun invoke(status: Int, body: String, headers: Map<String, String>) =
            HttpResult(status, body, headers.toMap()) // defensive copy — caller's map must not mutate our state
    }
}
