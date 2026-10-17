package tw.kevinzhang.moneylook.ai

import org.junit.Assert.*
import org.junit.Test

class AiSettingsTest {
    private val settings = AiSettings(AiProvider.CUSTOM, "test-model", "https://vm.example/proxy/v1/", "secret", "client", "cf-secret")
    @Test fun customRootPreservesPrefixAndAddsOnlyChatPath() {
        assertEquals("https://vm.example/proxy/v1/chat/completions", settings.endpoint())
        assertTrue(validateAiSettings(settings).isEmpty())
    }
    @Test fun rejectsUnsafeUrlsAndIncompleteAccessCredentials() {
        for (url in listOf("http://vm.example/v1", "https://user:pass@vm.example/v1", "https://vm.example/v1?token=x", "https://vm.example/v1#x")) {
            assertTrue(validateAiSettings(settings.copy(baseUrl = url)).isNotEmpty())
        }
        assertTrue(validateAiSettings(settings.copy(accessClientSecret = "")).isNotEmpty())
    }
    @Test fun printedSettingsNeverRevealCredentials() {
        assertFalse(settings.toString().contains("cf-secret"))
        assertFalse(settings.toString().contains("secret"))
    }
}
