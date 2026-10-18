package tw.kevinzhang.moneylook.ai

import com.google.gson.JsonParser
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.*

class AiBatchProtocolTest {
    private val settings = AiSettings(AiProvider.CUSTOM, "test-model", "https://vm.example/v1", "api-secret", "cf-id", "cf-secret")
    private val input = AiClassificationInput("咖啡", -80.0, "TWD", listOf(Category("food", "餐飲", "#000000"), Category("transfer-account", "帳戶移轉", "#000000")))
    private val items = listOf(AiBatchItem("first", input), AiBatchItem("second", input), AiBatchItem("third", input))
    private fun body(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test fun matchesIdsRegardlessOfOrderAndPreservesPartialSuccess() {
        val result = AiBatchProtocol.parse("""{"results":[{"id":"second","categoryId":"food","reason":"咖啡"},{"id":"unknown","categoryId":"food","reason":"忽略"},{"id":"first","categoryId":"transfer-account","reason":"禁止"}]}""", items)
        assertEquals(setOf("second"), result.proposals.keys)
        assertEquals(setOf("first", "third"), result.invalidIds)
    }
    @Test fun duplicateIdsAreInvalidEvenIfOneEntryWasValid() {
        val result = AiBatchProtocol.parse("""{"results":[{"id":"first","categoryId":"food","reason":"商家"},{"id":"first","categoryId":"food","reason":"重複"},{"id":"second","newCategory":{"name":"早餐","reportingGroup":"EXPENSE"},"reason":"商家"},null]}""", items)
        assertEquals(setOf("second"), result.proposals.keys)
        assertEquals(setOf("first", "third"), result.invalidIds)
    }
    @Test fun rejectsMalformedEnvelopes() {
        for (text in listOf("{}", "{\"results\":{}}", "{\"results\":[", "{results:[]}", "{\"results\":[]} trailing")) {
            try { AiBatchProtocol.parse(text, items); fail("accepted $text") }
            catch (e: AiClassificationException) { assertEquals(AiFailureCode.INVALID_RESPONSE, e.code) }
        }
    }
    @Test fun allProvidersShareOneCatalogAndHaveBoundedOutputAndIsolatedCredentials() {
        for (provider in AiProvider.entries) {
            val request = AiBatchProtocol.request(settings.copy(provider = provider), items)
            val json = JsonParser.parseString(body(request)).asJsonObject
            val user = when (provider) {
                AiProvider.GEMINI -> json.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")[0].asJsonObject.get("text").asString
                else -> json.getAsJsonArray("messages").last().asJsonObject.get("content").asString
            }
            val payload = JsonParser.parseString(user).asJsonObject
            assertEquals(1, payload.getAsJsonArray("categories").size())
            assertEquals(3, payload.getAsJsonArray("transactions").size())
            assertFalse(payload.getAsJsonArray("transactions")[0].asJsonObject.has("categories"))
            assertFalse(user.contains("transfer-account"))
            val max = when(provider) {
                AiProvider.GEMINI -> json.getAsJsonObject("generationConfig").get("maxOutputTokens")
                AiProvider.OPENAI -> json.get("max_completion_tokens")
                else -> json.get("max_tokens")
            }
            assertEquals(4096, max.asInt)
            assertEquals(if(provider == AiProvider.GEMINI) "api-secret" else null, request.header("x-goog-api-key"))
            assertEquals(if(provider == AiProvider.CLAUDE) "api-secret" else null, request.header("x-api-key"))
            assertEquals(if(provider in listOf(AiProvider.OPENAI, AiProvider.CUSTOM)) "Bearer api-secret" else null, request.header("Authorization"))
            assertEquals(if(provider == AiProvider.CUSTOM) "cf-secret" else null, request.header("CF-Access-Client-Secret"))
            assertEquals(if(provider == AiProvider.CUSTOM) "cf-id" else null, request.header("CF-Access-Client-Id"))
        }
    }
    @Test fun rejectsOversizedBatchesAndInvalidSettingsBeforeTransport() {
        for (batch in listOf((1..21).map { AiBatchItem("$it",input) }, listOf(AiBatchItem("big",input.copy(summary="漢".repeat(24000)))))) {
            try { AiBatchProtocol.request(settings,batch); fail("accepted oversized batch") }
            catch (_: BatchTooLargeException) { }
        }
        try { AiBatchProtocol.request(settings.copy(apiToken=""),items); fail("accepted settings") }
        catch (e: AiClassificationException) { assertEquals(AiFailureCode.SETTINGS,e.code) }
    }
    @Test fun rejectsBadIndividualProposalsWithoutDiscardingValidNeighbors() {
        val result = AiBatchProtocol.parse("""{"results":[{"id":"first","categoryId":"food","reason":"咖啡"},{"id":"second","categoryId":"unknown","reason":"商家"},{"id":"third","newCategory":{"name":"本人帳戶移轉","reportingGroup":"EXCLUDED"},"reason":"移轉"}]}""", items)
        assertEquals(setOf("first"),result.proposals.keys)
        assertEquals(setOf("second","third"),result.invalidIds)
    }
    @Test fun acceptsExactRequestByteLimitAndTwentyItems() {
        assertNotNull(AiBatchProtocol.request(settings,(1..20).map { AiBatchItem("$it",input) }))
        val base=listOf(AiBatchItem("one",input.copy(summary="")))
        val overhead=body(AiBatchProtocol.request(settings,base)).toByteArray(Charsets.UTF_8).size
        val full=listOf(AiBatchItem("one",input.copy(summary="x".repeat(65536-overhead))))
        assertEquals(65536L,AiBatchProtocol.request(settings,full).body!!.contentLength())
        try { AiBatchProtocol.request(settings,listOf(full.first().copy(input=full.first().input.copy(summary=full.first().input.summary+"x")))); fail("accepted 65537 bytes") }
        catch(_:BatchTooLargeException) { }
    }
}
