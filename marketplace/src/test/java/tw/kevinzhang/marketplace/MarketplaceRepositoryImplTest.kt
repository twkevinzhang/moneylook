package tw.kevinzhang.marketplace

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
              "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
              "schedule": {
                "suggestedCron": "0 8 * * *",
                "suggestedTimezone": "Asia/Taipei"
              }
            }
        """.trimIndent()
        val manifest = Gson().fromJson(json, ExtensionManifest::class.java)
        assertEquals("sync-trigger.min.js", manifest.syncTrigger.scriptPath)
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

    private fun manifest(
        scriptPath: String = "sync-trigger.min.js",
    ) = ExtensionManifest(
        id = "tw.test",
        name = "Test",
        version = 1,
        versionName = "1.0.0",
        description = "desc",
        syncTrigger = ExtensionManifest.SyncTriggerConfig(scriptPath),
        schedule = ExtensionManifest.ScheduleConfig(
            suggestedCron = "0 8 * * *",
            suggestedTimezone = "Asia/Taipei",
        ),
        iconUrl = null,
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
