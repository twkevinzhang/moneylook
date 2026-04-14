package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult

interface ExtensionRunner {
    /**
     * Runs the extension script for the given installed extension.
     * Must be called from a coroutine context.
     * Returns SyncResult.Error if session is missing, expired, or script throws.
     */
    suspend fun run(extension: InstalledExtension): SyncResult
}
