package tw.kevinzhang.moneylook.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.*

class AiClassificationClientTest {
    private fun <T> withServer(block: suspend (MockWebServer,AiClassificationClient,AiSettings)->T): T = runBlocking {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(),false)
            server.start()
            val failure=java.util.concurrent.atomic.AtomicReference<java.io.IOException?>()
            val client=AiClassificationClient(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager)
                .eventListener(object : okhttp3.EventListener() { override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { failure.set(ioe) } })
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())
            try { block(server,client,AiSettings(AiProvider.CUSTOM,"test-model",server.url("/proxy/v1/").newBuilder().host("localhost").build().toString(),"api-secret","access-id","access-secret")) }
            catch(e: Exception) { throw AssertionError("Local TLS transport: ${failure.get()}",e) }
        }
    }
    private val input=AiClassificationInput("咖啡",-80.0,"TWD",listOf(Category("food","餐飲","#000000")))
    @Test fun sendsRealTlsRequestWithPrefixAndBothCredentials() = withServer { server,client,settings ->
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"categoryId\":\"food\",\"reason\":\"咖啡店\"}"}}]}"""))
        assertEquals(AiCategorySelection.Existing("food"),client.classify(settings,input).selection)
        val request=server.takeRequest()
        assertEquals("/proxy/v1/chat/completions",request.path)
        assertEquals("Bearer api-secret",request.getHeader("Authorization"))
        assertEquals("access-secret",request.getHeader("CF-Access-Client-Secret"))
    }
    @Test fun rejectsHtmlRedirectAndInvalidResponseWithoutLeakingUpstreamBody() = withServer { server,client,settings ->
        for ((response,code) in listOf(
            MockResponse().setBody("<html>Cloudflare Access api-secret</html>") to AiFailureCode.ACCESS_OR_AUTH,
            MockResponse().setResponseCode(302).addHeader("Location","https://untrusted.example") to AiFailureCode.ACCESS_OR_AUTH,
            MockResponse().setResponseCode(429).setBody("access-secret") to AiFailureCode.RATE_LIMIT,
            MockResponse().setBody("not JSON api-secret") to AiFailureCode.INVALID_RESPONSE)) {
            server.enqueue(response)
            try { client.classify(settings,input); fail("expected rejection") }
            catch (e: AiClassificationException) {
                assertEquals(code,e.code)
                assertFalse(e.toString().contains("api-secret"))
                assertFalse(e.toString().contains("access-secret"))
            }
        }
        assertEquals(4,server.requestCount)
    }
    @Test fun cancellingCoroutineCancelsInFlightCallWithoutRetry() = withServer { server,client,settings ->
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2,java.util.concurrent.TimeUnit.SECONDS))
        try {
            kotlinx.coroutines.withTimeout(200) { client.classify(settings,input) }
            fail("request did not cancel")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertNotNull(server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1,server.requestCount)
    }
}
