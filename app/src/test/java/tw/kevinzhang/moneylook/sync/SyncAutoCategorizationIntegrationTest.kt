package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.LegacyAccountIdentity
import tw.kevinzhang.core.data.db.IngestionContext
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import tw.kevinzhang.moneylook.security.ProtectedSourceFingerprint
import tw.kevinzhang.moneylook.security.SourceFingerprintProtector
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class SyncAutoCategorizationIntegrationTest {
    @Test
    fun `persisted transfer ids are forwarded to automatic categorization`() = runBlocking {
        val store = RecordingStore()
        var receivedIds = emptyList<String>()
        var receivedRunId: String? = null
        val persister = SyncResultPersister(
            transferSyncStore = store,
            autoCategorizer = object : TransferAutoCategorizer {
                override suspend fun categorizeTransferIds(transferIds: List<String>) {
                    receivedIds = transferIds
                }

                override suspend fun categorizeTransferIds(
                    transferIds: List<String>,
                    ingestionRunId: String,
                ) {
                    receivedIds = transferIds
                    receivedRunId = ingestionRunId
                }
            },
            sourceFingerprintProtector = FakeSourceFingerprintProtector,
        )

        persister.persist(
            extension = InstalledExtension(
                id = "extension",
                manifestId = "bank",
                name = "Bank",
                version = 1,
                repoUrl = "https://github.com/example/extensions",
                syncTriggerCachePath = "/tmp/sync.js",
                iconUrl = null,
            ),
            result = SyncResult.Success(
                listOf(
                    AccountData(
                        name = "帳戶",
                        balance = 100.0,
                        currency = "TWD",
                        sourceAccountKey = "source-account",
                        transfers = listOf(
                            TransferData(
                                txnDateTime = "2026-07-22",
                                description = "午餐",
                                amount = -120.0,
                                balance = null,
                                memo = "",
                                id = "source-transfer",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(store.transfers.map(Transfer::id), receivedIds)
        assertEquals(store.ingestionContext?.runId, receivedRunId)
        assertTrue(store.ingestionContext?.transferFingerprints?.keys == receivedIds.toSet())
        val evidence = store.ingestionContext!!.transferFingerprints.values.single()
        assertNotEquals(evidence.sourceFingerprint, evidence.payloadFingerprint)
        assertEquals(IngestionClassificationStatus.COMPLETE, store.classificationStatus)
    }

    private class RecordingStore : TransferSyncStore {
        var transfers = emptyList<Transfer>()
        var ingestionContext: IngestionContext? = null
        var classificationStatus: IngestionClassificationStatus? = null

        override suspend fun replaceSnapshot(
            extensionId: String,
            accounts: List<Account>,
            transfers: List<Transfer>,
            refreshes: List<AccountTransferRefresh>,
            cardInstruments: List<CreditCardInstrument>,
            replaceCardAccountIds: Set<String>,
            legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
            replaceKinds: Set<AssetKind>?,
        ) {
            this.transfers = transfers
        }

        override suspend fun replaceSnapshot(
            extensionId: String,
            accounts: List<Account>,
            transfers: List<Transfer>,
            refreshes: List<AccountTransferRefresh>,
            cardInstruments: List<CreditCardInstrument>,
            replaceCardAccountIds: Set<String>,
            legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
            replaceKinds: Set<AssetKind>?,
            ingestionContext: IngestionContext,
        ) {
            replaceSnapshot(
                extensionId,
                accounts,
                transfers,
                refreshes,
                cardInstruments,
                replaceCardAccountIds,
                legacyIdentityByAccountId,
                replaceKinds,
            )
            this.ingestionContext = ingestionContext
        }

        override suspend fun updateClassificationStatus(
            runId: String,
            status: IngestionClassificationStatus,
            completedAt: Long?,
        ) {
            classificationStatus = status
        }
    }

    private object FakeSourceFingerprintProtector : SourceFingerprintProtector {
        override fun fingerprint(vararg components: String): ProtectedSourceFingerprint =
            ProtectedSourceFingerprint(
                value = components.joinToString("|").hashCode().toUInt().toString(16),
                keyVersion = 7,
            )
    }
}
