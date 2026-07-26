package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.SourceDocument
import tw.kevinzhang.core.data.model.TransferFieldObservation

/** Keyed, app-private fingerprints for one normalized transfer returned by an extension. */
data class TransferFingerprintEvidence(
    val sourceFingerprint: String,
    val payloadFingerprint: String,
)

/** Privacy-safe facts associated with one completed snapshot write. */
data class IngestionContext(
    val runId: String,
    val startedAt: Long,
    val completedAt: Long,
    val extensionVersion: Int,
    val artifactRevision: String?,
    val artifactSha256: String?,
    val trigger: IngestionTrigger,
    val status: IngestionStatus,
    val classificationStatus: IngestionClassificationStatus =
        IngestionClassificationStatus.PENDING,
    val sourceFingerprint: String,
    val fingerprintKeyVersion: Int,
    val transferFingerprints: Map<String, TransferFingerprintEvidence>,
    val sourceDocuments: List<SourceDocument> = emptyList(),
    val fieldObservations: List<TransferFieldObservation> = emptyList(),
)

/** A successful inclusive date range whose transactions may safely replace local history. */
data class TransferDateRange(
    val start: String,
    val end: String,
)

/**
 * A null range list retains the pre-history-extension snapshot behaviour for this account.
 * A non-null list replaces only ranges that the extension confirmed as successfully fetched.
 */
data class AccountTransferRefresh(
    val accountId: String,
    val completedRanges: List<TransferDateRange>?,
)

/**
 * App-private legacy identity used exactly once to retain an existing account ID when an
 * extension upgrades to opaque source keys. Its account number must never be logged.
 */
data class LegacyAccountIdentity(
    val accountNo: String,
    val kind: AssetKind,
    val currency: String,
) {
    override fun toString(): String = "LegacyAccountIdentity([REDACTED])"
}

interface TransferSyncStore {
    suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
        /** Per-card metadata for returned credit-card accounts; PANs are already encrypted. */
        cardInstruments: List<CreditCardInstrument> = emptyList(),
        /** Credit-card account IDs whose card list is an authoritative complete snapshot. */
        replaceCardAccountIds: Set<String> = emptySet(),
        /** Proposed account ID to its unique, app-private legacy identity for one-time migration. */
        legacyIdentityByAccountId: Map<String, LegacyAccountIdentity> = emptyMap(),
        /** Null keeps legacy whole-extension replacement; otherwise only these kinds are authoritative. */
        replaceKinds: Set<AssetKind>? = null,
    )

    /**
     * Provenance-aware overload. Keeping the legacy method preserves test doubles and third-party
     * store implementations while Room writes events atomically in its concrete implementation.
     */
    suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
        cardInstruments: List<CreditCardInstrument> = emptyList(),
        replaceCardAccountIds: Set<String> = emptySet(),
        legacyIdentityByAccountId: Map<String, LegacyAccountIdentity> = emptyMap(),
        replaceKinds: Set<AssetKind>? = null,
        ingestionContext: IngestionContext,
    ) {
        replaceSnapshot(
            extensionId, accounts, transfers, refreshes, cardInstruments, replaceCardAccountIds,
            legacyIdentityByAccountId, replaceKinds,
        )
    }

    /** Safe failure ledger entry for a run whose snapshot transaction did not commit. */
    suspend fun recordFailedIngestion(
        extensionId: String,
        ingestionContext: IngestionContext,
        accountCount: Int,
        transferCount: Int,
    ) = Unit

    /** Classification completion is explicit because it occurs after snapshot persistence. */
    suspend fun updateClassificationStatus(
        runId: String,
        status: IngestionClassificationStatus,
        completedAt: Long?,
    ) = Unit
}
