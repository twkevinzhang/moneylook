package tw.kevinzhang.extension_runtime.data

data class HttpResult(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)
