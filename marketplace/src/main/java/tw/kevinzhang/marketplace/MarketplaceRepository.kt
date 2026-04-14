package tw.kevinzhang.marketplace

import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionManifest

interface MarketplaceRepository {
    /** Fetches index.min.json from the given GitHub repo URL. */
    suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry>

    /** Fetches {path}/manifest.json from the given GitHub repo URL. */
    suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest

    /** Downloads {path}/extension-script.min.js and saves to internal storage. Returns local file path. */
    suspend fun downloadScript(repoUrl: String, path: String, extensionId: String): String
}
