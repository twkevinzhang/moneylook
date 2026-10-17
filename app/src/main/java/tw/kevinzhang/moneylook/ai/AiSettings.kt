package tw.kevinzhang.moneylook.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AiProvider(val label: String) { GEMINI("Gemini"), OPENAI("OpenAI"), CLAUDE("Claude"), CUSTOM("自訂 OpenAI 相容服務") }

data class AiSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val model: String = "",
    val baseUrl: String = "",
    val apiToken: String = "",
    val accessClientId: String = "",
    val accessClientSecret: String = "",
) {
    override fun toString() = "AiSettings(provider=$provider, credentials=[REDACTED])"
    fun endpoint(): String = when (provider) {
        AiProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
        AiProvider.CLAUDE -> "https://api.anthropic.com/v1/messages"
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/${model.trim().removePrefix("models/")}:generateContent"
        AiProvider.CUSTOM -> baseUrl.trim().trimEnd('/') + "/chat/completions"
    }
}

fun validateAiSettings(settings: AiSettings): List<String> = buildList {
    if (settings.model.isBlank() || !settings.model.matches(Regex("[A-Za-z0-9_./:@-]{1,160}"))) add("請輸入有效的模型名稱")
    if (settings.apiToken.isBlank()) add("請輸入 API Token")
    if (listOf(settings.apiToken, settings.accessClientId, settings.accessClientSecret).any { it.any { c -> c == '\r' || c == '\n' || c.code < 32 || c.code > 126 } }) add("憑證含無效字元")
    if (settings.provider == AiProvider.CUSTOM) {
        val url = settings.baseUrl.trim().toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) add("Base URL 必須為不含帳密、query 或 fragment 的 HTTPS API 根路徑")
        if (settings.accessClientId.isBlank() != settings.accessClientSecret.isBlank()) add("Cloudflare Client ID 與 Client Secret 必須一起填寫")
    }
}
