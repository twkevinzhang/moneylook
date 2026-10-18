package tw.kevinzhang.moneylook.ai

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AiBatchClassificationClientTest {
    private val items = listOf(AiBatchItem("one", AiClassificationInput("咖啡", -80.0, "TWD", listOf(Category("food", "餐飲", "#000000")))))
    private fun withServer(block: suspend (MockWebServer,AiClassificationClient,AiSettings,AtomicBoolean)->Unit) = runBlocking {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val tls=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(),false)
            server.start()
            val cancelled=AtomicBoolean()
            val transport=OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(),tls.trustManager)
                // All provider URLs are routed to this local TLS fixture; no paid endpoint is contacted.
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                    .url(chain.request().url.newBuilder().host("localhost").port(server.port).build()).build()) }
                .callTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .eventListener(object: EventListener() { override fun canceled(call: Call) { cancelled.set(true) } }).build()
            block(server,AiClassificationClient(transport),AiSettings(AiProvider.CUSTOM,"test-model",server.url("/v1/").newBuilder().host("localhost").build().toString(),"secret"),cancelled)
        }
    }
    private fun envelope(text:String) = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to text)))))
    @Test fun makesOneHttpsCallAndAttachesProviderMetadata() = withServer { server,client,settings,_ ->
        server.enqueue(MockResponse().setBody(envelope("""{"results":[{"id":"one","categoryId":"food","reason":"咖啡"}]}""")))
        val result=client.classifyBatch(settings,items)
        assertEquals(setOf("one"),result.proposals.keys)
        assertEquals("CUSTOM",result.proposals.getValue("one").provider)
        assertEquals("test-model",result.proposals.getValue("one").model)
        assertEquals(1,server.requestCount)
        assertEquals("Bearer secret",server.takeRequest().getHeader("Authorization"))
    }
    @Test fun rejectsRedirectAndOversizedResponseWithoutRetries() = withServer { server,client,settings,_ ->
        for ((response,code) in listOf(MockResponse().setResponseCode(302).addHeader("Location","https://untrusted.example") to AiFailureCode.ACCESS_OR_AUTH,
            MockResponse().setBody("x".repeat(65537)) to AiFailureCode.INVALID_RESPONSE)) {
            server.enqueue(response)
            try { client.classifyBatch(settings,items); fail("accepted invalid response") }
            catch(e:AiClassificationException) { assertEquals(code,e.code) }
        }
        assertEquals(2,server.requestCount)
    }
    @Test fun readsEveryProviderEnvelopeOverLocalHttps() = withServer { server,client,settings,_ ->
        val answer="""{"results":[{"id":"one","categoryId":"food","reason":"商家"}]}"""
        for(provider in AiProvider.entries) {
            val quoted=Gson().toJson(answer)
            val response=when(provider) {
                AiProvider.GEMINI -> """{"candidates":[{"content":{"parts":[{"text":$quoted}]}}]}"""
                AiProvider.CLAUDE -> """{"content":[{"type":"text","text":$quoted}]}"""
                else -> envelope(answer)
            }
            server.enqueue(MockResponse().setBody(response))
            assertEquals(provider.name,client.classifyBatch(settings.copy(provider=provider),items).proposals.getValue("one").provider)
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
        }
        assertEquals(4,server.requestCount)
    }
    @Test fun cancellationCancelsActualCall() = withServer { server,client,settings,cancelled ->
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2,TimeUnit.SECONDS))
        try { withTimeout(250) { client.classifyBatch(settings,items) }; fail("not cancelled") }
        catch(_:TimeoutCancellationException) { }
        assertTrue(cancelled.get())
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
        assertEquals(1,server.requestCount)
    }
}
