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
class AiClassificationClient(client: OkHttpClient) {
    private val client = client.newBuilder().callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    @Inject constructor() : this(OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())

    suspend fun classify(settings: AiSettings, input: AiClassificationInput): AiCategoryProposal =
        execute(AiProtocol.request(settings, input)) { body ->
            AiProtocol.parse(AiProtocol.content(settings.provider, body), input).copy(provider = settings.provider.name, model = settings.model)
        }

    suspend fun classifyBatch(settings: AiSettings, items: List<AiBatchItem>): AiBatchResult =
        execute(AiBatchProtocol.request(settings, items)) { body ->
            val result = AiBatchProtocol.parse(AiProtocol.content(settings.provider, body), items)
            result.copy(proposals = result.proposals.mapValues { (_, proposal) ->
                proposal.copy(provider = settings.provider.name, model = settings.model)
            })
        }

    private suspend fun <T> execute(request: Request, parse: (String) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
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
                        parse(body)
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
