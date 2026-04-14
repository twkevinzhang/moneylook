package tw.kevinzhang.marketplace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionIndexEntryDto
import tw.kevinzhang.marketplace.data.ExtensionManifest
import java.io.File
import java.io.IOException
import android.content.Context
import javax.inject.Inject

class MarketplaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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
            gson.fromJson(json, ExtensionManifest::class.java)
        }

    override suspend fun downloadScript(repoUrl: String, path: String, extensionId: String): String =
        withContext(Dispatchers.IO) {
            val rawBase = toRawBase(repoUrl)
            val manifest = gson.fromJson(
                fetchString("$rawBase/$path/manifest.json"),
                ExtensionManifest::class.java
            )
            val scriptUrl = "$rawBase/$path/${manifest.scriptPath}"
            val bytes = fetchBytes(scriptUrl)
            val scriptFile = File(context.filesDir, "extensions/$extensionId/script.js")
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeBytes(bytes)
            scriptFile.absolutePath
        }

    // Converts https://github.com/owner/repo → https://raw.githubusercontent.com/owner/repo/main
    internal fun toRawBase(repoUrl: String): String {
        val normalized = repoUrl.trimEnd('/')
        return if (normalized.contains("raw.githubusercontent.com")) {
            normalized
        } else {
            normalized
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("http://github.com/", "https://raw.githubusercontent.com/") + "/main"
        }
    }

    private fun fetchString(url: String): String {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty response for $url")
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: throw IOException("Empty response for $url")
        }
    }
}
