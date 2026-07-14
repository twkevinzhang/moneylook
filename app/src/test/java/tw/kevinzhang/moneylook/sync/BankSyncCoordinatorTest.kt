package tw.kevinzhang.moneylook.sync

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.login.NativeLoginResult
import tw.kevinzhang.extension_runtime.login.NativeLoginRunner
import tw.kevinzhang.extension_runtime.session.EphemeralSession

class BankSyncCoordinatorTest {
    private val extension = InstalledExtension(
        id = "bank::repo",
        manifestId = "bank",
        name = "Bank",
        version = 1,
        repoUrl = "https://github.com/example/repo",
        syncTriggerCachePath = "/tmp/sync.js",
        loginUrl = "https://bank.example/login",
        targetDomainsJson = "[\"bank.example\"]",
        iconUrl = null,
        loginAutomationJson = """
            {
              "usernameSelector":"#username",
              "passwordSelector":"#password",
              "captchaImageSelector":"#captcha-image",
              "captchaInputSelector":"#captcha",
              "submitSelector":"#submit",
              "successUrlContains":"/accounts"
            }
        """.trimIndent(),
    )
    private val profile = CredentialProfile(
        extensionId = extension.id,
        username = "user",
        password = "password",
        approvedLoginHost = "bank.example",
        approvedDomainsJson = "[\"bank.example\"]",
        scheduleCron = "0 8 * * *",
        timezoneId = "Asia/Taipei",
    )

    @Test
    fun successfulLoginPassesOnlyEphemeralSessionToExtension() = runBlocking {
        val session = EphemeralSession.of(mapOf("bank.example" to "sid=secret"))
        var extensionWasRun = false
        val runner = object : ExtensionRunner {
            override suspend fun run(
                extension: InstalledExtension,
                session: EphemeralSession,
            ): SyncResult {
                extensionWasRun = true
                assertFalse(session.isEmpty)
                return SyncResult.Success(emptyList())
            }
        }
        val coordinator = BankSyncCoordinator(
            nativeLoginRunner = NativeLoginRunner { NativeLoginResult.Success(session) },
            extensionRunner = runner,
            gson = Gson(),
        )

        val result = coordinator.sync(extension, profile)

        assertTrue(result is SyncResult.Success)
        assertTrue(extensionWasRun)
    }

    @Test
    fun failedLoginDoesNotRunExtension() = runBlocking {
        var extensionWasRun = false
        val runner = object : ExtensionRunner {
            override suspend fun run(
                extension: InstalledExtension,
                session: EphemeralSession,
            ): SyncResult {
                extensionWasRun = true
                return SyncResult.Success(emptyList())
            }
        }
        val coordinator = BankSyncCoordinator(
            nativeLoginRunner = NativeLoginRunner { NativeLoginResult.Error("captcha failed") },
            extensionRunner = runner,
            gson = Gson(),
        )

        val result = coordinator.sync(extension, profile)

        assertEquals("captcha failed", (result as SyncResult.Error).message)
        assertFalse(extensionWasRun)
    }

    @Test
    fun changedManifestDomainsRequireNewUserApproval() = runBlocking {
        var loginWasRun = false
        val coordinator = BankSyncCoordinator(
            nativeLoginRunner = NativeLoginRunner {
                loginWasRun = true
                NativeLoginResult.Error("should not run")
            },
            extensionRunner = object : ExtensionRunner {
                override suspend fun run(
                    extension: InstalledExtension,
                    session: EphemeralSession,
                ): SyncResult = SyncResult.Success(emptyList())
            },
            gson = Gson(),
        )
        val changed = extension.copy(targetDomainsJson = "[\"bank.example\",\"new.bank.example\"]")

        val result = coordinator.sync(changed, profile)

        assertTrue((result as SyncResult.Error).message.contains("權限已變更"))
        assertFalse(loginWasRun)
    }
}
