package tw.kevinzhang.marketplace

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionIndexEntryDto
import tw.kevinzhang.marketplace.data.ExtensionManifest
import tw.kevinzhang.marketplace.data.validateAndNormalize
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.io.FileOutputStream
import javax.inject.Inject

class MarketplaceRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : MarketplaceRepository {

    override suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry> =
        withContext(Dispatchers.IO) {
            val rawBase = immutableRawBase(repoUrl, refreshRevision = true)
            val json = fetchString("$rawBase/index.min.json")
            val type = object : TypeToken<List<ExtensionIndexEntryDto>>() {}.type
            val dtos: List<ExtensionIndexEntryDto> = gson.fromJson(json, type)
            dtos.map { dto ->
                requireSafeRelativePath(dto.path, "index.path")
                dto.toDomain()
            }
        }

    override suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest =
        withContext(Dispatchers.IO) {
            val rawBase = immutableRawBase(repoUrl)
            val safePath = requireSafeRelativePath(path, "index.path")
            val json = fetchString("$rawBase/$safePath/manifest.json")
            gson.fromJson(json, ExtensionManifest::class.java).validateAndNormalize()
        }

    override suspend fun downloadSyncTriggerScript(
        repoUrl: String,
        path: String,
        extensionId: String,
    ): DownloadedExtensionArtifact = withContext(Dispatchers.IO) {
        val rawBase = immutableRawBase(repoUrl)
        val safePath = requireSafeRelativePath(path, "index.path")
        val manifest = gson.fromJson(
            fetchString("$rawBase/$safePath/manifest.json"),
            ExtensionManifest::class.java,
        ).validateAndNormalize()
        val scriptUrl = "$rawBase/$safePath/${manifest.syncTrigger.scriptPath}"
        val bytes = fetchBytes(scriptUrl)
        val extensionsRoot = File(context.filesDir, "extensions").canonicalFile
        val expectedSha256 = bytes.sha256()
        val scriptFile = File(
            extensionsRoot,
            "$extensionId/artifacts/$expectedSha256.js",
        ).canonicalFile
        check(scriptFile.path.startsWith(extensionsRoot.path + File.separator)) {
            "Script path escapes extensions directory"
        }
        val parent = requireNotNull(scriptFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Unable to create extension directory" }
        installAtomically(scriptFile, bytes, expectedSha256)
        DownloadedExtensionArtifact(
            path = scriptFile.absolutePath,
            immutableRevision = rawBase.substringAfterLast('/'),
            sha256 = expectedSha256,
        )
    }

    // Converts a supported repository URL to its conventional branch-based Raw URL. Runtime
    // downloads use immutableRawBase() so a CDN hit can never return content from another commit.
    internal fun toRawBase(repoUrl: String): String {
        val repository = parseGitHubRepository(repoUrl)
        return "$RAW_CONTENT_BASE/${repository.owner}/${repository.name}/${repository.revision ?: DEFAULT_BRANCH}"
    }

    private fun immutableRawBase(
        repoUrl: String,
        refreshRevision: Boolean = false,
    ): String {
        val repository = parseGitHubRepository(repoUrl)
        val revision = repository.revision ?: run {
            val key = "${repository.owner}/${repository.name}"
            if (!refreshRevision) resolvedRevisions[key]?.let { return@run it }
            resolveDefaultBranchRevision(repository).also { resolvedRevisions[key] = it }
        }
        return "$RAW_CONTENT_BASE/${repository.owner}/${repository.name}/$revision"
    }

    private fun resolveDefaultBranchRevision(repository: GitHubRepository): String {
        val url = "$GITHUB_API_BASE/repos/${repository.owner}/${repository.name}/git/ref/heads/$DEFAULT_BRANCH"
        val response = gson.fromJson(fetchString(url), GitHubRefResponse::class.java)
        val revision = response.gitObject?.sha?.lowercase(Locale.US).orEmpty()
        if (!COMMIT_SHA.matches(revision)) {
            throw IOException("GitHub ref response did not contain a valid commit SHA")
        }
        return revision
    }

    private fun parseGitHubRepository(repoUrl: String): GitHubRepository {
        val normalized = repoUrl.trimEnd('/')
        val uri = try {
            URI(normalized)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl", exception)
        }
        require(uri.scheme == "https" && uri.rawUserInfo == null) {
            "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
        }
        require(uri.rawQuery == null && uri.rawFragment == null && '%' !in uri.rawPath) {
            "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
        }
        val segments = uri.rawPath.trim('/').split('/').filter { it.isNotBlank() }
        val repository = when (uri.host?.lowercase(Locale.US)) {
            "github.com" -> {
                require(segments.size == 2) {
                    "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
                }
                GitHubRepository(segments[0], segments[1].removeSuffix(".git"), null)
            }
            "raw.githubusercontent.com" -> {
                require(segments.size == 3) {
                    "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
                }
                val revision = segments[2]
                require(revision == DEFAULT_BRANCH || COMMIT_SHA.matches(revision.lowercase(Locale.US))) {
                    "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
                }
                GitHubRepository(
                    segments[0],
                    segments[1],
                    revision.takeIf { it != DEFAULT_BRANCH }?.lowercase(Locale.US),
                )
            }
            else -> throw IllegalArgumentException(
                "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl",
            )
        }
        require(
            REPOSITORY_SEGMENT.matches(repository.owner) &&
                REPOSITORY_SEGMENT.matches(repository.name) &&
                repository.owner !in DOT_SEGMENTS &&
                repository.name !in DOT_SEGMENTS,
        ) {
            "Unsupported repo URL (only HTTPS GitHub is supported): $repoUrl"
        }
        return repository
    }

    private fun requireSafeRelativePath(path: String, field: String): String {
        require(path.isNotBlank() && '\\' !in path && '%' !in path) {
            "$field must be a safe relative path"
        }
        val uri = try {
            URI(path)
        } catch (exception: Exception) {
            throw IllegalArgumentException("$field is invalid", exception)
        }
        require(
            !uri.isAbsolute &&
                uri.rawAuthority == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                !path.startsWith('/') &&
                path.split('/').none { it.isBlank() || it in DOT_SEGMENTS },
        ) {
            "$field must be a safe relative path"
        }
        return path
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
        .url(url.toHttpUrl())
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

    /**
     * Writes and fsyncs a same-directory temporary file before rename. Content-addressed targets
     * are immutable, so the InstalledExtension row continues pointing to its previous artifact
     * until the caller successfully persists the returned path.
     */
    private fun installAtomically(target: File, bytes: ByteArray, expectedSha256: String) {
        val parent = requireNotNull(target.parentFile)
        if (target.exists()) {
            check(target.readBytes().sha256() == expectedSha256) {
                "Existing content-addressed artifact digest mismatch"
            }
            return
        }
        val temp = File.createTempFile(".sync-trigger-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temp.readBytes().sha256() == expectedSha256) { "Downloaded artifact digest mismatch" }
            if (!temp.renameTo(target)) {
                // A concurrent installer may have won the same immutable digest.
                check(target.exists() && target.readBytes().sha256() == expectedSha256) {
                    "Unable to install content-addressed artifact"
                }
            }
            check(target.readBytes().sha256() == expectedSha256) { "Installed artifact digest mismatch" }
        } finally {
            temp.delete()
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val DEFAULT_BRANCH = "main"
        const val GITHUB_API_BASE = "https://api.github.com"
        const val RAW_CONTENT_BASE = "https://raw.githubusercontent.com"
        const val MAX_METADATA_BYTES = 1024L * 1024
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
        val REPOSITORY_SEGMENT = Regex("^[A-Za-z0-9_.-]+$")
        val DOT_SEGMENTS = setOf(".", "..")
    }

    private data class GitHubRepository(
        val owner: String,
        val name: String,
        val revision: String?,
    )

    private data class GitHubRefResponse(
        @SerializedName("object") val gitObject: GitHubRefObject?,
    )

    private data class GitHubRefObject(val sha: String?)

    private val resolvedRevisions = ConcurrentHashMap<String, String>()
}
