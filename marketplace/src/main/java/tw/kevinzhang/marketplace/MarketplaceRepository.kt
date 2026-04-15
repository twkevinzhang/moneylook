package tw.kevinzhang.marketplace

import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionManifest

interface MarketplaceRepository {
    /** Fetches index.min.json from the given GitHub repo URL. */
    suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry>

    /** Fetches {path}/manifest.json from the given GitHub repo URL. */
    suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest

    /**
     * Downloads the sync-trigger script and saves to internal storage.
     * Returns the absolute local file path.
     */
    suspend fun downloadSyncTriggerScript(repoUrl: String, path: String, extensionId: String): String

    /**
     * Downloads the schedule script and saves to internal storage.
     * Returns the absolute local file path, or null if the extension has no schedule.
     */
    suspend fun downloadScheduleScript(repoUrl: String, path: String, extensionId: String): String?
}
