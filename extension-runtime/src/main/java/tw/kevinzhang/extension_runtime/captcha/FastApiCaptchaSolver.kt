package tw.kevinzhang.extension_runtime.captcha

import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for a ddddocr-compatible FastAPI `/ocr` endpoint.
 *
 * The complete endpoint URL is supplied at runtime by the app (for example from BuildConfig).
 * No service host or fallback URL is embedded in this module.
 */
class FastApiCaptchaSolver(
    okHttpClient: OkHttpClient,
    private val ocrEndpointUrl: String,
    private val gson: Gson,
) : CaptchaSolver {
    private val client = okHttpClient.newBuilder()
        .apply {
            // Never inherit application logging: captcha bytes and OCR responses are sensitive.
            interceptors().clear()
            networkInterceptors().clear()
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(ocrEndpointUrl.startsWith("https://")) { "OCR endpoint must use HTTPS" }
    }

    override suspend fun solve(imageBytes: ByteArray): CaptchaSolveResult = withContext(Dispatchers.IO) {
        if (imageBytes.isEmpty()) return@withContext CaptchaSolveResult.Error("captcha image is empty")
        if (imageBytes.size > MAX_IMAGE_BYTES) {
            return@withContext CaptchaSolveResult.Error("captcha image exceeds size limit")
        }

        try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "captcha.png",
                    imageBytes.toRequestBody("image/png".toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url(ocrEndpointUrl)
                .post(multipart)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CaptchaSolveResult.Error("OCR service returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.length > MAX_RESPONSE_CHARS) {
                    return@withContext CaptchaSolveResult.Error("OCR response exceeds size limit")
                }
                parseResponse(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CaptchaSolveResult.Error("OCR request failed", e)
        }
    }

    internal fun parseResponse(body: String): CaptchaSolveResult {
        val root = gson.fromJson(body, JsonElement::class.java)?.asJsonObject
            ?: return CaptchaSolveResult.Error("OCR response is empty")
        val code = root.get("code")?.asInt
        if (code != null && code != 0 && code != 200) {
            val message = root.get("message")?.asString ?: "OCR service rejected the image"
            return CaptchaSolveResult.Error(message.take(200))
        }
        val text = extractText(root.get("data"))?.trim().orEmpty()
        return if (text.isEmpty()) CaptchaSolveResult.Error("OCR response has no result")
        else CaptchaSolveResult.Success(text)
    }

    private fun extractText(data: JsonElement?): String? = when {
        data == null || data.isJsonNull -> null
        data.isJsonPrimitive -> data.asString
        data.isJsonObject -> {
            val obj = data.asJsonObject
            listOf("text", "result", "ocr_result")
                .firstNotNullOfOrNull { key -> obj.get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString }
        }
        else -> null
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_CHARS = 64 * 1024
    }
}
