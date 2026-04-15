package tw.kevinzhang.marketplace

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.marketplace.data.ExtensionManifest

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

    @Test
    fun `manifest with syncTrigger and schedule deserializes correctly`() {
        val json = """
            {
              "id": "tw.test",
              "name": "Test",
              "version": 1,
              "versionName": "1.0.0",
              "description": "desc",
              "loginUrl": "https://test.com",
              "targetDomains": ["test.com"],
              "iconUrl": null,
              "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
              "schedule": { "cron": "0 8 * * *", "scriptPath": "schedule.min.js" }
            }
        """.trimIndent()
        val manifest = Gson().fromJson(json, ExtensionManifest::class.java)
        assertEquals("sync-trigger.min.js", manifest.syncTrigger.scriptPath)
        assertEquals("0 8 * * *", manifest.schedule?.cron)
        assertEquals("schedule.min.js", manifest.schedule?.scriptPath)
    }

    @Test
    fun `manifest without schedule block deserializes with null schedule`() {
        val json = """
            {
              "id": "tw.test",
              "name": "Test",
              "version": 1,
              "versionName": "1.0.0",
              "description": "desc",
              "loginUrl": "https://test.com",
              "targetDomains": ["test.com"],
              "iconUrl": null,
              "syncTrigger": { "scriptPath": "sync-trigger.min.js" }
            }
        """.trimIndent()
        val manifest = Gson().fromJson(json, ExtensionManifest::class.java)
        assertNull(manifest.schedule)
    }
}
