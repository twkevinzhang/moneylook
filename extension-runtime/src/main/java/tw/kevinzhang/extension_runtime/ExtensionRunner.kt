package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult

interface ExtensionRunner {
    /**
     * Runs the sync-trigger script for the given extension.
     * Returns SyncResult.Error if session is missing, expired, or script throws.
     */
    suspend fun run(extension: InstalledExtension): SyncResult

    /**
     * Runs the schedule script for the given extension.
     * Returns null if the extension has no schedule script cached.
     * Returns null (no cache update) if the script returns void/undefined.
     * Returns SyncResult.Error if the script throws.
     */
    suspend fun runSchedule(extension: InstalledExtension): SyncResult?
}
