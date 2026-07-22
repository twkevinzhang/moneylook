package tw.kevinzhang.core.network.exchange

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenExchangeRateRepositoryTest {

    private val server = MockWebServer()
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.filesDir.resolve(CACHE_FILE_NAME).delete()
        context.filesDir.resolve("$CACHE_FILE_NAME.bak").delete()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        context.filesDir.resolve(CACHE_FILE_NAME).delete()
        context.filesDir.resolve("$CACHE_FILE_NAME.bak").delete()
    }

    @Test
    fun `network response with valid TWD base is returned and cached`() = runBlocking {
        server.enqueue(successResponse())
        val repository = repository(nowMillis = { 42L })

        val result = repository.latestRates()

        requireNotNull(result)
        assertEquals(ExchangeRateSource.NETWORK, result.source)
        assertEquals(42L, result.fetchedAtMillis)
        assertEquals(1.0, result.rates["TWD"])
        assertEquals(0.2, result.rates["USD"])
        assertEquals("/v6/latest/TWD", server.takeRequest().path)
        assertTrue(context.filesDir.resolve(CACHE_FILE_NAME).isFile)
    }

    @Test
    fun `falls back to last valid cache when the network request fails`() = runBlocking {
        server.enqueue(successResponse())
        val repository = repository(nowMillis = { 99L })
        repository.latestRates()

        server.enqueue(MockResponse().setResponseCode(503))
        val cached = repository.latestRates()

        requireNotNull(cached)
        assertEquals(ExchangeRateSource.CACHE, cached.source)
        assertEquals(99L, cached.fetchedAtMillis)
        assertEquals(0.2, cached.rates["USD"])
    }

    @Test
    fun `rejects unsuccessful response invalid base and nonpositive or nonfinite rates`() = runBlocking {
        val invalidBodies = listOf(
            """{"result":"error","base_code":"TWD","rates":{"USD":0.2}}""",
            """{"result":"success","base_code":"USD","rates":{"USD":0.2}}""",
            """{"result":"success","base_code":"TWD","rates":{"USD":0}}""",
            """{"result":"success","base_code":"TWD","rates":{"USD":-1}}""",
            """{"result":"success","base_code":"TWD","rates":{"USD":1e999}}""",
        )

        invalidBodies.forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertNull(repository().latestRates())
        }
    }

    @Test
    fun `rejects invalid cache when no valid network response is available`() = runBlocking {
        context.filesDir.resolve(CACHE_FILE_NAME).writeText(
            """{"fetchedAtMillis":1,"rates":{"USD":0}}""",
        )
        server.enqueue(MockResponse().setResponseCode(503))

        assertNull(repository().latestRates())
    }

    private fun repository(nowMillis: () -> Long = { 1L }): OpenExchangeRateRepository =
        OpenExchangeRateRepository(
            context = context,
            okHttpClient = OkHttpClient(),
            gson = Gson(),
            endpoint = server.url("/v6/latest/TWD"),
            nowMillis = nowMillis,
        )

    private fun successResponse(): MockResponse = MockResponse().setBody(
        """
        {
          "result": "success",
          "base_code": "TWD",
          "rates": { "TWD": 1, "USD": 0.2, "JPY": 5 }
        }
        """.trimIndent(),
    )

    private companion object {
        const val CACHE_FILE_NAME = "twd_exchange_rates.json"
    }
}
