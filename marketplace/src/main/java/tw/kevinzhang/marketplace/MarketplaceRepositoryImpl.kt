package tw.kevinzhang.marketplace

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionIndexEntryDto
import tw.kevinzhang.marketplace.data.ExtensionManifest
import tw.kevinzhang.marketplace.data.validateAndNormalize
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import javax.inject.Inject

class MarketplaceRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : MarketplaceRepository {

    override suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry> =
        withContext(Dispatchers.IO) {
            val rawBase = toRawBase(repoUrl)
            val json = fetchString("$rawBase/index.min.json")
            val type = object : TypeToken<List<ExtensionIndexEntryDto>>() {}.type
            val dtos: List<ExtensionIndexEntryDto> = gson.fromJson(json, type)
            dtos.map { it.toDomain() }
        }

    override suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest =
        withContext(Dispatchers.IO) {
            val rawBase = toRawBase(repoUrl)
            val json = fetchString("$rawBase/$path/manifest.json")
            gson.fromJson(json, ExtensionManifest::class.java).validateAndNormalize()
        }

    override suspend fun downloadSyncTriggerScript(
        repoUrl: String,
        path: String,
        extensionId: String,
    ): String = withContext(Dispatchers.IO) {
        val rawBase = toRawBase(repoUrl)
        val manifest = gson.fromJson(
            fetchString("$rawBase/$path/manifest.json"),
            ExtensionManifest::class.java,
        ).validateAndNormalize()
        val scriptUrl = "$rawBase/$path/${manifest.syncTrigger.scriptPath}"
        val bytes = fetchBytes(scriptUrl)
        val scriptFile = File(context.filesDir, "extensions/$extensionId/sync-trigger.js")
        check(scriptFile.canonicalPath.startsWith(context.filesDir.canonicalPath)) {
            "Script path escapes filesDir: ${scriptFile.canonicalPath}"
        }
        scriptFile.parentFile?.mkdirs()
        scriptFile.writeBytes(bytes)
        scriptFile.absolutePath
    }

    // Converts https://github.com/owner/repo → https://raw.githubusercontent.com/owner/repo/main
    internal fun toRawBase(repoUrl: String): String {
        val normalized = repoUrl.trimEnd('/')
        val uri = try {
            URI(normalized)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl", exception)
        }
        require(uri.scheme == "https" && uri.rawUserInfo == null) {
            "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
        }
        return when (uri.host?.lowercase(Locale.US)) {
            "raw.githubusercontent.com" -> normalized
            "github.com" -> normalized.replaceFirst(
                "https://github.com/",
                "https://raw.githubusercontent.com/",
            ) + "/main"
            else -> throw IllegalArgumentException(
                "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl",
            )
        }
    }

    private fun fetchString(url: String): String {
        okHttpClient.newCall(buildRequest(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return readBoundedBytes(response.body, MAX_METADATA_BYTES, url).toString(Charsets.UTF_8)
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        okHttpClient.newCall(buildRequest(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return readBoundedBytes(response.body, MAX_SCRIPT_BYTES, url)
        }
    }

    internal fun buildRequest(url: String): Request = Request.Builder()
        .url(
            url.toHttpUrl().newBuilder()
                .addQueryParameter("_moneylook", System.nanoTime().toString())
                .build(),
        )
        .cacheControl(CacheControl.FORCE_NETWORK)
        .build()

    private fun readBoundedBytes(body: okhttp3.ResponseBody?, limit: Long, url: String): ByteArray {
        body ?: throw IOException("Empty response for $url")
        if (body.contentLength() > limit) throw IOException("Response too large for $url")
        val source = body.source()
        source.request(limit + 1L)
        if (source.buffer.size > limit) throw IOException("Response too large for $url")
        return source.buffer.clone().readByteArray()
    }

    private companion object {
        const val MAX_METADATA_BYTES = 1024L * 1024
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    }
}
