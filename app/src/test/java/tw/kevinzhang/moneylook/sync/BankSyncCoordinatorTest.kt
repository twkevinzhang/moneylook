package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.db.TransferCursorStore
import tw.kevinzhang.core.data.db.TransferSyncCursor
import tw.kevinzhang.core.data.db.SyncDiagnosticDao
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.SyncDiagnostic
import tw.kevinzhang.extension_runtime.ExtensionCredential
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.ExtensionSyncContext
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
        credential = """{"customerId":"A123","password":"password"}""",
        scheduleCron = "0 8 * * *",
        timezoneId = "Asia/Taipei",
    )

    @Test
    fun passesPlaintextCredentialJsonDirectlyToExtension() = runBlocking {
        var receivedExtension: InstalledExtension? = null
        var receivedCredential: ExtensionCredential? = null
        var receivedSyncContext: ExtensionSyncContext? = null
        val expected = SyncResult.Success(emptyList())
        val coordinator = BankSyncCoordinator(
            extensionRunner = object : ExtensionRunner {
                override suspend fun run(
                    extension: InstalledExtension,
                    credential: ExtensionCredential,
                    syncContext: ExtensionSyncContext,
                ): SyncResult {
                    receivedExtension = extension
                    receivedCredential = credential
                    receivedSyncContext = syncContext
                    return expected
                }
            },
            transferCursorStore = cursorStore(),
            syncDiagnosticDao = diagnosticDao(),
        )

        val result = coordinator.sync(extension, profile)

        assertSame(expected, result)
        assertSame(extension, receivedExtension)
        assertEquals(profile.credential, receivedCredential?.json)
        assertEquals("deposit-opaque-1", receivedSyncContext?.transferCursors?.single()?.sourceAccountKey)
        assertEquals("deposit", receivedSyncContext?.transferCursors?.single()?.kind)
        assertEquals("TWD", receivedSyncContext?.transferCursors?.single()?.currency)
        assertEquals(
            "2026-07-21T18:00:00+08:00",
            receivedSyncContext?.transferCursors?.single()?.latestTxnDateTime,
        )
        assertEquals(
            "2026-07-01T00:00:00+08:00",
            receivedSyncContext?.transferCursors?.single()?.earliestTxnDateTime,
        )
    }

    @Test
    fun returnsExtensionFailureWithoutNativeLoginStep() = runBlocking {
        val coordinator = BankSyncCoordinator(
            extensionRunner = object : ExtensionRunner {
                override suspend fun run(
                    extension: InstalledExtension,
                    credential: ExtensionCredential,
                    syncContext: ExtensionSyncContext,
                ): SyncResult = SyncResult.Error("extension login failed")
            },
            transferCursorStore = cursorStore(),
            syncDiagnosticDao = diagnosticDao(),
        )

        val result = coordinator.sync(extension, profile)

        assertTrue(result is SyncResult.Error)
        assertEquals("extension login failed", (result as SyncResult.Error).message)
    }

    @Test
    fun mapsTimeDepositCursorKindToSdkContract() = runBlocking {
        var receivedSyncContext: ExtensionSyncContext? = null
        val coordinator = BankSyncCoordinator(
            extensionRunner = object : ExtensionRunner {
                override suspend fun run(
                    extension: InstalledExtension,
                    credential: ExtensionCredential,
                    syncContext: ExtensionSyncContext,
                ): SyncResult {
                    receivedSyncContext = syncContext
                    return SyncResult.Success(emptyList())
                }
            },
            transferCursorStore = cursorStore(AssetKind.TIME_DEPOSIT),
            syncDiagnosticDao = diagnosticDao(),
        )

        coordinator.sync(extension, profile)

        assertEquals("time_deposit", receivedSyncContext?.transferCursors?.single()?.kind)
    }

    private fun cursorStore(kind: AssetKind = AssetKind.DEPOSIT) = object : TransferCursorStore {
        override suspend fun latestByExtension(extensionId: String): List<TransferSyncCursor> = listOf(
            TransferSyncCursor(
                sourceAccountKey = "deposit-opaque-1",
                kind = kind,
                currency = "TWD",
                latestTxnDateTime = "2026-07-21T18:00:00+08:00",
                earliestTxnDateTime = "2026-07-01T00:00:00+08:00",
            ),
        )
    }

    private fun diagnosticDao() = object : SyncDiagnosticDao {
        override suspend fun insert(diagnostic: SyncDiagnostic) = Unit
        override fun observeByExtension(extensionId: String): Flow<List<SyncDiagnostic>> =
            flowOf(emptyList())
    }
}
