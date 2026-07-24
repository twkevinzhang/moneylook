package tw.kevinzhang.marketplace

import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionManifest

interface MarketplaceRepository {
    /** Fetches index.min.json from the given GitHub repo URL. */
    suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry>

    /** Fetches, validates, and normalizes {path}/manifest.json from the given GitHub repo URL. */
    suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest

    /**
     * Downloads the sync-trigger script and saves to internal storage.
     * Returns the local path and immutable source identity used by runtime and provenance.
     */
    suspend fun downloadSyncTriggerScript(repoUrl: String, path: String, extensionId: String): DownloadedExtensionArtifact
}

data class DownloadedExtensionArtifact(
    val path: String,
    val immutableRevision: String,
    val sha256: String,
)
