package tw.kevinzhang.core.network.exchange

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Supplies the latest exchange rates quoted as one TWD in each foreign currency.
 * Convert a foreign amount back to TWD with `foreignAmount / rates[currency]`.
 */
interface TwdExchangeRateRepository {
    suspend fun latestRates(): LatestTwdExchangeRates?
}

data class LatestTwdExchangeRates(
    val rates: Map<String, Double>,
    val fetchedAtMillis: Long,
    val source: ExchangeRateSource,
)

enum class ExchangeRateSource {
    NETWORK,
    CACHE,
}

class OpenExchangeRateRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val endpoint: HttpUrl = DEFAULT_ENDPOINT,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TwdExchangeRateRepository {

    override suspend fun latestRates(): LatestTwdExchangeRates? = withContext(Dispatchers.IO) {
        fetchFromNetwork()?.also(::writeCache) ?: readCache()
    }

    private fun fetchFromNetwork(): LatestTwdExchangeRates? = runCatching {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            parseNetworkResponse(body, nowMillis())
        }
    }.getOrNull()

    private fun readCache(): LatestTwdExchangeRates? = runCatching {
        val bytes = AtomicFile(cacheFile).readFully()
        parseCache(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    private fun writeCache(rates: LatestTwdExchangeRates) {
        runCatching {
            val atomicFile = AtomicFile(cacheFile)
            val json = gson.toJson(
                CachedTwdExchangeRates(
                    fetchedAtMillis = rates.fetchedAtMillis,
                    rates = rates.rates,
                ),
            )
            val output = atomicFile.startWrite()
            try {
                output.write(json.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(output)
            } catch (error: IOException) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

    private fun parseNetworkResponse(
        response: String,
        fetchedAtMillis: Long,
    ): LatestTwdExchangeRates? {
        val root = response.asJsonObjectOrNull() ?: return null
        if (root.string("result") != "success" || root.string("base_code") != BASE_CURRENCY) {
            return null
        }
        val rates = parseRates(root["rates"]?.asJsonObjectOrNull() ?: return null) ?: return null
        return LatestTwdExchangeRates(rates, fetchedAtMillis, ExchangeRateSource.NETWORK)
    }

    private fun parseCache(cache: String): LatestTwdExchangeRates? {
        val root = cache.asJsonObjectOrNull() ?: return null
        val fetchedAtMillis = root["fetchedAtMillis"]?.takeIf { it.isJsonPrimitive }
            ?.asLongOrNull()
            ?.takeIf { it >= 0L }
            ?: return null
        val rates = parseRates(root["rates"]?.asJsonObjectOrNull() ?: return null) ?: return null
        return LatestTwdExchangeRates(rates, fetchedAtMillis, ExchangeRateSource.CACHE)
    }

    private fun parseRates(json: JsonObject): Map<String, Double>? {
        if (json.entrySet().isEmpty()) return null
        val parsed = linkedMapOf<String, Double>()
        for ((currency, value) in json.entrySet()) {
            if (!CURRENCY_CODE.matches(currency)) return null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
            val rate = value.asDoubleOrNull() ?: return null
            if (!rate.isFinite() || rate <= 0.0) return null
            parsed[currency.uppercase(Locale.ROOT)] = rate
        }
        return parsed
    }

    private fun String.asJsonObjectOrNull(): JsonObject? = runCatching {
        JsonParser.parseString(this).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun JsonObject.string(name: String): String? = this[name]
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asLongOrNull(): Long? = runCatching {
        if (!asJsonPrimitive.isNumber) null else asLong
    }.getOrNull()

    private fun JsonElement.asDoubleOrNull(): Double? = runCatching { asDouble }.getOrNull()

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    private data class CachedTwdExchangeRates(
        val fetchedAtMillis: Long,
        val rates: Map<String, Double>,
    )

    companion object {
        private val DEFAULT_ENDPOINT: HttpUrl = "https://open.er-api.com/v6/latest/TWD".toHttpUrl()
        private const val BASE_CURRENCY = "TWD"
        private const val CACHE_FILE_NAME = "twd_exchange_rates.json"
        private val CURRENCY_CODE = Regex("[A-Z]{3}")
    }
}
