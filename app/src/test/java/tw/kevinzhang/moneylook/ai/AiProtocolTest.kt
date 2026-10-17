package tw.kevinzhang.moneylook.ai

import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.*

class AiProtocolTest {
    private val settings = AiSettings(AiProvider.CUSTOM, "test-model", "https://vm.example/v1", "api-secret", "cf-id", "cf-secret")
    private val input = AiClassificationInput("咖啡", -80.0, "TWD", listOf(Category("food", "餐飲", "#000000")))
    @Test fun customHasIndependentBearerAndAccessHeaders() {
        val request = AiProtocol.request(settings, input)
        assertEquals("Bearer api-secret", request.header("Authorization"))
        assertEquals("cf-id", request.header("CF-Access-Client-Id"))
        assertEquals("cf-secret", request.header("CF-Access-Client-Secret"))
        for (provider in listOf(AiProvider.GEMINI, AiProvider.OPENAI, AiProvider.CLAUDE)) {
            assertNull(AiProtocol.request(settings.copy(provider = provider), input).header("CF-Access-Client-Secret"))
        }
    }
    @Test fun parsesNewCategoryAndRefusesUnknownOrReservedCategory() {
        val result = AiProtocol.parse("""{"newCategory":{"name":"早餐","reportingGroup":"EXPENSE"},"reason":"商家資訊"}""", input)
        assertEquals(AiCategorySelection.New("早餐", CategoryReportingGroup.EXPENSE), result.selection)
        for (id in listOf("missing", "transfer-account")) {
            try { AiProtocol.parse("""{"categoryId":"$id","reason":"test"}""", input); fail("accepted invalid ID") }
            catch (_: AiClassificationException) { }
        }
    }
    @Test fun readsGeminiClaudeAndOpenAiResponseEnvelopes() {
        val answer="""{"categoryId":"food","reason":"商家"}"""
        val json=com.google.gson.Gson().toJson(answer)
        val responses=mapOf(
            AiProvider.GEMINI to """{"candidates":[{"content":{"parts":[{"text":$json}]}}]}""",
            AiProvider.CLAUDE to """{"content":[{"type":"text","text":$json}]}""",
            AiProvider.OPENAI to """{"choices":[{"message":{"content":$json}}]}""")
        for((provider,response) in responses) {
            assertEquals(AiCategorySelection.Existing("food"),AiProtocol.parse(AiProtocol.content(provider,response),input).selection)
        }
        assertEquals("api-secret",AiProtocol.request(settings.copy(provider=AiProvider.GEMINI),input).header("x-goog-api-key"))
        assertEquals("2023-06-01",AiProtocol.request(settings.copy(provider=AiProvider.CLAUDE),input).header("anthropic-version"))
    }
}
