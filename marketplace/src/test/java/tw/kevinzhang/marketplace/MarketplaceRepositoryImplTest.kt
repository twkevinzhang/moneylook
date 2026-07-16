package tw.kevinzhang.marketplace

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.marketplace.data.ExtensionManifest
import tw.kevinzhang.marketplace.data.validateAndNormalize

@RunWith(RobolectricTestRunner::class)
class MarketplaceRepositoryImplTest {

    private val repo = MarketplaceRepositoryImpl(
        context = RuntimeEnvironment.getApplication(),
        okHttpClient = OkHttpClient(),
        gson = Gson(),
    )

    @Test
    fun `github url converts to raw base`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            repo.toRawBase("https://github.com/twkevinzhang/moneylook-extensions")
        )
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            repo.toRawBase("https://github.com/twkevinzhang/moneylook-extensions/")
        )
    }

    @Test
    fun `already raw url is unchanged`() {
        val rawUrl = "https://raw.githubusercontent.com/owner/repo/main"
        assertEquals(rawUrl, repo.toRawBase(rawUrl))
    }

    @Test
    fun `marketplace request bypasses local cache without relying on query cache keys`() {
        val request = repo.buildRequest("https://raw.githubusercontent.com/owner/repo/main/index.min.json")

        assertEquals("no-cache", request.header("Cache-Control"))
        assertEquals(null, request.url.query)
    }

    @Test
    fun `index resolves main ref and downloads from immutable commit raw url`() = runBlocking {
        val revision = "0123456789abcdef0123456789abcdef01234567"
        val requests = mutableListOf<String>()
        val immutableRepo = repositoryWithResponses(requests) { path ->
            when (path) {
                "/repos/owner/repo/git/ref/heads/main" ->
                    "{\"object\":{\"sha\":\"$revision\"}}"
                "/owner/repo/$revision/index.min.json" -> "[]"
                else -> error("Unexpected request path: $path")
            }
        }

        assertTrue(immutableRepo.fetchIndex("https://github.com/owner/repo").isEmpty())

        assertEquals(
            listOf(
                "https://api.github.com/repos/owner/repo/git/ref/heads/main",
                "https://raw.githubusercontent.com/owner/repo/$revision/index.min.json",
            ),
            requests,
        )
    }

    @Test
    fun `cached revision keeps manifest and script on the same immutable commit`() = runBlocking {
        val revision = "abcdef0123456789abcdef0123456789abcdef01"
        val requests = mutableListOf<String>()
        val manifest = """
            {
              "id":"tw.test","name":"Test","version":3,"versionName":"3.0.0",
              "description":"desc","iconUrl":null,
              "credential":{"fields":[{"key":"account","label":"Account","type":"text","required":true,"summary":true}]},
              "syncTrigger":{"scriptPath":"sync.js"}
            }
        """.trimIndent()
        val immutableRepo = repositoryWithResponses(requests) { path ->
            when (path) {
                "/repos/owner/repo/git/ref/heads/main" ->
                    "{\"object\":{\"sha\":\"$revision\"}}"
                "/owner/repo/$revision/index.min.json" -> "[]"
                "/owner/repo/$revision/tw.test/manifest.json" -> manifest
                "/owner/repo/$revision/tw.test/sync.js" -> "globalThis.__moneylookResult = Promise.resolve({accounts: []});"
                else -> error("Unexpected request path: $path")
            }
        }

        immutableRepo.fetchIndex("https://github.com/owner/repo")
        immutableRepo.fetchManifest("https://github.com/owner/repo", "tw.test")
        immutableRepo.downloadSyncTriggerScript(
            "https://github.com/owner/repo",
            "tw.test",
            "tw.test::https://github.com/owner/repo",
        )

        assertEquals(1, requests.count { it.contains("/git/ref/heads/main") })
        assertTrue(requests.drop(1).all { it.contains("/$revision/") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-github url throws IllegalArgumentException`() {
        repo.toRawBase("https://gitlab.com/owner/repo")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `insecure github url throws IllegalArgumentException`() {
        repo.toRawBase("http://github.com/owner/repo")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raw github hostname in attacker path throws IllegalArgumentException`() {
        repo.toRawBase("https://attacker.test/raw.githubusercontent.com/owner/repo/main")
    }

    @Test
    fun `repository url rejects dot segments`() {
        listOf(
            "https://github.com/../repo",
            "https://github.com/owner/..",
            "https://raw.githubusercontent.com/owner/../main",
        ).forEach { url ->
            assertInvalid { repo.toRawBase(url) }
        }
    }

    @Test
    fun `index rejects unsafe extension paths before returning entries`() = runBlocking {
        val revision = "0123456789abcdef0123456789abcdef01234567"
        val unsafePaths = listOf("", "../tw.test", "/tw.test", "tw.test?ref=1", "tw.test//nested", "tw.test%2fother")

        unsafePaths.forEach { unsafePath ->
            val immutableRepo = repositoryWithResponses(mutableListOf()) { path ->
                when (path) {
                    "/repos/owner/repo/git/ref/heads/main" ->
                        "{\"object\":{\"sha\":\"$revision\"}}"
                    "/owner/repo/$revision/index.min.json" ->
                        "[{\"id\":\"tw.test\",\"name\":\"Test\",\"version\":1,\"versionName\":\"1.0.0\",\"path\":\"$unsafePath\"}]"
                    else -> error("Unexpected request path: $path")
                }
            }

            assertInvalid {
                runBlocking { immutableRepo.fetchIndex("https://github.com/owner/repo") }
            }
        }
    }

    @Test
    fun `manifest with script and schedule deserializes correctly`() {
        val json = """
            {
              "id": "tw.test",
              "name": "Test",
              "version": 1,
              "versionName": "1.0.0",
              "description": "desc",
              "iconUrl": null,
              "credential": {
                "fields": [
                  { "key": "account", "label": "帳號", "type": "text", "required": true, "summary": true },
                  { "key": "secret", "label": "密碼", "type": "password", "required": true, "summary": false }
                ]
              },
              "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
              "schedule": {
                "suggestedCron": "0 8 * * *",
                "suggestedTimezone": "Asia/Taipei"
              }
            }
        """.trimIndent()
        val manifest = Gson().fromJson(json, ExtensionManifest::class.java)
        assertEquals("sync-trigger.min.js", manifest.syncTrigger.scriptPath)
        assertEquals(listOf("account", "secret"), manifest.credential.fields.map { it.key })
        assertEquals("0 8 * * *", manifest.schedule?.suggestedCron)
        assertEquals("Asia/Taipei", manifest.schedule?.suggestedTimezone)
    }

    @Test
    fun `legacy schedule cron is accepted without schedule script`() {
        val json = """
            {
              "id": "tw.test",
              "name": "Test",
              "version": 1,
              "versionName": "1.0.0",
              "description": "desc",
              "iconUrl": null,
              "credential": {
                "fields": [
                  { "key": "account", "label": "帳號", "type": "text", "required": true, "summary": true }
                ]
              },
              "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
              "schedule": { "cron": "0 8 * * *", "scriptPath": "schedule.min.js" }
            }
        """.trimIndent()
        val manifest = Gson().fromJson(json, ExtensionManifest::class.java)
        assertEquals("0 8 * * *", manifest.schedule?.suggestedCron)
        assertEquals("Asia/Taipei", manifest.schedule?.suggestedTimezone)
    }

    @Test
    fun `validation accepts safe nested script path`() {
        manifest(scriptPath = "dist/sync-trigger.min.js").validateAndNormalize()
    }

    @Test
    fun `validation rejects unsafe script paths`() {
        listOf("", "../sync.js", "/sync.js", "https://attacker.test/sync.js", "sync.js?raw=1", "a//sync.js").forEach { path ->
            assertInvalid {
                manifest(scriptPath = path).validateAndNormalize()
            }
        }
    }

    @Test
    fun `validation rejects blank basic fields`() {
        assertInvalid {
            manifest().copy(name = " ").validateAndNormalize()
        }
    }

    @Test
    fun `validation accepts supported credential fields`() {
        manifest().validateAndNormalize()
    }

    @Test
    fun `validation rejects missing credential`() {
        val json = Gson().toJsonTree(manifest()).asJsonObject.apply { remove("credential") }
        val parsed = Gson().fromJson(json, ExtensionManifest::class.java)

        assertInvalid { parsed.validateAndNormalize() }
    }

    @Test
    fun `validation rejects credential field missing required flags`() {
        listOf("required", "summary").forEach { property ->
            val json = Gson().toJsonTree(manifest()).asJsonObject
            json.getAsJsonObject("credential")
                .getAsJsonArray("fields")[0]
                .asJsonObject
                .remove(property)
            val parsed = Gson().fromJson(json, ExtensionManifest::class.java)

            assertInvalid { parsed.validateAndNormalize() }
        }
    }

    @Test
    fun `validation rejects empty or excessive credential fields`() {
        assertInvalid {
            manifest(fields = emptyList()).validateAndNormalize()
        }
        assertInvalid {
            manifest(fields = List(17) { credentialField(key = "field_$it") }).validateAndNormalize()
        }
    }

    @Test
    fun `validation rejects invalid or duplicate credential keys`() {
        listOf("", "_startsWithUnderscore", "has-dash", "starts with space", "9startsWithNumber", "a".repeat(65)).forEach { key ->
            assertInvalid {
                manifest(fields = listOf(credentialField(key = key))).validateAndNormalize()
            }
        }
        assertInvalid {
            manifest(
                fields = listOf(
                    credentialField(key = "account"),
                    credentialField(key = "account"),
                ),
            ).validateAndNormalize()
        }
    }

    @Test
    fun `validation rejects unsupported type blank label and password summary`() {
        assertInvalid {
            manifest(fields = listOf(credentialField(type = "hidden"))).validateAndNormalize()
        }
        assertInvalid {
            manifest(fields = listOf(credentialField(label = " "))).validateAndNormalize()
        }
        assertInvalid {
            manifest(fields = listOf(credentialField(label = "a".repeat(81)))).validateAndNormalize()
        }
        assertInvalid {
            manifest(
                fields = listOf(
                    credentialField(type = "password", summary = true),
                ),
            ).validateAndNormalize()
        }
    }

    @Test
    fun `credential fields serialize as installable JSON array`() {
        val fields = manifest().credential.fields
        val json = Gson().toJson(fields)
        val parsed = JsonParser.parseString(json).asJsonArray

        assertEquals(2, parsed.size())
        assertEquals("account", parsed[0].asJsonObject.get("key").asString)
        assertTrue(parsed[0].asJsonObject.get("required").asBoolean)
        assertEquals("password", parsed[1].asJsonObject.get("type").asString)
        assertTrue(!parsed[1].asJsonObject.get("summary").asBoolean)
    }

    private fun manifest(
        scriptPath: String = "sync-trigger.min.js",
        fields: List<ExtensionManifest.CredentialField> = listOf(
            credentialField(key = "account", label = "帳號", summary = true),
            credentialField(key = "secret", label = "密碼", type = "password"),
        ),
    ) = ExtensionManifest(
        id = "tw.test",
        name = "Test",
        version = 1,
        versionName = "1.0.0",
        description = "desc",
        credential = ExtensionManifest.CredentialConfig(fields),
        syncTrigger = ExtensionManifest.SyncTriggerConfig(scriptPath),
        schedule = ExtensionManifest.ScheduleConfig(
            suggestedCron = "0 8 * * *",
            suggestedTimezone = "Asia/Taipei",
        ),
        iconUrl = null,
    )

    private fun credentialField(
        key: String = "account",
        label: String = "帳號",
        type: String = "text",
        required: Boolean = true,
        summary: Boolean = false,
    ) = ExtensionManifest.CredentialField(
        key = key,
        label = label,
        type = type,
        required = required,
        summary = summary,
    )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected manifest validation to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun repositoryWithResponses(
        requests: MutableList<String>,
        bodyForPath: (String) -> String,
    ): MarketplaceRepositoryImpl {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request.url.toString()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyForPath(request.url.encodedPath).toResponseBody())
                    .build()
            })
            .build()
        return MarketplaceRepositoryImpl(
            context = RuntimeEnvironment.getApplication(),
            okHttpClient = client,
            gson = Gson(),
        )
    }
}
