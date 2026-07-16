package tw.kevinzhang.marketplace

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
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
    fun `marketplace downloads revalidate github raw content`() {
        val request = repo.buildRequest("https://raw.githubusercontent.com/owner/repo/main/index.min.json")

        assertEquals("no-cache", request.header("Cache-Control"))
        assertTrue(request.url.queryParameter("_moneylook")?.isNotBlank() == true)
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
}
