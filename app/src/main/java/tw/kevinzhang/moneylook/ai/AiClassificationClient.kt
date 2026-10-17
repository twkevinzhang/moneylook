package tw.kevinzhang.moneylook.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import tw.kevinzhang.core.data.model.*

@Singleton
class AiClassificationClient(private val client: OkHttpClient) {
    @Inject constructor() : this(OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())

    suspend fun classify(settings: AiSettings, input: AiClassificationInput): AiCategoryProposal = suspendCancellableCoroutine { continuation ->
        val call = try { client.newCall(AiProtocol.request(settings, input)) } catch (e: AiClassificationException) {
            continuation.resumeWithException(e); return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AiClassificationException(AiFailureCode.TIMEOUT))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val proposal = response.use {
                        if (!it.isSuccessful) throw AiClassificationException(when (it.code) {
                            in 300..399, 401, 403 -> AiFailureCode.ACCESS_OR_AUTH
                            404 -> AiFailureCode.MODEL_OR_ENDPOINT
                            429 -> AiFailureCode.RATE_LIMIT
                            else -> AiFailureCode.SERVER
                        })
                        val source = it.body?.source() ?: throw AiClassificationException(AiFailureCode.INVALID_RESPONSE)
                        source.request(65537)
                        if (source.buffer.size > 65536) throw AiClassificationException(AiFailureCode.INVALID_RESPONSE)
                        val body = source.readUtf8()
                        if (body.trimStart().startsWith("<")) throw AiClassificationException(AiFailureCode.ACCESS_OR_AUTH)
                        AiProtocol.parse(AiProtocol.content(settings.provider, body), input).copy(provider = settings.provider.name, model = settings.model)
                    }
                    if (continuation.isActive) continuation.resume(proposal)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (e is AiClassificationException) e else AiClassificationException(AiFailureCode.INVALID_RESPONSE))
                }
            }
        })
    }

    suspend fun testConnection(settings: AiSettings) = withTimeout(30_000L) {
        classify(settings, AiClassificationInput("合成測試：咖啡店消費", -60.0, "TWD",
            listOf(Category("test-food", "餐飲", "#607D8B"))))
        Unit
    }
}
