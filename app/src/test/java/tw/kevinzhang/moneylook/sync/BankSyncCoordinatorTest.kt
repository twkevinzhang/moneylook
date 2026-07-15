package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.ExtensionCredentials
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult

class BankSyncCoordinatorTest {
    private val extension = InstalledExtension(
        id = "bank::repo",
        manifestId = "bank",
        name = "Bank",
        version = 1,
        repoUrl = "https://github.com/example/repo",
        syncTriggerCachePath = "/tmp/sync.js",
        iconUrl = null,
    )
    private val profile = CredentialProfile(
        extensionId = extension.id,
        username = "user",
        password = "password",
        scheduleCron = "0 8 * * *",
        timezoneId = "Asia/Taipei",
    )

    @Test
    fun passesPlaintextCredentialsDirectlyToExtension() = runBlocking {
        var receivedExtension: InstalledExtension? = null
        var receivedCredentials: ExtensionCredentials? = null
        val expected = SyncResult.Success(emptyList())
        val coordinator = BankSyncCoordinator(
            extensionRunner = object : ExtensionRunner {
                override suspend fun run(
                    extension: InstalledExtension,
                    credentials: ExtensionCredentials,
                ): SyncResult {
                    receivedExtension = extension
                    receivedCredentials = credentials
                    return expected
                }
            },
        )

        val result = coordinator.sync(extension, profile)

        assertSame(expected, result)
        assertSame(extension, receivedExtension)
        assertEquals(profile.username, receivedCredentials?.username)
        assertEquals(profile.password, receivedCredentials?.password)
    }

    @Test
    fun returnsExtensionFailureWithoutNativeLoginStep() = runBlocking {
        val coordinator = BankSyncCoordinator(
            extensionRunner = object : ExtensionRunner {
                override suspend fun run(
                    extension: InstalledExtension,
                    credentials: ExtensionCredentials,
                ): SyncResult = SyncResult.Error("extension login failed")
            },
        )

        val result = coordinator.sync(extension, profile)

        assertTrue(result is SyncResult.Error)
        assertEquals("extension login failed", (result as SyncResult.Error).message)
    }
}
