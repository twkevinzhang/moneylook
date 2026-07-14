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
    fun `manifest with login automation and schedule deserializes correctly`() {
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
              "loginAutomation": {
                "usernameSelector": "#username",
                "passwordSelector": "#password",
                "captchaImageSelector": "#captcha-image",
                "captchaInputSelector": "#captcha-input",
                "submitSelector": "button[type=submit]",
                "successUrlContains": "/accounts",
                "postSubmitDelayMs": 1000
              },
              "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
              "schedule": {
                "suggestedCron": "0 8 * * *",
                "suggestedTimezone": "Asia/Taipei"
              }
            }
        """.trimIndent()
        val manifest = Gson().fromJson(json, ExtensionManifest::class.java)
        assertEquals("#username", manifest.loginAutomation.usernameSelector)
        assertEquals("#captcha-image", manifest.loginAutomation.captchaImageSelector)
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
              "loginUrl": "https://test.com",
              "targetDomains": ["test.com"],
              "iconUrl": null,
              "loginAutomation": {
                "usernameSelector": "#username",
                "passwordSelector": "#password",
                "captchaImageSelector": "#captcha-image",
                "captchaInputSelector": "#captcha-input",
                "submitSelector": "#submit",
                "successUrlContains": "/accounts"
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
    fun `validation normalizes domains and accepts login subdomain`() {
        val result = manifest(
            loginUrl = "https://login.bank.example/account",
            targetDomains = listOf(" BANK.Example. ", "bank.example"),
        ).validateAndNormalize()

        assertEquals(listOf("bank.example"), result.targetDomains)
    }

    @Test
    fun `validation rejects non HTTPS login url`() {
        assertInvalid {
            manifest(loginUrl = "http://bank.example").validateAndNormalize()
        }
    }

    @Test
    fun `validation rejects login host substring match`() {
        assertInvalid {
            manifest(loginUrl = "https://bank.example.attacker.test").validateAndNormalize()
        }
    }

    @Test
    fun `validation rejects localhost and IP target domains`() {
        listOf("localhost", "api.localhost", "127.0.0.1", "::1", "2130706433").forEach { domain ->
            assertInvalid {
                manifest(loginUrl = "https://$domain", targetDomains = listOf(domain))
                    .validateAndNormalize()
            }
        }
    }

    @Test
    fun `validation rejects blank login selectors`() {
        assertInvalid {
            manifest(
                loginAutomation = loginAutomation().copy(captchaInputSelector = " "),
            ).validateAndNormalize()
        }
    }

    private fun manifest(
        loginUrl: String = "https://bank.example/login",
        targetDomains: List<String> = listOf("bank.example"),
        loginAutomation: ExtensionManifest.LoginAutomationConfig = loginAutomation(),
    ) = ExtensionManifest(
        id = "tw.test",
        name = "Test",
        version = 1,
        versionName = "1.0.0",
        description = "desc",
        loginUrl = loginUrl,
        targetDomains = targetDomains,
        loginAutomation = loginAutomation,
        syncTrigger = ExtensionManifest.SyncTriggerConfig(),
        schedule = ExtensionManifest.ScheduleConfig(
            suggestedCron = "0 8 * * *",
            suggestedTimezone = "Asia/Taipei",
        ),
        iconUrl = null,
    )

    private fun loginAutomation() = ExtensionManifest.LoginAutomationConfig(
        usernameSelector = "#username",
        passwordSelector = "#password",
        captchaImageSelector = "#captcha-image",
        captchaInputSelector = "#captcha-input",
        submitSelector = "#submit",
        successUrlContains = "/accounts",
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
